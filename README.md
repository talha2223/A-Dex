# A-Dex (Android Discord Executor)

A-Dex is a production-oriented remote Android control stack with three components:

1. `android-app/` - Kotlin Android app (API 23+)
2. `backend/` - Node.js Express + WebSocket API server
3. `discord-bot/` - Discord command bot (Python `discord.py`)

The system supports remote automation, device management, and parental-control enforcement through Discord commands.

## Architecture

- Android app starts a foreground service, registers to backend, and maintains a resilient WebSocket session.
- Discord bot parses commands and sends signed API requests to backend.
- Backend authenticates and routes commands to the correct channel-bound device.
- Android app executes commands and returns structured results/media to backend.
- Backend pushes results to bot subscribers over WebSocket.

## Repository Layout

```text
A-Dex/
  android-app/
    branding/
  backend/
    migrations/init.sql
    scripts/init-db.js
  discord-bot/
  scripts/
    build-android-brand.ps1
    generate_android_icons.py
    start-backend.ps1
    start-discord-bot.ps1
    start-cloudflare-tunnel.ps1
    health-check.ps1
```

## Security Model

- Every device has a unique `deviceId` + `deviceToken`.
- Bot API calls are signed with HMAC SHA256 using `x-adex-timestamp` and `x-adex-signature`.
- Only authorized guild admins can run control commands.
- Device targeting is deterministic: each Discord channel binds to one device.
- Pairing uses short-lived one-time codes.

## Backend Setup (Local PC)

1. Copy env template:
   - `Copy-Item backend/.env.example backend/.env`
2. Edit `backend/.env`:
   - Set `BOT_HMAC_SECRET` and `BOT_WS_TOKEN` to strong random values.
   - Set `OWNER_DISCORD_USER_ID` to your Discord user ID.
   - Optional zero-code enrollment:
     - `AUTO_ENROLL_TOKEN` (shared secret embedded in Android build)
     - `AUTO_ENROLL_GUILD_ID` (guild where new devices should appear)
     - `AUTO_ENROLL_CHANNEL_ID` (channel to receive auto-enroll event messages)
     - `AUTO_ENROLL_BIND_CHANNEL=true` only if you want that channel auto-bound to latest device
3. Install and initialize:
   - `cd backend`
   - `npm install`
   - `npm run init-db`
4. Start backend:
   - `npm run start`
5. Verify:
   - `powershell -ExecutionPolicy Bypass -File .\scripts\health-check.ps1`

API base: `http://127.0.0.1:8080/api/v1`
WebSocket endpoint: `ws://127.0.0.1:8080/ws`

Backend runtime requirement: Node.js 22+ (uses built-in `node:sqlite`).

## Discord Bot Setup

1. Create Discord application + bot in Discord Developer Portal.
2. Enable privileged intent: `MESSAGE CONTENT INTENT`.
3. Invite bot with scope `bot` and message read/send permissions.
4. Copy env template:
   - `Copy-Item discord-bot/.env.example discord-bot/.env`
5. Edit `discord-bot/.env`:
   - `DISCORD_BOT_TOKEN`
   - `BACKEND_BASE_URL`
   - `BACKEND_WS_URL`
   - `BOT_HMAC_SECRET` (must match backend)
   - `BOT_WS_TOKEN` (must match backend)
6. Start bot:
   - `cd discord-bot`
   - `python -m pip install -r requirements.txt`
   - `python -m bot.main`

## Android App Setup

1. Open `android-app/` in Android Studio.
2. Allow Gradle sync and install SDK for API 34.
3. Build and install debug app to device (API 23+).
4. Open app and tap `Open Permission Setup` until all are granted:
   - Runtime permissions (location/camera/audio/storage)
   - Overlay permission
   - Usage access
   - Accessibility service (`A-Dex App Monitor`)
   - Device admin activation (`A-Dex Device Admin`)
5. Tap `Start Service`.
6. Read pair code from app status/foreground notification.

### Config-Driven Branding + Signed Release Build

1. Edit `android-app/branding/branding.json`:
   - `appName`
   - `iconSource` / `roundIconSource`
   - optional `applicationIdSuffix` / `versionNameSuffix`
   - `signing` block (`keystorePath`, `keyAlias`, env var names for passwords)
2. Set keystore password env vars:
   - `ADEX_KEYSTORE_PASSWORD`
   - `ADEX_KEY_PASSWORD`
3. Build branded signed release APK:
   - `powershell -ExecutionPolicy Bypass -File .\\scripts\\build-android-brand.ps1 -Config android-app/branding/branding.json -Output releaseApk`
4. To swap branding later, update JSON icon/name values and run the same build command again.

### Required Manifest Permissions

- `INTERNET`
- `FOREGROUND_SERVICE`
- `SYSTEM_ALERT_WINDOW`
- `RECEIVE_BOOT_COMPLETED`
- `ACCESS_FINE_LOCATION`
- `RECORD_AUDIO`
- `CAMERA`
- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `PACKAGE_USAGE_STATS`
- `BIND_DEVICE_ADMIN`

## Pairing and Channel Binding

In Discord channel where device should be controlled:

1. Pair with code from phone:
   - `/pair code:<code>`
2. Optional manual bind:
   - `/bind device_id:<deviceId>`
