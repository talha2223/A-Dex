const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const { config } = require('./config');

let db;

function initDb() {
  if (db) {
    return db;
  }

  // Open SQLite database in WAL mode for better concurrent read/write behavior.
  db = new DatabaseSync(config.dbPath);
  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA foreign_keys = ON;');

  const schemaPath = path.join(__dirname, '..', 'migrations', 'init.sql');
  const schema = fs.readFileSync(schemaPath, 'utf8');
  db.exec(schema);

  return db;
}

function getDb() {
  if (!db) {
    return initDb();
  }
  return db;
}

module.exports = { initDb, getDb };
