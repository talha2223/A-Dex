import asyncio
import io
import json
import sys
from pathlib import Path
from typing import Any

import discord
from discord import app_commands

try:
    from .backend_client import BackendApiError, BackendClient
    from .config import Config, load_config
except ImportError:
    # Supports direct script execution (e.g. Pterodactyl PY_FILE path mode).
    repo_bot_parent = Path(__file__).resolve().parent.parent
    if str(repo_bot_parent) not in sys.path:
        sys.path.insert(0, str(repo_bot_parent))
    from bot.backend_client import BackendApiError, BackendClient
    from bot.config import Config, load_config



def format_error(exc: Exception) -> str:
    if isinstance(exc, BackendApiError):
        return str(exc)
    return str(exc) or "Unknown error"



def format_result_message(result: dict[str, Any]) -> str:
    command_name = result.get("commandName", "unknown")
    device_id = result.get("deviceId", "unknown")

    if result.get("status") == "success":
        data = result.get("data") or {}
        if command_name == "files":
            files = data.get("files") or []
            if isinstance(files, list):
                header = (
                    f"Path: `{data.get('path', '-')}` | "
                    f"Page: `{data.get('page', 1)}/{data.get('totalPages', 1)}` | "
                    f"Items: `{len(files)}` of `{data.get('totalItems', len(files))}`"
                )
                lines = []
                for item in files[:25]:
                    if not isinstance(item, dict):
                        continue
                    mark = "DIR" if item.get("isDirectory") else "FILE"
                    name = item.get("name", "unknown")
                    size = item.get("size", 0)
                    modified = item.get("modifiedAt", 0)
                    lines.append(f"- `{mark}` {name} | {size} bytes | mtime: {modified}")
                body = "\n".join(lines) if lines else "- (empty)"
                return f"Command `files` completed on device `{device_id}`.\n{header}\n{body}"

        if command_name == "filestat":
            stat = data.get("stat")
            if isinstance(stat, dict):
                return (
                    f"Command `filestat` completed on device `{device_id}`.\n"
                    f"Path: `{stat.get('path')}`\n"
                    f"Type: `{'dir' if stat.get('isDirectory') else 'file'}` | "
                    f"Size: `{stat.get('size', 0)}` | Modified: `{stat.get('modifiedAt', 0)}`"
                )

        data_text = ""
        if result.get("data") is not None:
            serialized = json.dumps(result.get("data"), ensure_ascii=False)
            data_text = f"\nData: `{serialized[:500]}`"
        return f"Command `{command_name}` completed on device `{device_id}`.{data_text}"

    error_code = result.get("errorCode") or "UNKNOWN"
    error_message = result.get("errorMessage") or ""
    return f"Command `{command_name}` failed on device `{device_id}`: {error_code} {error_message}".strip()



def split_lines_for_discord(lines: list[str], max_len: int = 1800) -> list[str]:
    """Split long multi-line output into safe Discord message chunks."""
    if not lines:
        return [""]

    chunks: list[str] = []
    current = ""
    for line in lines:
        candidate = f"{current}\n{line}" if current else line
        if len(candidate) > max_len:
            if current:
                chunks.append(current)
            current = line
        else:
            current = candidate

    if current:
        chunks.append(current)
    return chunks


