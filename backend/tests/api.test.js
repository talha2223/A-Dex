const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const request = require('supertest');
const { computeBotSignature } = require('../src/utils/signature');

function signedHeaders(secret, body) {
  const timestamp = Date.now().toString();
  const rawBody = JSON.stringify(body || {});
  const signature = computeBotSignature(secret, timestamp, rawBody);
  return {
    'x-adex-timestamp': timestamp,
    'x-adex-signature': signature,
  };
}

test('health endpoint responds with ok status', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'adex-backend-health-'));
  process.env.DB_PATH = path.join(tempDir, 'adex.db');
  process.env.MEDIA_DIR = path.join(tempDir, 'media');
  process.env.BOT_HMAC_SECRET = 'test-secret';
  process.env.BOT_WS_TOKEN = 'test-ws';
  process.env.OWNER_DISCORD_USER_ID = 'owner1';

  // Clear require cache so config picks up fresh env values.
  delete require.cache[require.resolve('../src/server')];
  delete require.cache[require.resolve('../src/config')];
  delete require.cache[require.resolve('../src/db')];

  const { createApp } = require('../src/server');
  const { app, hub } = createApp();

  const response = await request(app).get('/api/v1/health');
  assert.equal(response.statusCode, 200);
  assert.equal(response.body.status, 'ok');

  hub.close();
});

test('device command allowlist includes parental shield, file manager, and set-b modules', () => {
  const { DEVICE_COMMANDS } = require('../src/routes/api');
  assert.equal(DEVICE_COMMANDS.has('parentpin'), true);
  assert.equal(DEVICE_COMMANDS.has('shield'), true);
  assert.equal(DEVICE_COMMANDS.has('playaudio'), true);
  assert.equal(DEVICE_COMMANDS.has('stopaudio'), true);
  assert.equal(DEVICE_COMMANDS.has('pauseaudio'), true);
  assert.equal(DEVICE_COMMANDS.has('resumeaudio'), true);
  assert.equal(DEVICE_COMMANDS.has('audiostatus'), true);
  assert.equal(DEVICE_COMMANDS.has('filestat'), true);
  assert.equal(DEVICE_COMMANDS.has('mkdir'), true);
  assert.equal(DEVICE_COMMANDS.has('rename'), true);
  assert.equal(DEVICE_COMMANDS.has('move'), true);
  assert.equal(DEVICE_COMMANDS.has('delete'), true);
  assert.equal(DEVICE_COMMANDS.has('uploadfile'), true);
  assert.equal(DEVICE_COMMANDS.has('readtext'), true);
  assert.equal(DEVICE_COMMANDS.has('camerasnap'), true);
  assert.equal(DEVICE_COMMANDS.has('contactlookup'), true);
  assert.equal(DEVICE_COMMANDS.has('smsdraft'), true);
  assert.equal(DEVICE_COMMANDS.has('fileshareintent'), true);
  assert.equal(DEVICE_COMMANDS.has('quicklaunch'), true);
  assert.equal(DEVICE_COMMANDS.has('torchpattern'), true);
  assert.equal(DEVICE_COMMANDS.has('ringtoneprofile'), true);
  assert.equal(DEVICE_COMMANDS.has('screentimeoutset'), true);
  assert.equal(DEVICE_COMMANDS.has('mediacontrol'), true);
  assert.equal(DEVICE_COMMANDS.has('randomquote'), true);
  assert.equal(DEVICE_COMMANDS.has('fakecallui'), true);
  assert.equal(DEVICE_COMMANDS.has('shakealert'), true);
});

test('pairing code creation and claim flow works for owner user', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'adex-backend-pairing-'));
  process.env.DB_PATH = path.join(tempDir, 'adex.db');
  process.env.MEDIA_DIR = path.join(tempDir, 'media');
  process.env.BOT_HMAC_SECRET = 'test-secret';
  process.env.BOT_WS_TOKEN = 'test-ws';
  process.env.OWNER_DISCORD_USER_ID = 'owner1';

  delete require.cache[require.resolve('../src/server')];
  delete require.cache[require.resolve('../src/config')];
  delete require.cache[require.resolve('../src/db')];

  const { createApp } = require('../src/server');
  const { app, hub } = createApp();

  const pairResponse = await request(app)
    .post('/api/v1/pairing/code')
    .send({ deviceId: 'device-test-1', name: 'Pixel Test', androidVersion: '13' });

  assert.equal(pairResponse.statusCode, 200);
  assert.ok(pairResponse.body.pairCode);
  assert.ok(pairResponse.body.deviceToken);

  const claimBody = {
    code: pairResponse.body.pairCode,
    guildId: 'guild-1',
    channelId: 'channel-1',
    discordUserId: 'owner1',
  };

  const claimResponse = await request(app)
    .post('/api/v1/pairing/claim')
    .set(signedHeaders('test-secret', claimBody))
    .send(claimBody);

  assert.equal(claimResponse.statusCode, 200);
  assert.equal(claimResponse.body.deviceId, 'device-test-1');

  hub.close();
});

test('auto enrollment token attaches device to configured guild without manual pair claim', async () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'adex-backend-auto-enroll-'));
  process.env.DB_PATH = path.join(tempDir, 'adex.db');
  process.env.MEDIA_DIR = path.join(tempDir, 'media');
  process.env.BOT_HMAC_SECRET = 'test-secret';
  process.env.BOT_WS_TOKEN = 'test-ws';
  process.env.OWNER_DISCORD_USER_ID = 'owner1';
  process.env.AUTO_ENROLL_TOKEN = 'enroll-secret';
  process.env.AUTO_ENROLL_GUILD_ID = 'guild-auto-1';
  process.env.AUTO_ENROLL_CHANNEL_ID = 'channel-auto-1';
  process.env.AUTO_ENROLL_BIND_CHANNEL = 'false';

  delete require.cache[require.resolve('../src/server')];
  delete require.cache[require.resolve('../src/config')];
  delete require.cache[require.resolve('../src/db')];

  const { createApp } = require('../src/server');
  const { app, hub } = createApp();

  const pairResponse = await request(app)
    .post('/api/v1/pairing/code')
    .send({
      deviceId: 'device-auto-1',
      enrollmentToken: 'enroll-secret',
      name: 'Auto Device',
      model: 'Pixel X',
      androidVersion: '14',
    });

  assert.equal(pairResponse.statusCode, 200);
  assert.equal(pairResponse.body.autoEnrolled, true);
  assert.equal(pairResponse.body.autoEnrollGuildId, 'guild-auto-1');

  const devicesResponse = await request(app)
    .get('/api/v1/devices')
    .set('x-adex-bot-token', 'test-ws')
    .query({ guildId: 'guild-auto-1', discordUserId: 'owner1' });

  assert.equal(devicesResponse.statusCode, 200);
  assert.ok(Array.isArray(devicesResponse.body.devices));
  assert.equal(devicesResponse.body.devices.length, 1);
  assert.equal(devicesResponse.body.devices[0].id, 'device-auto-1');
  assert.equal(devicesResponse.body.devices[0].channelId, null);

  hub.close();
});
