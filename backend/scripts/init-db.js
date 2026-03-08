const { initDb } = require('../src/db');

// Initializes SQLite schema without starting the HTTP server.
try {
  initDb();
  // eslint-disable-next-line no-console
  console.log('A-Dex database initialized successfully.');
  process.exit(0);
} catch (err) {
  // eslint-disable-next-line no-console
  console.error('Failed to initialize database:', err);
  process.exit(1);
}