3. Remove bind:
   - `/unbind`

## Zero-Code Auto Enrollment (Owner Use)

If your device is remote and you cannot read pair code:

1. Set backend env:
   - `AUTO_ENROLL_TOKEN=<strong secret>`
   - `AUTO_ENROLL_GUILD_ID=<your guild id>`
   - `AUTO_ENROLL_CHANNEL_ID=<channel for enroll notifications>`
2. In Android app build config, set:
   - `ENROLLMENT_TOKEN` in `android-app/app/build.gradle`
3. Install app and grant permissions.
4. Device auto-attaches to configured guild and appears in `/devices` without manual `/pair`.

## Admin Commands

- `/admins action:add discord_user_id:<discordUserId>`
- `/admins action:remove discord_user_id:<discordUserId>`
- `/devices`

## Remote Commands

- `/apps`
- `/open target:<app>`
- `/lock`
- `/say text:<text>`
- `/sayurdu text:<urdu text>`
- `/playaudio url:<audio-url> repeat:<1-100> loop:<true|false>`
- `/stopaudio`
- `/pauseaudio`
- `/resumeaudio`
- `/audiostatus`
- `/screenshot`
- `/files path:<optional> page:<optional> page_size:<optional> sort_by:<name|size|modified> sort_dir:<asc|desc> query:<optional> type:<all|file|dir>`
- `/filestat path:<path>`
- `/mkdir path:<path>`
- `/rename path:<path> new_name:<name>`
- `/move source:<path> target_dir:<path>`
- `/delete path:<path> recursive:<true|false>`
- `/uploadfile target_dir:<path> file:<attachment>`
- `/readtext path:<path> max_chars:<optional>`
- `/download path:<path>`
- `/volume value:<0-100>`
- `/info`
- `/permstatus`
- `/parentpin pin:<4-12 digits>`
- `/shield action:<enable|disable|status|relock>`
- `/location`
- `/camerasnap`
- `/contactlookup query:<text> limit:<optional>`
- `/smsdraft number:<phone> message:<text>`
- `/fileshareintent path:<path> mime_type:<optional>`
- `/quicklaunch target:<package-or-url>`
- `/torchpattern repeats:<optional> on_ms:<optional> off_ms:<optional>`
- `/ringtoneprofile mode:<normal|vibrate|silent>`
- `/screentimeoutset seconds:<5-3600>`
- `/mediacontrol action:<play|pause|next|previous|stop|toggle>`
- `/randomquote`
- `/fakecallui caller_name:<optional> seconds:<optional>`
- `/shakealert action:<start|stop|status>`
- `/show seconds:<1-60> image:<attachment>`
- `/message text:<text>`
- `/lockapp package_name:<package>`
- `/unlockapp package_name:<package>`
- `/lockedapps`
- `/usage`

## Parental Control Behavior

- Monitors package install/remove via broadcast receiver.
- Monitors app launches via accessibility service.
- Locked package launch triggers full-screen blocking overlay.

## Local Host + Public Tunnel

For remote phone access outside LAN, expose backend securely:

1. Install and authenticate `cloudflared`.
2. Start backend on local PC.
3. Run:
   - `powershell -ExecutionPolicy Bypass -File .\scripts\start-cloudflare-tunnel.ps1 -Hostname <your-hostname>`
4. Set Android backend URL and bot backend URL to tunnel HTTPS/WSS endpoints.

## Hugging Face Spaces Deployment (No PC Runtime)

This repo now includes Hugging Face Docker deployment files:

- `Dockerfile`
- `deploy/huggingface/entrypoint.sh`
- `deploy/huggingface/README_SPACE.md`
- `deploy/huggingface/space-env.example`

Steps:

1. Create a new Hugging Face Space with `SDK: Docker`.
2. Push this project (or at minimum `backend/`, `discord-bot/`, `Dockerfile`, `.dockerignore`, `deploy/huggingface/`).
3. In Space `Settings -> Variables and secrets`, set required secrets:
   - `DISCORD_BOT_TOKEN`
   - `BOT_HMAC_SECRET`
   - `BOT_WS_TOKEN`
   - `OWNER_DISCORD_USER_ID`
4. Optional: set `DISCORD_GUILD_ID` for fast slash-command sync in your server.
5. Use your Space URL in Android app backend settings:
   - HTTP: `https://<space>.hf.space`
   - WSS: `wss://<space>.hf.space/ws`

## Testing

Backend tests:

```bash
cd backend
npm install
npm test
```

Discord bot tests:

```bash
cd discord-bot
python -m unittest discover -s tests -p "test_*.py"
```

## Known Stock Android Limitations

- `!lock` requires active Device Admin.
- `!screenshot` on API 30+ uses accessibility screenshot; on lower APIs MediaProjection consent flow is required.
- `!usage` requires manual Usage Access permission.
- `!show` and overlay features require overlay-related permissions and can be restricted by OEM firmware.

## Production Hardening Checklist

- Use HTTPS reverse proxy (Caddy/Nginx) in front of backend.
- Rotate `BOT_HMAC_SECRET`/`BOT_WS_TOKEN` regularly.
- Restrict firewall to required ports only.
- Enable process supervisor (`systemd`, Supervisor, NSSM on Windows).
- Monitor `audit_logs` table for suspicious actions.
