const http = require('http');
const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const morgan = require('morgan');
const { config } = require('./config');
const { initDb } = require('./db');
const { createStore } = require('./services/store');
const { RealtimeHub } = require('./services/realtimeHub');
const { createBotAuthMiddleware, createDeviceAuthMiddleware, requireGuildAdmin } = require('./services/auth');
const { createApiRouter } = require('./routes/api');

function createApp() {
  const db = initDb();
  const store = createStore(db, config);

  const app = express();

  // Preserve raw JSON payload for HMAC verification before parsing mutates shape.
  app.use(express.json({
    limit: '2mb',
    verify: (req, _res, buf) => {
      req.rawBody = buf.toString('utf8');
    },
  }));

  app.use(cors());
  // Hugging Face App tab loads the Space inside an iframe.
  // Disable frame-related security headers that would block embedding.
  app.use(helmet({
    contentSecurityPolicy: false,
    frameguard: false,
  }));
  app.use(morgan('tiny'));

  const server = http.createServer(app);
  const hub = new RealtimeHub(server, { store, config });

  const botAuth = createBotAuthMiddleware(config);
  const deviceAuth = createDeviceAuthMiddleware(store);
  const guildAdminAuth = requireGuildAdmin(store);

  app.use('/api/v1', createApiRouter({ store, hub, config, botAuth, deviceAuth, guildAdminAuth }));

  // Root route is useful for container platforms that probe the service URL.
  app.get('/', (_req, res) => {
    res.json({
      service: 'A-Dex backend',
      health: '/api/v1/health',
      ws: '/ws',
      ts: Date.now(),
    });
  });

  // Last-resort error handler keeps response shape consistent.
  app.use((err, _req, res, _next) => {
    // eslint-disable-next-line no-console
    console.error('Unhandled error:', err);
    res.status(500).json({ error: 'INTERNAL_SERVER_ERROR' });
  });

  return { app, server, hub };
}

if (require.main === module) {
  const { server } = createApp();
  server.listen(config.port, config.host, () => {
    // eslint-disable-next-line no-console
    console.log(`A-Dex backend listening on ${config.host}:${config.port}`);
  });
}

module.exports = { createApp };
