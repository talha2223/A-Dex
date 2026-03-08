const path = require('path');
const fs = require('fs');
const { nowMs } = require('../utils/time');
const { generatePairCode, generateToken, generateId } = require('../utils/random');

// Store wraps SQL statements to keep business logic centralized and explicit.
function createStore(db, runtimeConfig) {
  function withJson(value) {
    return JSON.stringify(value ?? {});
  }

  function parseJson(value, fallback = {}) {
    if (!value) {
      return fallback;
    }

    try {
      return JSON.parse(value);
    } catch (_err) {
      return fallback;
    }
  }

  function logAudit({ guildId = null, discordUserId = null, action, target = null, metadata = {} }) {
    db.prepare(
      `INSERT INTO audit_logs (guild_id, discord_user_id, action, target, metadata_json, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).run(guildId, discordUserId, action, target, withJson(metadata), nowMs());
  }

  function registerOrRefreshDevice({
    deviceId,
    deviceToken,
    name = null,
    model = null,
    androidVersion = null,
    appVersion = null,
  }) {
    const existing = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId);
    const now = nowMs();

    if (!existing) {
      const token = generateToken();
      db.prepare(
        `INSERT INTO devices (id, token, name, model, android_version, app_version, status, last_seen, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 'offline', ?, ?, ?)`
      ).run(deviceId, token, name, model, androidVersion, appVersion, now, now, now);

      logAudit({ action: 'device.register', target: deviceId, metadata: { model, androidVersion, appVersion } });
      return { token, deviceId, isNew: true };
    }

    let token = existing.token;

    // Existing devices must present a matching token; if absent we rotate to enforce ownership.
    if (deviceToken && deviceToken === existing.token) {
      token = existing.token;
    } else if (!deviceToken) {
      token = generateToken();
    } else {
      throw new Error('DEVICE_TOKEN_INVALID');
    }

    db.prepare(
      `UPDATE devices
       SET token = ?, name = COALESCE(?, name), model = COALESCE(?, model), android_version = COALESCE(?, android_version),
           app_version = COALESCE(?, app_version), updated_at = ?, last_seen = ?
       WHERE id = ?`
    ).run(token, name, model, androidVersion, appVersion, now, now, deviceId);

    logAudit({ action: 'device.refresh', target: deviceId, metadata: { model, androidVersion, appVersion } });
    return { token, deviceId, isNew: false };
  }

  function validateDeviceToken(deviceId, token) {
    if (!token || !deviceId) {
      return false;
    }

    const row = db.prepare('SELECT id FROM devices WHERE id = ? AND token = ?').get(deviceId, token);
    return Boolean(row);
  }

  function createPairingCode(deviceId) {
    const expiresAt = nowMs() + runtimeConfig.pairCodeTtlSeconds * 1000;
    let code = generatePairCode();

    for (let i = 0; i < 6; i += 1) {
      const exists = db.prepare('SELECT code FROM pairing_codes WHERE code = ? AND expires_at > ? AND claimed_at IS NULL').get(code, nowMs());
      if (!exists) {
        break;
      }
      code = generatePairCode();
    }

    db.prepare('DELETE FROM pairing_codes WHERE device_id = ?').run(deviceId);
    db.prepare(
      `INSERT INTO pairing_codes (code, device_id, expires_at, claimed_at, claimed_by_discord_user_id, created_at)
       VALUES (?, ?, ?, NULL, NULL, ?)`
    ).run(code, deviceId, expiresAt, nowMs());

    logAudit({ action: 'pairing.code.create', target: deviceId, metadata: { code, expiresAt } });
    return { code, expiresAt };
  }

  function ensureGuildAdmin(guildId, discordUserId) {
    if (runtimeConfig.ownerDiscordUserId && runtimeConfig.ownerDiscordUserId === discordUserId) {
      return true;
    }

    const row = db
      .prepare('SELECT guild_id FROM guild_admins WHERE guild_id = ? AND discord_user_id = ?')
      .get(guildId, discordUserId);

    return Boolean(row);
  }

  function claimPairingCode({ code, guildId, channelId, discordUserId }) {
    const entry = db.prepare('SELECT * FROM pairing_codes WHERE code = ?').get(code);
    if (!entry) {
      throw new Error('PAIR_CODE_NOT_FOUND');
    }

    const now = nowMs();
    if (entry.claimed_at) {
      throw new Error('PAIR_CODE_ALREADY_CLAIMED');
    }

    if (entry.expires_at <= now) {
      throw new Error('PAIR_CODE_EXPIRED');
    }

    db.prepare(
      `UPDATE pairing_codes
       SET claimed_at = ?, claimed_by_discord_user_id = ?
       WHERE code = ?`
    ).run(now, discordUserId, code);

    attachDeviceToGuild({
      guildId,
      deviceId: entry.device_id,
      actorUserId: discordUserId,
      source: 'pairing_claim',
    });

    db.prepare(
      `INSERT INTO channel_device_bindings (channel_id, guild_id, device_id, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(channel_id) DO UPDATE SET guild_id = excluded.guild_id, device_id = excluded.device_id, created_at = excluded.created_at`
    ).run(channelId, guildId, entry.device_id, now);

    // Bootstrap guild admin on first successful pairing by the owner account.
    if (runtimeConfig.ownerDiscordUserId && runtimeConfig.ownerDiscordUserId === discordUserId) {
      db.prepare(
        `INSERT INTO guild_admins (guild_id, discord_user_id, created_at)
         VALUES (?, ?, ?)
         ON CONFLICT(guild_id, discord_user_id) DO NOTHING`
      ).run(guildId, discordUserId, now);
    }

    logAudit({
      guildId,
      discordUserId,
      action: 'pairing.code.claim',
      target: entry.device_id,
      metadata: { channelId, code },
    });

    return { deviceId: entry.device_id, channelId, guildId };
  }

  function attachDeviceToGuild({ guildId, deviceId, actorUserId = null, source = 'unknown' }) {
    const device = db.prepare('SELECT id FROM devices WHERE id = ?').get(deviceId);
    if (!device) {
      throw new Error('DEVICE_NOT_FOUND');
    }

    db.prepare(
      `INSERT INTO guild_devices (guild_id, device_id, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(guild_id, device_id) DO NOTHING`
    ).run(guildId, deviceId, nowMs());

    logAudit({
      guildId,
      discordUserId: actorUserId,
      action: 'guild.device.attach',
      target: deviceId,
      metadata: { source },
    });
  }

  function addGuildAdmin({ guildId, targetUserId, actorUserId }) {
    db.prepare(
      `INSERT INTO guild_admins (guild_id, discord_user_id, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(guild_id, discord_user_id) DO NOTHING`
    ).run(guildId, targetUserId, nowMs());

    logAudit({ guildId, discordUserId: actorUserId, action: 'admin.add', target: targetUserId, metadata: {} });
  }

  function removeGuildAdmin({ guildId, targetUserId, actorUserId }) {
    db.prepare('DELETE FROM guild_admins WHERE guild_id = ? AND discord_user_id = ?').run(guildId, targetUserId);

    logAudit({ guildId, discordUserId: actorUserId, action: 'admin.remove', target: targetUserId, metadata: {} });
  }

  function bindChannel({ guildId, channelId, deviceId, actorUserId }) {
    const device = db.prepare('SELECT id FROM devices WHERE id = ?').get(deviceId);
    if (!device) {
      throw new Error('DEVICE_NOT_FOUND');
    }

    attachDeviceToGuild({
      guildId,
      deviceId,
      actorUserId,
      source: 'channel_bind',
    });

    db.prepare(
      `INSERT INTO channel_device_bindings (channel_id, guild_id, device_id, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(channel_id) DO UPDATE SET guild_id = excluded.guild_id, device_id = excluded.device_id, created_at = excluded.created_at`
    ).run(channelId, guildId, deviceId, nowMs());

    logAudit({ guildId, discordUserId: actorUserId, action: 'channel.bind', target: channelId, metadata: { deviceId } });
  }

  function unbindChannel({ guildId, channelId, actorUserId }) {
    db.prepare('DELETE FROM channel_device_bindings WHERE guild_id = ? AND channel_id = ?').run(guildId, channelId);
    logAudit({ guildId, discordUserId: actorUserId, action: 'channel.unbind', target: channelId, metadata: {} });
  }

  function getBoundDevice({ guildId, channelId }) {
    return db
      .prepare('SELECT device_id FROM channel_device_bindings WHERE guild_id = ? AND channel_id = ?')
      .get(guildId, channelId);
  }

  function listDevicesForGuild(guildId) {
    return db
      .prepare(
        `WITH scoped_devices AS (
           SELECT device_id FROM guild_devices WHERE guild_id = ?
           UNION
           SELECT device_id FROM channel_device_bindings WHERE guild_id = ?
         )
         SELECT d.id, d.name, d.model, d.android_version AS androidVersion, d.app_version AS appVersion, d.status, d.last_seen AS lastSeen,
                c.channel_id AS channelId
         FROM scoped_devices sd
         JOIN devices d ON d.id = sd.device_id
         LEFT JOIN channel_device_bindings c ON d.id = c.device_id AND c.guild_id = ?
         ORDER BY d.updated_at DESC`
      )
      .all(guildId, guildId, guildId);
  }

  function markDeviceOnline(deviceId, metadata = {}) {
    db.prepare(
      `UPDATE devices
       SET status = 'online', last_seen = ?, updated_at = ?, model = COALESCE(?, model), android_version = COALESCE(?, android_version), app_version = COALESCE(?, app_version), name = COALESCE(?, name)
       WHERE id = ?`
    ).run(nowMs(), nowMs(), metadata.model || null, metadata.androidVersion || null, metadata.appVersion || null, metadata.name || null, deviceId);
  }

  function markDeviceOffline(deviceId) {
    db.prepare(`UPDATE devices SET status = 'offline', last_seen = ?, updated_at = ? WHERE id = ?`).run(nowMs(), nowMs(), deviceId);
  }

  function createCommand({ guildId, channelId, discordUserId, deviceId, commandName, payload, requestId }) {
    const id = generateId();
    const now = nowMs();
    const expiresAt = now + runtimeConfig.commandTimeoutSeconds * 1000;

    db.prepare(
      `INSERT INTO commands (id, request_id, device_id, guild_id, channel_id, discord_user_id, command_name, payload_json, status, created_at, updated_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?)`
    ).run(id, requestId, deviceId, guildId, channelId, discordUserId, commandName, withJson(payload), now, now, expiresAt);

    return {
      id,
      requestId,
      deviceId,
      guildId,
      channelId,
      discordUserId,
      commandName,
      payload,
      status: 'queued',
      expiresAt,
      createdAt: now,
    };
  }

  function markCommandDispatched(commandId) {
    db.prepare(`UPDATE commands SET status = 'dispatched', updated_at = ? WHERE id = ? AND status = 'queued'`).run(nowMs(), commandId);
  }

  function markCommandFailed(commandId, code, message) {
    db.prepare(
      `UPDATE commands
       SET status = 'failed', error_code = ?, error_message = ?, updated_at = ?
       WHERE id = ?`
    ).run(code, message, nowMs(), commandId);
  }

  function getQueuedCommandsForDevice(deviceId, limit = 30) {
    const rows = db
      .prepare(
        `SELECT * FROM commands
         WHERE device_id = ? AND status = 'queued' AND expires_at > ?
         ORDER BY created_at ASC
         LIMIT ?`
      )
      .all(deviceId, nowMs(), limit);

    return rows.map((row) => ({
      id: row.id,
      requestId: row.request_id,
      deviceId: row.device_id,
      guildId: row.guild_id,
      channelId: row.channel_id,
      discordUserId: row.discord_user_id,
      commandName: row.command_name,
      payload: parseJson(row.payload_json),
      status: row.status,
      expiresAt: row.expires_at,
      createdAt: row.created_at,
    }));
  }

  function getCommandById(commandId) {
    const row = db.prepare('SELECT * FROM commands WHERE id = ?').get(commandId);
    if (!row) {
      return null;
    }

    return {
      id: row.id,
      requestId: row.request_id,
      deviceId: row.device_id,
      guildId: row.guild_id,
      channelId: row.channel_id,
      discordUserId: row.discord_user_id,
      commandName: row.command_name,
      payload: parseJson(row.payload_json),
      status: row.status,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
      expiresAt: row.expires_at,
    };
  }

  function completeCommand({ commandId, status, data = {}, errorCode = null, errorMessage = null, mediaId = null }) {
    const command = getCommandById(commandId);
    if (!command) {
      throw new Error('COMMAND_NOT_FOUND');
    }

    const resultId = generateId();
    db.prepare(
      `INSERT INTO command_results (id, command_id, status, data_json, error_code, error_message, media_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(resultId, commandId, status, withJson(data), errorCode, errorMessage, mediaId, nowMs());

    db.prepare(
      `UPDATE commands
       SET status = ?, error_code = ?, error_message = ?, updated_at = ?
       WHERE id = ?`
    ).run(status === 'success' ? 'completed' : 'failed', errorCode, errorMessage, nowMs(), commandId);

    return {
      resultId,
      command: getCommandById(commandId),
      result: { status, data, errorCode, errorMessage, mediaId },
    };
  }

  function expireTimedOutCommands() {
    const now = nowMs();
    const timedOut = db
      .prepare(
        `SELECT id, guild_id, channel_id, discord_user_id, request_id, command_name
         FROM commands
         WHERE status IN ('queued', 'dispatched') AND expires_at <= ?`
      )
      .all(now);

    if (timedOut.length === 0) {
      return [];
    }

    const update = db.prepare(
      `UPDATE commands
       SET status = 'timed_out', error_code = 'COMMAND_TIMEOUT', error_message = 'Command timed out before result was received', updated_at = ?
       WHERE id = ?`
    );

    db.exec('BEGIN TRANSACTION;');
    try {
      for (const row of timedOut) {
        update.run(now, row.id);
      }
      db.exec('COMMIT;');
    } catch (err) {
      db.exec('ROLLBACK;');
      throw err;
    }

    return timedOut;
  }

  function saveMediaForCommand({ commandId, fileName, mimeType, filePath, fileSize }) {
    const command = getCommandById(commandId);
    if (!command) {
      throw new Error('COMMAND_NOT_FOUND');
    }

    const mediaId = generateId();
    db.prepare(
      `INSERT INTO media_files (id, command_id, file_name, mime_type, file_path, file_size, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run(mediaId, commandId, fileName, mimeType, filePath, fileSize, nowMs());

    return { mediaId };
  }

  function getMediaById(mediaId) {
    return db.prepare('SELECT * FROM media_files WHERE id = ?').get(mediaId);
  }

  function verifyCommandBelongsToDevice(commandId, deviceId) {
    const row = db.prepare('SELECT id FROM commands WHERE id = ? AND device_id = ?').get(commandId, deviceId);
    return Boolean(row);
  }

  function pruneMediaFiles() {
    const cutoff = nowMs() - runtimeConfig.mediaRetentionHours * 60 * 60 * 1000;
    const rows = db.prepare('SELECT id, file_path FROM media_files WHERE created_at < ?').all(cutoff);

    for (const row of rows) {
      try {
        if (row.file_path && fs.existsSync(row.file_path)) {
          fs.unlinkSync(row.file_path);
        }
      } catch (_err) {
        // Ignore filesystem errors so cleanup can continue for other files.
      }

      db.prepare('DELETE FROM media_files WHERE id = ?').run(row.id);
    }

    return rows.length;
  }

  function getLockedApps(deviceId) {
    return db.prepare('SELECT package_name FROM locked_apps WHERE device_id = ? ORDER BY package_name').all(deviceId).map((r) => r.package_name);
  }

  function upsertLockedApp(deviceId, packageName) {
    db.prepare(
      `INSERT INTO locked_apps (device_id, package_name, created_at)
       VALUES (?, ?, ?)
       ON CONFLICT(device_id, package_name) DO NOTHING`
    ).run(deviceId, packageName, nowMs());
  }

  function pruneExpiredPairCodes() {
    db.prepare('DELETE FROM pairing_codes WHERE expires_at <= ?').run(nowMs());
  }

  function getDataPathForMediaFile(fileName) {
    return path.join(runtimeConfig.mediaDir, fileName);
  }

  return {
    registerOrRefreshDevice,
    validateDeviceToken,
    createPairingCode,
    claimPairingCode,
    attachDeviceToGuild,
    ensureGuildAdmin,
    addGuildAdmin,
    removeGuildAdmin,
    bindChannel,
    unbindChannel,
    getBoundDevice,
    listDevicesForGuild,
    markDeviceOnline,
    markDeviceOffline,
    createCommand,
    markCommandDispatched,
    markCommandFailed,
    getQueuedCommandsForDevice,
    getCommandById,
    completeCommand,
    expireTimedOutCommands,
    saveMediaForCommand,
    getMediaById,
    verifyCommandBelongsToDevice,
    pruneMediaFiles,
    upsertLockedApp,
    getLockedApps,
    pruneExpiredPairCodes,
    getDataPathForMediaFile,
    logAudit,
  };
}

module.exports = { createStore };
