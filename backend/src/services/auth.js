const { computeBotSignature } = require('../utils/signature');

// Bot auth uses signed requests with timestamp to prevent replay.
function createBotAuthMiddleware(config) {
  return function botAuth(req, res, next) {
    const botToken = req.header('x-adex-bot-token');
    if (botToken && config.botWsToken && botToken === config.botWsToken) {
      return next();
    }

    const signature = req.header('x-adex-signature');
    const timestamp = req.header('x-adex-timestamp');

    if (!signature || !timestamp) {
      return res.status(401).json({ error: 'BOT_AUTH_HEADERS_MISSING' });
    }

    const ts = Number(timestamp);
    if (!Number.isFinite(ts)) {
      return res.status(401).json({ error: 'BOT_AUTH_TIMESTAMP_INVALID' });
    }

    const ageMs = Math.abs(Date.now() - ts);
    if (ageMs > 5 * 60 * 1000) {
      return res.status(401).json({ error: 'BOT_AUTH_TIMESTAMP_EXPIRED' });
    }

    const expected = computeBotSignature(config.botHmacSecret, timestamp, req.rawBody || '');
    const expectedBuffer = Buffer.from(expected);
    const signatureBuffer = Buffer.from(signature);

    if (expectedBuffer.length !== signatureBuffer.length || !require('crypto').timingSafeEqual(expectedBuffer, signatureBuffer)) {
      return res.status(401).json({ error: 'BOT_AUTH_SIGNATURE_INVALID' });
    }

    return next();
  };
}

// Device auth is a bearer token tied to a concrete device id.
function createDeviceAuthMiddleware(store) {
  return function deviceAuth(req, res, next) {
    const deviceId = req.header('x-device-id');
    const authorization = req.header('authorization') || '';
    const token = authorization.startsWith('Bearer ') ? authorization.slice('Bearer '.length) : null;

    if (!deviceId || !token) {
      return res.status(401).json({ error: 'DEVICE_AUTH_HEADERS_MISSING' });
    }

    if (!store.validateDeviceToken(deviceId, token)) {
      return res.status(401).json({ error: 'DEVICE_AUTH_INVALID' });
    }

    req.deviceId = deviceId;
    return next();
  };
}

function requireGuildAdmin(store) {
  return function guildAdmin(req, res, next) {
    const guildId = req.body.guildId || req.query.guildId;
    const discordUserId = req.body.discordUserId || req.query.discordUserId || req.body.actorUserId || req.query.actorUserId;

    if (!guildId || !discordUserId) {
      return res.status(400).json({ error: 'GUILD_OR_USER_MISSING' });
    }

    if (!store.ensureGuildAdmin(guildId, discordUserId)) {
      return res.status(403).json({ error: 'DISCORD_USER_NOT_AUTHORIZED' });
    }

    return next();
  };
}

module.exports = {
  createBotAuthMiddleware,
  createDeviceAuthMiddleware,
  requireGuildAdmin,
};
