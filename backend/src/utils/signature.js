const crypto = require('crypto');

function computeBotSignature(secret, timestamp, rawBody) {
  return crypto.createHmac('sha256', secret).update(`${timestamp}.${rawBody || ''}`).digest('hex');
}

module.exports = { computeBotSignature };
