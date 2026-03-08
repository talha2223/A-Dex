const path = require('path');
const fs = require('fs');
const dotenv = require('dotenv');

// Load environment variables from backend/.env when present.
dotenv.config();

const baseDir = process.cwd();
const dataDir = path.join(baseDir, 'data');
const dbPath = process.env.DB_PATH ? path.resolve(baseDir, process.env.DB_PATH) : path.join(dataDir, 'adex.db');
const mediaDir = process.env.MEDIA_DIR ? path.resolve(baseDir, process.env.MEDIA_DIR) : path.join(dataDir, 'media');

// Ensure runtime directories exist before server startup.
fs.mkdirSync(path.dirname(dbPath), { recursive: true });
fs.mkdirSync(mediaDir, { recursive: true });

const config = {
  host: process.env.HOST || '0.0.0.0',
  port: Number(process.env.PORT || 8080),
  dbPath,
  mediaDir,
  botHmacSecret: process.env.BOT_HMAC_SECRET || 'dev-secret-change-me',
  botWsToken: process.env.BOT_WS_TOKEN || 'dev-ws-token-change-me',
  ownerDiscordUserId: process.env.OWNER_DISCORD_USER_ID || '',
  autoEnrollToken: process.env.AUTO_ENROLL_TOKEN || '',
  autoEnrollGuildId: process.env.AUTO_ENROLL_GUILD_ID || '',
  autoEnrollChannelId: process.env.AUTO_ENROLL_CHANNEL_ID || '',
  autoEnrollBindChannel: String(process.env.AUTO_ENROLL_BIND_CHANNEL || 'false').toLowerCase() === 'true',
  pairCodeTtlSeconds: Number(process.env.PAIR_CODE_TTL_SECONDS || 300),
  commandTimeoutSeconds: Number(process.env.COMMAND_TIMEOUT_SECONDS || 90),
  mediaRetentionHours: Number(process.env.MEDIA_RETENTION_HOURS || 24),
  maxUploadBytes: Number(process.env.MAX_UPLOAD_BYTES || 10 * 1024 * 1024),
};

module.exports = { config };
