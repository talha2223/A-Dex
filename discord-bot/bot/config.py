import os
from dataclasses import dataclass

from dotenv import load_dotenv

# Load environment variables from discord-bot/.env when present.
load_dotenv()


@dataclass(frozen=True)
class Config:
    discord_bot_token: str
    discord_guild_id: int | None
    backend_base_url: str
    backend_ws_url: str
    bot_hmac_secret: str
    bot_ws_token: str
    command_prefix: str
    show_image_max_bytes: int



def load_config() -> Config:
    return Config(
        discord_bot_token=os.getenv("DISCORD_BOT_TOKEN", ""),
        discord_guild_id=int(os.getenv("DISCORD_GUILD_ID")) if os.getenv("DISCORD_GUILD_ID") else None,
        backend_base_url=os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8080"),
        backend_ws_url=os.getenv("BACKEND_WS_URL", "ws://127.0.0.1:8080/ws"),
        bot_hmac_secret=os.getenv("BOT_HMAC_SECRET", "dev-secret-change-me"),
        bot_ws_token=os.getenv("BOT_WS_TOKEN", "dev-ws-token-change-me"),
        command_prefix=os.getenv("COMMAND_PREFIX", "!"),
        show_image_max_bytes=int(os.getenv("SHOW_IMAGE_MAX_BYTES", "8000000")),
    )
