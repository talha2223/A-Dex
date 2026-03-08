const { WebSocketServer } = require('ws');

class RealtimeHub {
  constructor(httpServer, { store, config }) {
    this.store = store;
    this.config = config;
    this.deviceSockets = new Map();
    this.botSockets = new Set();

    // Attach a WebSocket server to the existing HTTP server under /ws.
    this.wss = new WebSocketServer({ noServer: true });
    httpServer.on('upgrade', (request, socket, head) => {
      try {
        const url = new URL(request.url, `http://${request.headers.host}`);
        if (url.pathname !== '/ws') {
          socket.destroy();
          return;
        }

        this.wss.handleUpgrade(request, socket, head, (ws) => {
          this.wss.emit('connection', ws, request);
        });
      } catch (_err) {
        socket.destroy();
      }
    });

    this.wss.on('connection', (ws) => this.attachSocket(ws));

    // Timers enforce command timeout behavior and storage cleanup.
    this.timeoutTimer = setInterval(() => this.expireTimedOutCommands(), 5_000);
    this.cleanupTimer = setInterval(() => {
      this.store.pruneExpiredPairCodes();
      this.store.pruneMediaFiles();
    }, 60_000);
  }

  attachSocket(ws) {
    ws.session = {
      role: null,
      deviceId: null,
      heartbeatAt: Date.now(),
    };

    ws.on('message', (buf) => this.handleMessage(ws, buf));
    ws.on('close', () => this.handleClose(ws));
    ws.on('error', () => this.handleClose(ws));

    ws.send(JSON.stringify({ type: 'server.hello', ts: Date.now() }));
  }

