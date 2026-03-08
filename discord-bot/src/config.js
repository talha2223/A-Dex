const dotenv = require('dotenv');

dotenv.config();

const config = {
  discordBotToken: process.env.DISCORD_BOT_TOKEN || '',
  discordClientId: process.env.DISCORD_CLIENT_ID || '',
  backendBaseUrl: process.env.BACKEND_BASE_URL || 'http://127.0.0.1:8080',
  backendWsUrl: process.env.BACKEND_WS_URL || 'ws://127.0.0.1:8080/ws',
  botHmacSecret: process.env.BOT_HMAC_SECRET || 'dev-secret-change-me',
  botWsToken: process.env.BOT_WS_TOKEN || 'dev-ws-token-change-me',
  commandPrefix: process.env.COMMAND_PREFIX || '!',
  showImageMaxBytes: Number(process.env.SHOW_IMAGE_MAX_BYTES || 8_000_000),
};

module.exports = { config };