class ADexDiscordClient(discord.Client):
    def __init__(self, config: Config, backend: BackendClient) -> None:
        intents = discord.Intents.default()
        intents.guilds = True
        intents.messages = True
        intents.message_content = False
        super().__init__(intents=intents)

        self.config = config
        self.backend = backend
        self.tree = app_commands.CommandTree(self)
        self._backend_event_task: asyncio.Task[None] | None = None
        self._commands_registered = False

    async def setup_hook(self) -> None:
        await self.backend.start()
        self._register_slash_commands()
        if self.config.discord_guild_id:
            guild = discord.Object(id=self.config.discord_guild_id)
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)
            print(f"Synced slash commands to guild {self.config.discord_guild_id}")
        else:
            await self.tree.sync()
            print("Synced global slash commands")
        self._backend_event_task = asyncio.create_task(self._backend_event_loop())

    async def close(self) -> None:
        if self._backend_event_task:
            self._backend_event_task.cancel()
            try:
                await self._backend_event_task
            except asyncio.CancelledError:
                pass

        await self.backend.stop()
        await super().close()

    async def on_ready(self) -> None:
        if self.user:
            print(f"A-Dex bot logged in as {self.user}")

    def _register_slash_commands(self) -> None:
        if self._commands_registered:
            return
        self._commands_registered = True

        @self.tree.command(name="apps", description="Return installed apps list")
        async def apps(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "apps", {})

        @self.tree.command(name="open", description="Open installed app by package or display name")
        @app_commands.describe(target="App package name or display name")
        async def open_app(interaction: discord.Interaction, target: str) -> None:
            await self._queue_remote_command(interaction, "open", {"target": target})

        @self.tree.command(name="lock", description="Lock the phone immediately")
        async def lock(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "lock", {})

        @self.tree.command(name="say", description="Speak text using phone TTS")
        @app_commands.describe(text="Text to speak")
        async def say(interaction: discord.Interaction, text: str) -> None:
            await self._queue_remote_command(interaction, "say", {"text": text})

        @self.tree.command(name="sayurdu", description="Speak Urdu text using Urdu TTS voice")
        @app_commands.describe(text="Urdu text to speak")
        async def sayurdu(interaction: discord.Interaction, text: str) -> None:
            await self._queue_remote_command(interaction, "sayurdu", {"text": text})

        @self.tree.command(name="playaudio", description="Play audio from URL with repeat")
        @app_commands.describe(url="Direct audio file URL", repeat="How many times to play (1-100)", loop="Loop forever until stopped")
        async def playaudio(
            interaction: discord.Interaction,
            url: str,
            repeat: app_commands.Range[int, 1, 100] = 1,
            loop: bool = False,
        ) -> None:
            await self._queue_remote_command(
                interaction,
                "playaudio",
                {"url": url, "repeat": int(repeat), "loop": bool(loop)},
            )

        @self.tree.command(name="stopaudio", description="Stop active audio playback")
        async def stopaudio(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "stopaudio", {})

        @self.tree.command(name="pauseaudio", description="Pause active audio playback")
        async def pauseaudio(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "pauseaudio", {})

        @self.tree.command(name="resumeaudio", description="Resume paused audio playback")
        async def resumeaudio(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "resumeaudio", {})

        @self.tree.command(name="audiostatus", description="Get current audio playback status")
        async def audiostatus(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "audiostatus", {})

        @self.tree.command(name="parentpin", description="Set or rotate parent PIN for uninstall shield")
        @app_commands.describe(pin="4-12 digit parent PIN")
        async def parentpin(interaction: discord.Interaction, pin: str) -> None:
            await self._queue_remote_command(interaction, "parentpin", {"pin": pin})

        @self.tree.command(name="shield", description="Manage uninstall shield")
        @app_commands.describe(action="Shield action")
        @app_commands.choices(action=[
            app_commands.Choice(name="status", value="status"),
            app_commands.Choice(name="enable", value="enable"),
            app_commands.Choice(name="disable", value="disable"),
            app_commands.Choice(name="relock", value="relock"),
        ])
        async def shield(interaction: discord.Interaction, action: app_commands.Choice[str]) -> None:
            await self._queue_remote_command(interaction, "shield", {"action": action.value})

        @self.tree.command(name="screenshot", description="Capture screenshot and send image")
        async def screenshot(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "screenshot", {})

        @self.tree.command(name="files", description="Browse files with pagination/search/sort")
        @app_commands.describe(
            path="Directory path (optional)",
            page="Page number",
            page_size="Items per page",
            sort_by="Sort by field",
            sort_dir="Sort order",
            query="Search text",
            type="Filter type",
        )
        @app_commands.choices(
            sort_by=[
                app_commands.Choice(name="name", value="name"),
                app_commands.Choice(name="size", value="size"),
                app_commands.Choice(name="modified", value="modified"),
            ],
            sort_dir=[
                app_commands.Choice(name="asc", value="asc"),
                app_commands.Choice(name="desc", value="desc"),
            ],
            type=[
                app_commands.Choice(name="all", value="all"),
                app_commands.Choice(name="file", value="file"),
                app_commands.Choice(name="dir", value="dir"),
            ],
        )
        async def files(
            interaction: discord.Interaction,
            path: str | None = None,
            page: app_commands.Range[int, 1, 500] = 1,
            page_size: app_commands.Range[int, 1, 200] = 50,
            sort_by: app_commands.Choice[str] | None = None,
            sort_dir: app_commands.Choice[str] | None = None,
            query: str | None = None,
            type: app_commands.Choice[str] | None = None,
        ) -> None:
            payload: dict[str, Any] = {
                "page": int(page),
                "pageSize": int(page_size),
            }
            if path:
                payload["path"] = path
            if sort_by:
                payload["sortBy"] = sort_by.value
            if sort_dir:
                payload["sortDir"] = sort_dir.value
            if query:
                payload["query"] = query
            if type:
                payload["type"] = type.value
            await self._queue_remote_command(interaction, "files", payload)

        @self.tree.command(name="filestat", description="Read metadata of file/folder")
        @app_commands.describe(path="Absolute path")
        async def filestat(interaction: discord.Interaction, path: str) -> None:
            await self._queue_remote_command(interaction, "filestat", {"path": path})

        @self.tree.command(name="mkdir", description="Create directory")
        @app_commands.describe(path="Directory path to create")
        async def mkdir(interaction: discord.Interaction, path: str) -> None:
            await self._queue_remote_command(interaction, "mkdir", {"path": path})

        @self.tree.command(name="rename", description="Rename file/folder")
        @app_commands.describe(path="Existing path", new_name="New name")
        async def rename(interaction: discord.Interaction, path: str, new_name: str) -> None:
            await self._queue_remote_command(interaction, "rename", {"path": path, "newName": new_name})

        @self.tree.command(name="move", description="Move file/folder to target directory")
        @app_commands.describe(source="Source path", target_dir="Target directory path")
        async def move(interaction: discord.Interaction, source: str, target_dir: str) -> None:
            await self._queue_remote_command(interaction, "move", {"source": source, "targetDir": target_dir})

        @self.tree.command(name="delete", description="Delete file/folder")
        @app_commands.describe(path="Target path", recursive="Delete directories recursively")
        async def delete(interaction: discord.Interaction, path: str, recursive: bool = False) -> None:
            await self._queue_remote_command(interaction, "delete", {"path": path, "recursive": recursive})

        @self.tree.command(name="uploadfile", description="Download attachment URL into device folder")
        @app_commands.describe(target_dir="Target directory path", file="Attachment to transfer")
        async def uploadfile(interaction: discord.Interaction, target_dir: str, file: discord.Attachment) -> None:
            await self._queue_remote_command(
                interaction,
                "uploadfile",
                {
                    "targetDir": target_dir,
                    "fileUrl": file.url,
                    "fileName": file.filename,
                },
            )

        @self.tree.command(name="readtext", description="Read text preview from file")
        @app_commands.describe(path="File path", max_chars="Max chars to return")
        async def readtext(
            interaction: discord.Interaction,
            path: str,
            max_chars: app_commands.Range[int, 64, 50000] = 2000,
        ) -> None:
            await self._queue_remote_command(interaction, "readtext", {"path": path, "maxChars": int(max_chars)})

        @self.tree.command(name="download", description="Download file from device path")
        @app_commands.describe(path="Absolute file path on device")
        async def download(interaction: discord.Interaction, path: str) -> None:
            await self._queue_remote_command(interaction, "download", {"path": path})

        @self.tree.command(name="volume", description="Set volume 0-100")
        @app_commands.describe(value="Volume percentage")
        async def volume(interaction: discord.Interaction, value: app_commands.Range[int, 0, 100]) -> None:
            await self._queue_remote_command(interaction, "volume", {"value": int(value)})

        @self.tree.command(name="info", description="Get device info")
        async def info(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "info", {})

        @self.tree.command(name="permstatus", description="Get required permission and service status")
        async def permstatus(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "permstatus", {})

        @self.tree.command(name="location", description="Get device GPS location")
        async def location(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "location", {})

        @self.tree.command(name="camerasnap", description="Launch camera capture intent on device")
        async def camerasnap(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "camerasnap", {})

        @self.tree.command(name="contactlookup", description="Lookup contacts by query")
        @app_commands.describe(query="Name or number fragment", limit="Max results")
        async def contactlookup(
            interaction: discord.Interaction,
            query: str,
            limit: app_commands.Range[int, 1, 100] = 20,
        ) -> None:
            await self._queue_remote_command(interaction, "contactlookup", {"query": query, "limit": int(limit)})

        @self.tree.command(name="smsdraft", description="Open SMS draft on device")
        @app_commands.describe(number="Phone number", message="Draft message body")
        async def smsdraft(interaction: discord.Interaction, number: str, message: str) -> None:
            await self._queue_remote_command(interaction, "smsdraft", {"number": number, "message": message})

        @self.tree.command(name="fileshareintent", description="Open Android share sheet for a file path")
        @app_commands.describe(path="File path", mime_type="Optional MIME type override")
        async def fileshareintent(interaction: discord.Interaction, path: str, mime_type: str | None = None) -> None:
            payload: dict[str, Any] = {"path": path}
            if mime_type:
                payload["mimeType"] = mime_type
            await self._queue_remote_command(interaction, "fileshareintent", payload)

        @self.tree.command(name="quicklaunch", description="Quick launch package or URL")
        @app_commands.describe(target="Package name or URL")
        async def quicklaunch(interaction: discord.Interaction, target: str) -> None:
            payload = {"url": target} if target.startswith(("http://", "https://")) else {"packageName": target}
            await self._queue_remote_command(interaction, "quicklaunch", payload)

        @self.tree.command(name="torchpattern", description="Blink torch in a pattern")
        @app_commands.describe(repeats="Number of blinks", on_ms="On duration (ms)", off_ms="Off duration (ms)")
        async def torchpattern(
            interaction: discord.Interaction,
            repeats: app_commands.Range[int, 1, 30] = 3,
            on_ms: app_commands.Range[int, 50, 2000] = 250,
            off_ms: app_commands.Range[int, 50, 2000] = 250,
        ) -> None:
            await self._queue_remote_command(
                interaction,
                "torchpattern",
                {"repeats": int(repeats), "onMs": int(on_ms), "offMs": int(off_ms)},
            )

        @self.tree.command(name="ringtoneprofile", description="Set ringtone profile")
        @app_commands.describe(mode="Ringer profile")
        @app_commands.choices(mode=[
            app_commands.Choice(name="normal", value="normal"),
            app_commands.Choice(name="vibrate", value="vibrate"),
            app_commands.Choice(name="silent", value="silent"),
        ])
        async def ringtoneprofile(interaction: discord.Interaction, mode: app_commands.Choice[str]) -> None:
            await self._queue_remote_command(interaction, "ringtoneprofile", {"mode": mode.value})

        @self.tree.command(name="screentimeoutset", description="Set screen timeout in seconds")
        @app_commands.describe(seconds="Timeout value in seconds")
        async def screentimeoutset(
            interaction: discord.Interaction,
            seconds: app_commands.Range[int, 5, 3600],
        ) -> None:
            await self._queue_remote_command(interaction, "screentimeoutset", {"seconds": int(seconds)})

        @self.tree.command(name="mediacontrol", description="Send media playback action")
        @app_commands.describe(action="Playback action")
        @app_commands.choices(action=[
            app_commands.Choice(name="play", value="play"),
            app_commands.Choice(name="pause", value="pause"),
            app_commands.Choice(name="next", value="next"),
            app_commands.Choice(name="previous", value="previous"),
            app_commands.Choice(name="stop", value="stop"),
            app_commands.Choice(name="toggle", value="toggle"),
        ])
        async def mediacontrol(interaction: discord.Interaction, action: app_commands.Choice[str]) -> None:
            await self._queue_remote_command(interaction, "mediacontrol", {"action": action.value})

        @self.tree.command(name="randomquote", description="Get a random quote from device")
        async def randomquote(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "randomquote", {})

        @self.tree.command(name="fakecallui", description="Show fake incoming call UI")
        @app_commands.describe(caller_name="Caller name", seconds="Auto-dismiss seconds")
        async def fakecallui(
            interaction: discord.Interaction,
            caller_name: str = "Unknown Caller",
            seconds: app_commands.Range[int, 5, 120] = 20,
        ) -> None:
            await self._queue_remote_command(
                interaction,
                "fakecallui",
                {"callerName": caller_name, "seconds": int(seconds)},
            )

        @self.tree.command(name="shakealert", description="Control shake detector module")
        @app_commands.describe(action="Action")
        @app_commands.choices(action=[
            app_commands.Choice(name="status", value="status"),
            app_commands.Choice(name="start", value="start"),
            app_commands.Choice(name="stop", value="stop"),
        ])
        async def shakealert(interaction: discord.Interaction, action: app_commands.Choice[str]) -> None:
            await self._queue_remote_command(interaction, "shakealert", {"action": action.value})

        @self.tree.command(name="show", description="Display an image full-screen on phone")
        @app_commands.describe(seconds="Display duration in seconds", image="Image attachment")
        async def show(
            interaction: discord.Interaction,
            seconds: app_commands.Range[int, 1, 60],
            image: discord.Attachment,
        ) -> None:
            content_type = (image.content_type or "").lower()
            if not content_type.startswith("image/"):
                await interaction.response.send_message("Attachment must be an image.", ephemeral=True)
                return

            if image.size and image.size > self.config.show_image_max_bytes:
                max_mb = self.config.show_image_max_bytes // (1024 * 1024)
                await interaction.response.send_message(f"Attachment too large; max {max_mb} MB.", ephemeral=True)
                return

            await self._queue_remote_command(
                interaction,
                "show",
                {
                    "seconds": int(seconds),
                    "imageUrl": image.url,
                    "imageName": image.filename,
                    "imageContentType": content_type or "image/*",
                },
            )

        @self.tree.command(name="message", description="Display full-screen message on phone")
        @app_commands.describe(text="Message text")
        async def message(interaction: discord.Interaction, text: str) -> None:
            await self._queue_remote_command(interaction, "message", {"text": text})

        @self.tree.command(name="lockapp", description="Block an app package (parental control)")
        @app_commands.describe(package_name="Android package name")
        async def lockapp(interaction: discord.Interaction, package_name: str) -> None:
            await self._queue_remote_command(interaction, "lockapp", {"packageName": package_name})

        @self.tree.command(name="unlockapp", description="Unblock an app package")
        @app_commands.describe(package_name="Android package name")
        async def unlockapp(interaction: discord.Interaction, package_name: str) -> None:
            await self._queue_remote_command(interaction, "unlockapp", {"packageName": package_name})

        @self.tree.command(name="lockedapps", description="List all blocked app packages")
        async def lockedapps(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "lockedapps", {})

        @self.tree.command(name="usage", description="Get app usage statistics")
        async def usage(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "usage", {})

        @self.tree.command(name="pair", description="Pair channel with one-time device code")
        @app_commands.describe(code="One-time pairing code shown in app")
        async def pair(interaction: discord.Interaction, code: str) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)

            try:
                data = await self.backend.post(
                    "/api/v1/pairing/claim",
                    {
                        "code": code,
                        "guildId": str(interaction.guild_id),
                        "channelId": str(interaction.channel_id),
                        "discordUserId": str(interaction.user.id),
                    },
                )
                await interaction.followup.send(f"Paired device `{data.get('deviceId')}` to this channel.")
            except Exception as exc:
                await interaction.followup.send(f"Command failed: {format_error(exc)}")

        @self.tree.command(name="bind", description="Bind this channel to a specific device")
        @app_commands.describe(device_id="Device ID")
        async def bind(interaction: discord.Interaction, device_id: str) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)

            try:
                await self.backend.post(
                    "/api/v1/channel-bindings",
                    {
                        "guildId": str(interaction.guild_id),
                        "channelId": str(interaction.channel_id),
                        "deviceId": device_id,
                        "actorUserId": str(interaction.user.id),
                    },
                )
                await interaction.followup.send(f"Bound this channel to device `{device_id}`.")
            except Exception as exc:
                await interaction.followup.send(f"Command failed: {format_error(exc)}")

        @self.tree.command(name="unbind", description="Remove device binding from this channel")
        async def unbind(interaction: discord.Interaction) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)

            try:
                await self.backend.delete(
                    f"/api/v1/channel-bindings/{interaction.channel_id}",
                    {
                        "guildId": str(interaction.guild_id),
                        "actorUserId": str(interaction.user.id),
                    },
                )
                await interaction.followup.send("Channel binding removed.")
            except Exception as exc:
                await interaction.followup.send(f"Command failed: {format_error(exc)}")

        @self.tree.command(name="admins", description="Add or remove guild admin")
        @app_commands.describe(action="add or remove", discord_user_id="Discord user ID")
        @app_commands.choices(action=[
            app_commands.Choice(name="add", value="add"),
            app_commands.Choice(name="remove", value="remove"),
        ])
        async def admins(
            interaction: discord.Interaction,
            action: app_commands.Choice[str],
            discord_user_id: str,
        ) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)

            try:
                if action.value == "add":
                    await self.backend.post(
                        "/api/v1/admins",
                        {
                            "guildId": str(interaction.guild_id),
                            "actorUserId": str(interaction.user.id),
                            "targetUserId": discord_user_id,
                        },
                    )
                else:
                    await self.backend.delete(
                        f"/api/v1/admins/{discord_user_id}",
                        {
                            "guildId": str(interaction.guild_id),
                            "actorUserId": str(interaction.user.id),
                        },
                    )

                await interaction.followup.send(f"Admin {action.value} completed for user `{discord_user_id}`.")
            except Exception as exc:
                await interaction.followup.send(f"Command failed: {format_error(exc)}")

        @self.tree.command(name="devices", description="List paired devices for this guild")
        async def devices(interaction: discord.Interaction) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)

            try:
                data = await self.backend.get(
                    "/api/v1/devices",
                    {
                        "guildId": str(interaction.guild_id),
                        "discordUserId": str(interaction.user.id),
                    },
                )

                devices_data = data.get("devices") or []
                if not devices_data:
                    await interaction.followup.send("No paired devices found for this guild.")
                    return

                lines = [
                    f"- {d.get('id')} | {d.get('status')} | channel: {d.get('channelId') or 'unbound'} | model: {d.get('model') or 'unknown'}"
                    for d in devices_data
                ]

                chunks = split_lines_for_discord(lines)
                await interaction.followup.send("Devices:\n" + chunks[0])
                for chunk in chunks[1:]:
                    await interaction.followup.send(chunk)
            except Exception as exc:
                await interaction.followup.send(f"Command failed: {format_error(exc)}")

    async def _validate_guild_context(self, interaction: discord.Interaction) -> bool:
        if interaction.guild_id is not None and interaction.channel_id is not None:
            return True

        if interaction.response.is_done():
            await interaction.followup.send("This command can only be used in a guild text channel.", ephemeral=True)
        else:
            await interaction.response.send_message("This command can only be used in a guild text channel.", ephemeral=True)
        return False

    async def _queue_remote_command(self, interaction: discord.Interaction, command_name: str, payload: dict[str, Any]) -> None:
        if not await self._validate_guild_context(interaction):
            return

        await interaction.response.defer(thinking=True)

        try:
            response = await self.backend.post(
                "/api/v1/commands",
                {
                    "guildId": str(interaction.guild_id),
                    "channelId": str(interaction.channel_id),
                    "discordUserId": str(interaction.user.id),
                    "commandName": command_name,
                    "payload": payload,
                },
            )
            await interaction.followup.send(
                f"Command queued: `{command_name}` (id: `{response.get('commandId')}`, status: `{response.get('status')}`)."
            )
        except Exception as exc:
            await interaction.followup.send(f"Command failed: {format_error(exc)}")

    async def _backend_event_loop(self) -> None:
        while True:
            payload = await self.backend.events.get()
            event_type = payload.get("type")

            if event_type == "bot.command_result":
                await self._publish_command_result(payload)
            elif event_type == "bot.device_status":
                print("Device status:", payload.get("deviceId"), payload.get("status"))
            elif event_type == "bot.device_event":
                await self._publish_device_event(payload)
                print("Device event:", payload.get("deviceId"), payload.get("eventType"))

    async def _publish_command_result(self, payload: dict[str, Any]) -> None:
        channel_id_raw = payload.get("channelId")
        if not channel_id_raw:
            return

        try:
            channel_id = int(channel_id_raw)
        except (TypeError, ValueError):
            return

        channel = self.get_channel(channel_id)
        if channel is None:
            try:
                channel = await self.fetch_channel(channel_id)
            except Exception as exc:
                print("Failed to fetch channel:", exc)
                return

        if not isinstance(channel, discord.abc.Messageable):
            return

        text = format_result_message(payload)
        media_id = payload.get("mediaId")

        if media_id:
            try:
                content_type, media_bytes = await self.backend.get_media(media_id)
                ext = "bin"
                if "/" in content_type:
                    ext = content_type.split("/")[1].split(";")[0] or "bin"
                filename = f"{payload.get('commandName', 'command')}-{payload.get('commandId', 'result')}.{ext}"
                file = discord.File(io.BytesIO(media_bytes), filename=filename)
                await channel.send(content=text, file=file)
            except Exception as exc:
                print("Failed to publish media result:", exc)
                await channel.send(content=text)
        else:
            await channel.send(content=text)

    async def _publish_device_event(self, payload: dict[str, Any]) -> None:
        if payload.get("eventType") != "auto_enrolled":
            return

        channel_id_raw = payload.get("channelId")
        if not channel_id_raw:
            return

        try:
            channel_id = int(channel_id_raw)
        except (TypeError, ValueError):
            return

        channel = self.get_channel(channel_id)
        if channel is None:
            try:
                channel = await self.fetch_channel(channel_id)
            except Exception as exc:
                print("Failed to fetch event channel:", exc)
                return

        if not isinstance(channel, discord.abc.Messageable):
            return

        data = payload.get("data") or {}
        device_id = payload.get("deviceId", "unknown")
        model = data.get("model") or "unknown"
        android_version = data.get("androidVersion") or "unknown"
        app_version = data.get("appVersion") or "unknown"
        bound_text = "yes" if data.get("boundToChannel") else "no"

        await channel.send(
            f"Auto-enrolled device `{device_id}`\n"
            f"Model: `{model}` | Android: `{android_version}` | App: `{app_version}` | Bound: `{bound_text}`"
        )


async def _run() -> None:
    config = load_config()
    if not config.discord_bot_token:
        raise RuntimeError("DISCORD_BOT_TOKEN is missing. Set environment variables before startup.")

    backend = BackendClient(config)
    client = ADexDiscordClient(config, backend)

    try:
        await client.start(config.discord_bot_token)
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(_run())
