-- This schema stores devices, auth tokens, command queues, results, and audit data.
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  token TEXT NOT NULL,
  name TEXT,
  model TEXT,
  android_version TEXT,
  app_version TEXT,
  status TEXT NOT NULL DEFAULT 'offline',
  last_seen INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS pairing_codes (
  code TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  claimed_at INTEGER,
  claimed_by_discord_user_id TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS guild_admins (
  guild_id TEXT NOT NULL,
  discord_user_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (guild_id, discord_user_id)
);

CREATE TABLE IF NOT EXISTS channel_device_bindings (
  channel_id TEXT PRIMARY KEY,
  guild_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS guild_devices (
  guild_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (guild_id, device_id),
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  request_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  guild_id TEXT NOT NULL,
  channel_id TEXT NOT NULL,
  discord_user_id TEXT NOT NULL,
  command_name TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL,
  error_code TEXT,
  error_message TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS command_results (
  id TEXT PRIMARY KEY,
  command_id TEXT NOT NULL,
  status TEXT NOT NULL,
  data_json TEXT,
  error_code TEXT,
  error_message TEXT,
  media_id TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS media_files (
  id TEXT PRIMARY KEY,
  command_id TEXT NOT NULL,
  file_name TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  file_path TEXT NOT NULL,
  file_size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS locked_apps (
  device_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, package_name),
  FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  guild_id TEXT,
  discord_user_id TEXT,
  action TEXT NOT NULL,
  target TEXT,
  metadata_json TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_commands_device_status ON commands(device_id, status);
CREATE INDEX IF NOT EXISTS idx_commands_expiry ON commands(expires_at, status);
CREATE INDEX IF NOT EXISTS idx_pairing_expiry ON pairing_codes(expires_at, claimed_at);
CREATE INDEX IF NOT EXISTS idx_media_created_at ON media_files(created_at);
CREATE INDEX IF NOT EXISTS idx_guild_devices_guild ON guild_devices(guild_id);