  handleMessage(ws, buf) {
    let payload;
    try {
      payload = JSON.parse(buf.toString());
    } catch (_err) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'INVALID_JSON' }));
      return;
    }

    if (!payload.type) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'MISSING_TYPE' }));
      return;
    }

    switch (payload.type) {
      case 'device.hello':
        this.handleDeviceHello(ws, payload);
        break;
      case 'device.heartbeat':
        this.handleDeviceHeartbeat(ws);
        break;
      case 'device.result':
        this.handleDeviceResult(ws, payload);
        break;
      case 'device.event':
        this.handleDeviceEvent(ws, payload);
        break;
      case 'bot.subscribe':
        this.handleBotSubscribe(ws, payload);
        break;
      default:
        ws.send(JSON.stringify({ type: 'server.error', code: 'UNKNOWN_TYPE', detail: payload.type }));
        break;
    }
  }

  handleDeviceHello(ws, payload) {
    const { deviceId, deviceToken, model, androidVersion, appVersion, name } = payload;
    if (!deviceId || !deviceToken) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'DEVICE_AUTH_REQUIRED' }));
      ws.close();
      return;
    }

    if (!this.store.validateDeviceToken(deviceId, deviceToken)) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'DEVICE_AUTH_INVALID' }));
      ws.close();
      return;
    }

    ws.session.role = 'device';
    ws.session.deviceId = deviceId;
    ws.session.heartbeatAt = Date.now();

    // Only one active connection per device is kept to simplify routing.
    const existing = this.deviceSockets.get(deviceId);
    if (existing && existing !== ws) {
      existing.close();
    }

    this.deviceSockets.set(deviceId, ws);
    this.store.markDeviceOnline(deviceId, { model, androidVersion, appVersion, name });

    ws.send(JSON.stringify({ type: 'server.device.ready', deviceId, ts: Date.now() }));
    this.fanoutDeviceStatus(deviceId, 'online');

    this.flushQueuedCommands(deviceId);
  }

  handleDeviceHeartbeat(ws) {
    if (ws.session.role !== 'device' || !ws.session.deviceId) {
      return;
    }

    ws.session.heartbeatAt = Date.now();
    ws.send(JSON.stringify({ type: 'server.heartbeat.ack', ts: Date.now() }));
  }

  handleBotSubscribe(ws, payload) {
    if (!payload.token || payload.token !== this.config.botWsToken) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'BOT_WS_AUTH_INVALID' }));
      ws.close();
      return;
    }

    ws.session.role = 'bot';
    this.botSockets.add(ws);
    ws.send(JSON.stringify({ type: 'server.bot.ready', ts: Date.now() }));
  }

  handleDeviceResult(ws, payload) {
    if (ws.session.role !== 'device' || !ws.session.deviceId) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'DEVICE_NOT_AUTHENTICATED' }));
      return;
    }

    const { commandId, status, data, errorCode, errorMessage, mediaId } = payload;
    if (!commandId || !status) {
      ws.send(JSON.stringify({ type: 'server.error', code: 'RESULT_FIELDS_REQUIRED' }));
      return;
    }

    try {
      const completed = this.store.completeCommand({
        commandId,
        status,
        data: data || {},
        errorCode: errorCode || null,
        errorMessage: errorMessage || null,
        mediaId: mediaId || null,
      });

      this.fanoutCommandResult({
        commandId,
        requestId: completed.command.requestId,
        guildId: completed.command.guildId,
        channelId: completed.command.channelId,
        discordUserId: completed.command.discordUserId,
        deviceId: completed.command.deviceId,
        commandName: completed.command.commandName,
        status,
        data: data || {},
        errorCode: errorCode || null,
        errorMessage: errorMessage || null,
        mediaId: mediaId || null,
        ts: Date.now(),
      });

      ws.send(JSON.stringify({ type: 'server.result.ack', commandId, ts: Date.now() }));
    } catch (err) {
      ws.send(JSON.stringify({ type: 'server.error', code: err.message || 'RESULT_STORE_FAILED' }));
    }
  }

  handleDeviceEvent(ws, payload) {
    if (ws.session.role !== 'device' || !ws.session.deviceId) {
      return;
    }

    const eventType = payload.eventType || 'unknown';
    this.store.logAudit({
      action: `device.event.${eventType}`,
      target: ws.session.deviceId,
      metadata: payload.data || {},
    });

    this.fanout({
      type: 'bot.device_event',
      deviceId: ws.session.deviceId,
      eventType,
      data: payload.data || {},
      ts: Date.now(),
    });
  }

  handleClose(ws) {
    if (ws.session.role === 'bot') {
      this.botSockets.delete(ws);
      return;
    }

    if (ws.session.role === 'device' && ws.session.deviceId) {
      const existing = this.deviceSockets.get(ws.session.deviceId);
      if (existing === ws) {
        this.deviceSockets.delete(ws.session.deviceId);
        this.store.markDeviceOffline(ws.session.deviceId);
        this.fanoutDeviceStatus(ws.session.deviceId, 'offline');
      }
    }
  }

  fanoutDeviceStatus(deviceId, status) {
    this.fanout({ type: 'bot.device_status', deviceId, status, ts: Date.now() });
  }

  fanoutCommandResult(payload) {
    this.fanout({ type: 'bot.command_result', ...payload });
  }

  fanout(payload) {
    const message = JSON.stringify(payload);
    for (const ws of this.botSockets) {
      if (ws.readyState === 1) {
        ws.send(message);
      }
    }
  }

  dispatchCommand(command) {
    const ws = this.deviceSockets.get(command.deviceId);
    if (!ws || ws.readyState !== 1) {
      return false;
    }

    ws.send(
      JSON.stringify({
        type: 'device.command',
        commandId: command.id,
        requestId: command.requestId,
        commandName: command.commandName,
        payload: command.payload,
        expiresAt: command.expiresAt,
        ts: Date.now(),
      })
    );

    this.store.markCommandDispatched(command.id);
    return true;
  }

  flushQueuedCommands(deviceId) {
    const queued = this.store.getQueuedCommandsForDevice(deviceId, 50);
    for (const command of queued) {
      this.dispatchCommand(command);
    }
  }

  expireTimedOutCommands() {
    const expired = this.store.expireTimedOutCommands();
    for (const row of expired) {
      this.fanoutCommandResult({
        commandId: row.id,
        requestId: row.request_id,
        guildId: row.guild_id,
        channelId: row.channel_id,
        discordUserId: row.discord_user_id,
        commandName: row.command_name,
        status: 'error',
        data: {},
        errorCode: 'COMMAND_TIMEOUT',
        errorMessage: 'Command timed out before result was received',
        mediaId: null,
        ts: Date.now(),
      });
    }
  }

  close() {
    clearInterval(this.timeoutTimer);
    clearInterval(this.cleanupTimer);
    this.wss.close();
  }
}

module.exports = { RealtimeHub };
