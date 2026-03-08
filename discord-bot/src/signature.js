const crypto = require('crypto');

// Shared HMAC format with backend: sha256(secret, `${timestamp}.${rawBody}`).
function signBody(secret, timestamp, body) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body || {});
  return crypto.createHmac('sha256', secret).update(`${timestamp}.${payload}`).digest('hex');
}

module.exports = { signBody };
