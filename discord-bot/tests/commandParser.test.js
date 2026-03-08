const test = require('node:test');
const assert = require('node:assert/strict');
const { parseCommandInput, buildRemotePayload } = require('../src/commandParser');

test('parseCommandInput parses prefix command and arguments', () => {
  const parsed = parseCommandInput('!say "hello world"', '!');
  assert.equal(parsed.name, 'say');
  assert.equal(parsed.args[0], 'hello world');
  assert.equal(parsed.isRemote, true);
});

test('buildRemotePayload validates show command attachment', () => {
  const result = buildRemotePayload('show', ['10'], {
    url: 'https://cdn.example.com/a.png',
    name: 'a.png',
    size: 1024,
    contentType: 'image/png',
  }, 8_000_000);

  assert.ok(result.payload);
  assert.equal(result.payload.seconds, 10);
  assert.equal(result.payload.imageContentType, 'image/png');
});

test('buildRemotePayload rejects invalid volume values', () => {
  const result = buildRemotePayload('volume', ['101'], null, 8_000_000);
  assert.ok(result.error);
});

test('buildRemotePayload supports permstatus command', () => {
  const result = buildRemotePayload('permstatus', [], null, 8_000_000);
  assert.ok(result.payload);
  assert.deepEqual(result.payload, {});
});

test('buildRemotePayload supports parentpin command', () => {
  const result = buildRemotePayload('parentpin', ['1255'], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.pin, '1255');
});

test('buildRemotePayload supports shield default status', () => {
  const result = buildRemotePayload('shield', [], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.action, 'status');
});

test('buildRemotePayload supports playaudio command', () => {
  const result = buildRemotePayload('playaudio', ['https://cdn.example.com/a.mp3', '3'], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.url, 'https://cdn.example.com/a.mp3');
  assert.equal(result.payload.repeat, 3);
});

test('buildRemotePayload supports sayurdu command', () => {
  const result = buildRemotePayload('sayurdu', ['aap', 'kaise', 'hain'], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.text, 'aap kaise hain');
});

test('buildRemotePayload supports unlockapp command', () => {
  const result = buildRemotePayload('unlockapp', ['com.example.app'], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.packageName, 'com.example.app');
});

test('buildRemotePayload supports advanced files command', () => {
  const result = buildRemotePayload('filestat', ['/storage/emulated/0/test.txt'], null, 8_000_000);
  assert.ok(result.payload);
  assert.equal(result.payload.path, '/storage/emulated/0/test.txt');
});

test('buildRemotePayload supports set-b modules', () => {
  const torch = buildRemotePayload('torchpattern', ['4', '200', '300'], null, 8_000_000);
  assert.ok(torch.payload);
  assert.equal(torch.payload.repeats, 4);
  assert.equal(torch.payload.on_ms, 200);
  assert.equal(torch.payload.off_ms, 300);

  const media = buildRemotePayload('mediacontrol', ['pause'], null, 8_000_000);
  assert.ok(media.payload);
  assert.equal(media.payload.action, 'pause');
});
