const axios = require('axios');
const WebSocket = require('ws');
const EventEmitter = require('events');
const { signBody } = require('./signature');

class BackendClient extends EventEmitter {
  constructor(config) {
    super();
    this.config = config;
    this.ws = null;
    this.reconnectAttempt = 0;

    this.http = axios.create({
      baseURL: config.backendBaseUrl,
      timeout: 15_000,
    });
  }

  signedHeaders(body) {
    const timestamp = Date.now().toString();
    const signature = signBody(this.config.botHmacSecret, timestamp, body ?? '');
    const headers = {
      'x-adex-timestamp': timestamp,
      'x-adex-signature': signature,
    };
    if (this.config.botWsToken) {
      headers['x-adex-bot-token'] = this.config.botWsToken;
    }
    if (body !== '') {
      headers['content-type'] = 'application/json';
    }
    return headers;
  }

  async post(path, body) {
    const headers = this.signedHeaders(body);
    const response = await this.http.post(path, body, { headers });
    return response.data;
  }

  async delete(path, body) {
    const headers = this.signedHeaders(body);
    const response = await this.http.delete(path, { headers, data: body });
    return response.data;
  }

  async get(path, queryParams) {
    // Backend signs GET requests against an empty raw body.
    const headers = this.signedHeaders('');
    const response = await this.http.get(path, { headers, params: queryParams });
    return response.data;
  }

  async getMedia(mediaId) {
    // Backend signs GET requests against an empty raw body.
    const headers = this.signedHeaders('');
    const response = await this.http.get(`/api/v1/media/${mediaId}`, {
      headers,
      responseType: 'arraybuffer',
    });

    return {
      contentType: response.headers['content-type'] || 'application/octet-stream',
      buffer: Buffer.from(response.data),
    };
  }

  connectWebSocket() {
    this.ws = new WebSocket(this.config.backendWsUrl);

    this.ws.on('open', () => {
      this.reconnectAttempt = 0;
      this.ws.send(JSON.stringify({ type: 'bot.subscribe', token: this.config.botWsToken }));
    });

    this.ws.on('message', (buf) => {
      let payload;
      try {
        payload = JSON.parse(buf.toString());
      } catch (_err) {
        return;
      }

      if (payload.type === 'bot.command_result') {
        this.emit('commandResult', payload);
      } else if (payload.type === 'bot.device_status') {
        this.emit('deviceStatus', payload);
      } else if (payload.type === 'bot.device_event') {
        this.emit('deviceEvent', payload);
      }
    });

    this.ws.on('close', () => {
      this.scheduleReconnect();
    });

    this.ws.on('error', (_err) => {
      this.scheduleReconnect();
    });
  }

  scheduleReconnect() {
    if (this.ws) {
      try {
        this.ws.terminate();
      } catch (_err) {
        // Ignore close errors so reconnect can continue.
      }
      this.ws = null;
    }

    this.reconnectAttempt += 1;
    const delayMs = Math.min(30_000, 1_000 * 2 ** Math.min(6, this.reconnectAttempt));
    setTimeout(() => this.connectWebSocket(), delayMs);
  }
}

module.exports = { BackendClient };
