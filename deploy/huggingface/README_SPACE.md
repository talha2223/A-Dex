---
title: A-Dex Remote Control Backend
sdk: docker
app_port: 7860
pinned: false
---

# A-Dex on Hugging Face Spaces

This Space runs:
- A-Dex backend API + WebSocket server
- Python Discord bot

## Required Space Secrets

Set these in Space `Settings -> Variables and secrets`:

- `DISCORD_BOT_TOKEN`
- `BOT_HMAC_SECRET`
- `BOT_WS_TOKEN`
- `OWNER_DISCORD_USER_ID`

Optional:
- `DISCORD_GUILD_ID` (faster slash-command sync in one server)
- `PAIR_CODE_TTL_SECONDS`
- `COMMAND_TIMEOUT_SECONDS`
- `MEDIA_RETENTION_HOURS`
- `MAX_UPLOAD_BYTES`

## Runtime Notes

- Backend listens on port `7860` (Hugging Face default).
- API health endpoint: `/api/v1/health`
- SQLite data path defaults to `/data/adex.db`.
- Media files default to `/data/media`.

## Android App Connection

Set Android backend URLs to your Space URL:

- HTTP: `https://<space-subdomain>.hf.space`
- WebSocket: `wss://<space-subdomain>.hf.space/ws`
