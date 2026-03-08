import asyncio
import io
import json
import sys
import uuid
from dataclasses import dataclass
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

DEVICE_COMMAND_NAMES = {
    "apps",
    "open",
    "lock",
    "say",
    "sayurdu",
    "playaudio",
    "stopaudio",
    "pauseaudio",
    "resumeaudio",
    "audiostatus",
    "parentpin",
    "shield",
    "screenshot",
    "files",
    "filestat",
    "mkdir",
    "rename",
    "move",
    "delete",
    "uploadfile",
    "readtext",
    "download",
    "volume",
    "info",
    "permstatus",
    "location",
    "camerasnap",
    "contactlookup",
    "smsdraft",
    "fileshareintent",
    "quicklaunch",
    "torchpattern",
    "ringtoneprofile",
    "screentimeoutset",
    "mediacontrol",
    "randomquote",
    "fakecallui",
    "shakealert",
    "vibratepattern",
    "beep",
    "countdownoverlay",
    "flashtext",
    "coinflip",
    "diceroll",
    "randomnumber",
    "quicktimer",
    "soundfx",
    "prankscreen",
    "show",
    "message",
    "lockapp",
    "unlockapp",
    "lockedapps",
    "usage",
}


@dataclass
class LockAppPickerSession:
    apps: list[dict[str, str]]
    locked_packages: set[str]
    query: str = ""
    page: int = 0
    page_size: int = 5

    def filtered_apps(self) -> list[dict[str, str]]:
        if not self.query:
            return self.apps
        q = self.query.lower()
        return [app for app in self.apps if q in app["label"].lower() or q in app["packageName"].lower()]

    def page_count(self) -> int:
        total = len(self.filtered_apps())
        if total == 0:
            return 1
        return (total + self.page_size - 1) // self.page_size

    def page_items(self) -> list[dict[str, str]]:
        items = self.filtered_apps()
        max_page = max(0, self.page_count() - 1)
        self.page = max(0, min(self.page, max_page))
        start = self.page * self.page_size
        return items[start : start + self.page_size]

    def toggle_next_action(self, package_name: str) -> str:
        return "unlockapp" if package_name in self.locked_packages else "lockapp"

    def apply_toggle(self, package_name: str) -> None:
        if package_name in self.locked_packages:
            self.locked_packages.remove(package_name)
        else:
            self.locked_packages.add(package_name)


class LockAppSearchModal(discord.ui.Modal, title="Search Apps"):
    query = discord.ui.TextInput(label="Label or package contains", required=False, max_length=80)

    def __init__(self, view: "LockAppPickerView") -> None:
        super().__init__()
        self._view = view

    async def on_submit(self, interaction: discord.Interaction) -> None:
        if not self._view.is_owner(interaction):
            await interaction.response.send_message("Only the command author can use this picker.", ephemeral=True)
            return
        self._view.session.query = str(self.query.value or "").strip()
        self._view.session.page = 0
        self._view.rebuild_buttons()
        if self._view.message:
            await self._view.message.edit(content=self._view.render_text(), view=self._view)
        await interaction.response.send_message("Search applied.", ephemeral=True)


class LockToggleButton(discord.ui.Button["LockAppPickerView"]):
    def __init__(self, package_name: str, label: str, is_locked: bool) -> None:
        action_word = "Unlock" if is_locked else "Lock"
        style = discord.ButtonStyle.danger if is_locked else discord.ButtonStyle.success
        safe_label = f"{action_word}: {label}"[:80]
        super().__init__(style=style, label=safe_label, row=1)
        self.package_name = package_name

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.view is None:
            return
        await self.view.handle_toggle(interaction, self.package_name)


class LockAppPickerView(discord.ui.View):
    def __init__(self, client: "ADexDiscordClient", owner_user_id: int, guild_id: int, channel_id: int, session: LockAppPickerSession) -> None:
        super().__init__(timeout=300)
        self.client = client
        self.owner_user_id = owner_user_id
        self.guild_id = guild_id
        self.channel_id = channel_id
        self.session = session
        self.message: discord.Message | None = None
        self.rebuild_buttons()

    def is_owner(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self.owner_user_id

    def render_text(self) -> str:
        total = len(self.session.filtered_apps())
        query_text = self.session.query or "(none)"
        items = self.session.page_items()
        lines = [
            f"Lock App Picker | Query: `{query_text}`",
            f"Page `{self.session.page + 1}/{self.session.page_count()}` | Total shown: `{total}`",
            "Use buttons below to lock/unlock.",
        ]
        if not items:
            lines.append("- No apps matched.")
        else:
            for item in items:
                package_name = item["packageName"]
                status = "LOCKED" if package_name in self.session.locked_packages else "UNLOCKED"
                lines.append(f"- `{item['label']}` (`{package_name}`) [{status}]")
        return "\n".join(lines)

    def rebuild_buttons(self) -> None:
        self.clear_items()
        self.add_item(LockPageButton("Prev", -1))
        self.add_item(LockPageButton("Next", 1))
        self.add_item(LockSearchButton())
        self.add_item(LockRefreshButton())
        for app in self.session.page_items():
            self.add_item(
                LockToggleButton(
                    package_name=app["packageName"],
                    label=app["label"],
                    is_locked=app["packageName"] in self.session.locked_packages,
                )
            )

    async def handle_toggle(self, interaction: discord.Interaction, package_name: str) -> None:
        if not self.is_owner(interaction):
            await interaction.response.send_message("Only the command author can use this picker.", ephemeral=True)
            return
        await interaction.response.defer()
        try:
            action = self.session.toggle_next_action(package_name)
            result = await self.client._send_device_command_wait(
                guild_id=str(self.guild_id),
                channel_id=str(self.channel_id),
                discord_user_id=str(interaction.user.id),
                command_name=action,
                payload={"packageName": package_name},
                timeout_seconds=45,
                silent=True,
            )
        except Exception:
            result = None
        if result and result.get("status") == "success":
            self.session.apply_toggle(package_name)

        self.rebuild_buttons()
        if self.message:
            await self.message.edit(content=self.render_text(), view=self)

    async def refresh_state(self, interaction: discord.Interaction) -> None:
        if not self.is_owner(interaction):
            await interaction.response.send_message("Only the command author can use this picker.", ephemeral=True)
            return
        await interaction.response.defer()
        try:
            locked_result = await self.client._send_device_command_wait(
                guild_id=str(self.guild_id),
                channel_id=str(self.channel_id),
                discord_user_id=str(interaction.user.id),
                command_name="lockedapps",
                payload={},
                timeout_seconds=45,
                silent=True,
            )
        except Exception:
            locked_result = None
        if locked_result and locked_result.get("status") == "success":
            locked = locked_result.get("data", {}).get("lockedApps") or []
            if isinstance(locked, list):
                self.session.locked_packages = {str(v) for v in locked}

        self.rebuild_buttons()
        if self.message:
            await self.message.edit(content=self.render_text(), view=self)


class LockPageButton(discord.ui.Button["LockAppPickerView"]):
    def __init__(self, label: str, direction: int) -> None:
        super().__init__(style=discord.ButtonStyle.secondary, label=label, row=0)
        self.direction = direction

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.view is None:
            return
        if not self.view.is_owner(interaction):
            await interaction.response.send_message("Only the command author can use this picker.", ephemeral=True)
            return
        self.view.session.page += self.direction
        self.view.rebuild_buttons()
        await interaction.response.edit_message(content=self.view.render_text(), view=self.view)


class LockSearchButton(discord.ui.Button["LockAppPickerView"]):
    def __init__(self) -> None:
        super().__init__(style=discord.ButtonStyle.primary, label="Search", row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.view is None:
            return
        await interaction.response.send_modal(LockAppSearchModal(self.view))


class LockRefreshButton(discord.ui.Button["LockAppPickerView"]):
    def __init__(self) -> None:
        super().__init__(style=discord.ButtonStyle.secondary, label="Refresh", row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.view is None:
            return
        await self.view.refresh_state(interaction)



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
    if command_name == "screenshot" and error_code == "ACCESSIBILITY_SERVICE_NOT_ACTIVE":
        return (
            f"Command `{command_name}` failed on device `{device_id}`: {error_code}.\n"
            "Fix: Open the app -> tap `Open Permission Setup` -> enable A-Dex Accessibility Service -> retry `/screenshot`."
        )
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
        self._supported_device_commands: set[str] = set(DEVICE_COMMAND_NAMES)
        self._backend_version = "unknown"
        self._backend_build_ts = "unknown"
        self._pending_results: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._silent_request_ids: set[str] = set()

    async def setup_hook(self) -> None:
        await self.backend.start()
        await self._load_backend_capabilities()
        self._register_slash_commands()
        self._prune_unsupported_slash_commands()
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

    async def _load_backend_capabilities(self) -> None:
        try:
            caps = await self.backend.get_capabilities()
            commands_raw = caps.get("commands") or []
            commands = {str(v).strip().lower() for v in commands_raw if str(v).strip()}
            if commands:
                self._supported_device_commands = commands
            self._backend_version = str(caps.get("backendVersion") or "unknown")
            self._backend_build_ts = str(caps.get("backendBuildTs") or "unknown")
            unsupported = sorted(DEVICE_COMMAND_NAMES - self._supported_device_commands)
            print(
                f"Loaded backend capabilities: version={self._backend_version}, "
                f"commands={len(self._supported_device_commands)}, unsupported={len(unsupported)}"
            )
            if unsupported:
                print("Unsupported device commands on this backend:", ", ".join(unsupported))
        except Exception as exc:
            # Keep bot usable if backend is older and does not expose /capabilities.
            self._supported_device_commands = set(DEVICE_COMMAND_NAMES)
            self._backend_version = "unknown"
            self._backend_build_ts = "unknown"
            print(f"Capabilities fetch failed; falling back to full command set: {format_error(exc)}")

    def _prune_unsupported_slash_commands(self) -> None:
        unsupported = sorted(DEVICE_COMMAND_NAMES - self._supported_device_commands)
        for name in unsupported:
            self.tree.remove_command(name)
        if not {"apps", "lockapp", "unlockapp", "lockedapps"}.issubset(self._supported_device_commands):
            self.tree.remove_command("lockapp_picker")
        if "permstatus" not in self._supported_device_commands:
            self.tree.remove_command("setupcheck")

    def _is_supported_device_command(self, command_name: str) -> bool:
        return command_name.lower() in self._supported_device_commands

    def _register_slash_commands(self) -> None:
        if self._commands_registered:
            return
        self._commands_registered = True

        @self.tree.command(name="backendstatus", description="Show backend capability/version status")
        async def backendstatus(interaction: discord.Interaction) -> None:
            unsupported = sorted(DEVICE_COMMAND_NAMES - self._supported_device_commands)
            lines = [
                f"Backend version: `{self._backend_version}`",
                f"Build timestamp: `{self._backend_build_ts}`",
                f"Supported command count: `{len(self._supported_device_commands)}`",
            ]
            if unsupported:
                lines.append(f"Unsupported in this backend: `{', '.join(unsupported[:20])}`")
            await interaction.response.send_message("\n".join(lines), ephemeral=True)

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

        @self.tree.command(name="setupcheck", description="Human-readable setup checklist for device commands")
        async def setupcheck(interaction: discord.Interaction) -> None:
            if not await self._validate_guild_context(interaction):
                return
            await interaction.response.defer(thinking=True)
            try:
                result = await self._send_device_command_wait(
                    guild_id=str(interaction.guild_id),
                    channel_id=str(interaction.channel_id),
                    discord_user_id=str(interaction.user.id),
                    command_name="permstatus",
                    payload={},
                    timeout_seconds=45,
                    silent=True,
                )
            except Exception as exc:
                await interaction.followup.send(f"Setup check failed: {format_error(exc)}")
                return
            if not result:
                await interaction.followup.send("Setup check timed out. Try again in a few seconds.")
                return
            if result.get("status") != "success":
                await interaction.followup.send(format_result_message(result))
                return

            data = result.get("data") or {}
            runtime = data.get("runtimePermissions") or {}
            missing = data.get("missingRuntimePermissions") or []
            lines = [
                "Setup checklist:",
                f"- Accessibility service: `{'OK' if data.get('accessibilityServiceEnabled') else 'MISSING'}`",
                f"- Overlay permission: `{'OK' if data.get('overlayPermission') else 'MISSING'}`",
                f"- Usage Access: `{'OK' if data.get('usageAccessPermission') else 'MISSING'}`",
                f"- Device Admin: `{'OK' if data.get('deviceAdminEnabled') else 'MISSING'}`",
                f"- Runtime permissions summary: `{runtime}`",
            ]
            if missing:
                lines.append(f"- Missing runtime permissions: `{missing}`")
            if not data.get("accessibilityServiceEnabled"):
                lines.append("Screenshot fix: open app -> Open Permission Setup -> enable A-Dex Accessibility Service.")
            await interaction.followup.send("\n".join(lines))

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

        @self.tree.command(name="vibratepattern", description="Run vibration pattern")
        @app_commands.describe(pattern_ms="Comma separated milliseconds, e.g. 200,100,200", repeat="Repeat pattern")
        async def vibratepattern(interaction: discord.Interaction, pattern_ms: str, repeat: bool = False) -> None:
            try:
                values = [int(v.strip()) for v in pattern_ms.split(",") if v.strip()]
            except ValueError:
                await interaction.response.send_message("Invalid pattern. Use comma-separated integers.", ephemeral=True)
                return
            if not values:
                await interaction.response.send_message("Pattern must include at least one duration.", ephemeral=True)
                return
            await self._queue_remote_command(interaction, "vibratepattern", {"patternMs": values, "repeat": repeat})

        @self.tree.command(name="beep", description="Play short beeps on device")
        @app_commands.describe(tone="Tone style", count="Number of beeps")
        @app_commands.choices(tone=[
            app_commands.Choice(name="beep", value="beep"),
            app_commands.Choice(name="ack", value="ack"),
            app_commands.Choice(name="alarm", value="alarm"),
        ])
        async def beep(
            interaction: discord.Interaction,
            tone: app_commands.Choice[str],
            count: app_commands.Range[int, 1, 10] = 1,
        ) -> None:
            await self._queue_remote_command(interaction, "beep", {"tone": tone.value, "count": int(count)})

        @self.tree.command(name="countdownoverlay", description="Countdown then display completion message")
        @app_commands.describe(seconds="Countdown seconds", message="Completion message")
        async def countdownoverlay(
            interaction: discord.Interaction,
            seconds: app_commands.Range[int, 1, 3600] = 10,
            message: str = "Break over",
        ) -> None:
            await self._queue_remote_command(
                interaction,
                "countdownoverlay",
                {"seconds": int(seconds), "message": message},
            )

        @self.tree.command(name="flashtext", description="Show full-screen text for a short duration")
        @app_commands.describe(text="Text to display", seconds="Duration")
        async def flashtext(
            interaction: discord.Interaction,
            text: str,
            seconds: app_commands.Range[int, 1, 120] = 8,
        ) -> None:
            await self._queue_remote_command(interaction, "flashtext", {"text": text, "seconds": int(seconds)})

        @self.tree.command(name="coinflip", description="Flip a coin on device")
        async def coinflip(interaction: discord.Interaction) -> None:
            await self._queue_remote_command(interaction, "coinflip", {})

        @self.tree.command(name="diceroll", description="Roll one or more dice")
        @app_commands.describe(sides="Number of sides per die", count="Number of dice")
        async def diceroll(
            interaction: discord.Interaction,
            sides: app_commands.Range[int, 2, 100] = 6,
            count: app_commands.Range[int, 1, 10] = 1,
        ) -> None:
            await self._queue_remote_command(interaction, "diceroll", {"sides": int(sides), "count": int(count)})

        @self.tree.command(name="randomnumber", description="Generate random number in range")
        @app_commands.describe(minimum="Minimum", maximum="Maximum")
        async def randomnumber(
            interaction: discord.Interaction,
            minimum: int = 1,
            maximum: int = 100,
        ) -> None:
            await self._queue_remote_command(interaction, "randomnumber", {"min": int(minimum), "max": int(maximum)})

        @self.tree.command(name="quicktimer", description="Set quick timer and notify on device")
        @app_commands.describe(seconds="Timer duration", label="Timer label")
        async def quicktimer(
            interaction: discord.Interaction,
            seconds: app_commands.Range[int, 1, 3600] = 30,
            label: str = "Timer",
        ) -> None:
            await self._queue_remote_command(interaction, "quicktimer", {"seconds": int(seconds), "label": label})

        @self.tree.command(name="soundfx", description="Play a short sound effect")
        @app_commands.describe(effect="Effect name", duration_ms="Duration in milliseconds")
        @app_commands.choices(effect=[
            app_commands.Choice(name="applause", value="applause"),
            app_commands.Choice(name="alarm", value="alarm"),
            app_commands.Choice(name="beep", value="beep"),
        ])
        async def soundfx(
            interaction: discord.Interaction,
            effect: app_commands.Choice[str],
            duration_ms: app_commands.Range[int, 200, 10000] = 3000,
        ) -> None:
            await self._queue_remote_command(
                interaction,
                "soundfx",
                {"effect": effect.value, "durationMs": int(duration_ms)},
            )

        @self.tree.command(name="prankscreen", description="Show prank overlay on device")
        @app_commands.describe(mode="Prank mode", seconds="Duration")
        @app_commands.choices(mode=[
            app_commands.Choice(name="glitch", value="glitch"),
            app_commands.Choice(name="freeze", value="freeze"),
            app_commands.Choice(name="warning", value="warning"),
        ])
        async def prankscreen(
            interaction: discord.Interaction,
            mode: app_commands.Choice[str],
            seconds: app_commands.Range[int, 1, 60] = 6,
        ) -> None:
            await self._queue_remote_command(interaction, "prankscreen", {"mode": mode.value, "seconds": int(seconds)})

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

        @self.tree.command(name="lockapp", description="Block app directly or open interactive lock picker")
        @app_commands.describe(package_name="Android package name (optional for picker)", query="Initial search for picker")
        async def lockapp(
            interaction: discord.Interaction,
            package_name: str | None = None,
            query: str | None = None,
        ) -> None:
            if package_name:
                await self._queue_remote_command(interaction, "lockapp", {"packageName": package_name})
                return
            await self._open_lockapp_picker(interaction, query)

        @self.tree.command(name="lockapp_picker", description="Open interactive lock/unlock picker")
        @app_commands.describe(query="Initial app search query")
        async def lockapp_picker(interaction: discord.Interaction, query: str | None = None) -> None:
            await self._open_lockapp_picker(interaction, query)

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

    async def _open_lockapp_picker(self, interaction: discord.Interaction, query: str | None = None) -> None:
        if not await self._validate_guild_context(interaction):
            return
        await interaction.response.defer(thinking=True)
        try:
            apps_result = await self._send_device_command_wait(
                guild_id=str(interaction.guild_id),
                channel_id=str(interaction.channel_id),
                discord_user_id=str(interaction.user.id),
                command_name="apps",
                payload={},
                timeout_seconds=45,
                silent=True,
            )
            locked_result = await self._send_device_command_wait(
                guild_id=str(interaction.guild_id),
                channel_id=str(interaction.channel_id),
                discord_user_id=str(interaction.user.id),
                command_name="lockedapps",
                payload={},
                timeout_seconds=45,
                silent=True,
            )
        except Exception as exc:
            await interaction.followup.send(f"Failed to initialize lockapp picker: {format_error(exc)}")
            return

        if not apps_result or apps_result.get("status") != "success":
            await interaction.followup.send("Failed to load app list for picker. Ensure device is online.")
            return
        if not locked_result or locked_result.get("status") != "success":
            await interaction.followup.send("Failed to load locked apps state for picker.")
            return

        apps_data = apps_result.get("data", {}).get("apps") or []
        locked_data = locked_result.get("data", {}).get("lockedApps") or []
        apps: list[dict[str, str]] = []
        for item in apps_data:
            if isinstance(item, dict):
                label = str(item.get("label") or item.get("packageName") or "Unknown")
                package = str(item.get("packageName") or "").strip()
                if package:
                    apps.append({"label": label, "packageName": package})
        apps.sort(key=lambda it: it["label"].lower())

        session = LockAppPickerSession(
            apps=apps,
            locked_packages={str(v) for v in locked_data if isinstance(v, str)},
            query=(query or "").strip(),
        )
        view = LockAppPickerView(
            client=self,
            owner_user_id=interaction.user.id,
            guild_id=int(interaction.guild_id),
            channel_id=int(interaction.channel_id),
            session=session,
        )
        message = await interaction.followup.send(view.render_text(), view=view, wait=True)
        view.message = message

    async def _send_device_command_wait(
        self,
        guild_id: str,
        channel_id: str,
        discord_user_id: str,
        command_name: str,
        payload: dict[str, Any],
        timeout_seconds: int = 45,
        silent: bool = False,
    ) -> dict[str, Any] | None:
        if command_name.lower() in DEVICE_COMMAND_NAMES and not self._is_supported_device_command(command_name):
            raise BackendApiError(
                f"Command `{command_name}` is not supported by this backend deployment",
                status=400,
                details={"error": "UNSUPPORTED_BY_BACKEND", "hint": "Deploy newer backend or use /backendstatus"},
            )

        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: asyncio.Future[dict[str, Any]] = loop.create_future()
        self._pending_results[request_id] = future
        if silent:
            self._silent_request_ids.add(request_id)

        try:
            await self.backend.post(
                "/api/v1/commands",
                {
                    "requestId": request_id,
                    "guildId": guild_id,
                    "channelId": channel_id,
                    "discordUserId": discord_user_id,
                    "commandName": command_name,
                    "payload": payload,
                },
            )
            return await asyncio.wait_for(future, timeout=timeout_seconds)
        finally:
            self._pending_results.pop(request_id, None)
            self._silent_request_ids.discard(request_id)

    async def _queue_remote_command(self, interaction: discord.Interaction, command_name: str, payload: dict[str, Any]) -> None:
        if not await self._validate_guild_context(interaction):
            return

        if command_name.lower() in DEVICE_COMMAND_NAMES and not self._is_supported_device_command(command_name):
            await interaction.response.send_message(
                f"`{command_name}` is not supported by current backend build. Run `/backendstatus`.",
                ephemeral=True,
            )
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
        except BackendApiError as exc:
            if exc.status == 400 and isinstance(exc.details, dict) and exc.details.get("error") == "UNKNOWN_COMMAND":
                await interaction.followup.send(
                    f"Command failed: backend outdated / not synced with bot build for `{command_name}`. Run `/backendstatus`."
                )
                return
            await interaction.followup.send(f"Command failed: {format_error(exc)}")
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
        request_id = payload.get("requestId")
        if isinstance(request_id, str):
            pending = self._pending_results.get(request_id)
            if pending and not pending.done():
                pending.set_result(payload)
            if request_id in self._silent_request_ids:
                self._silent_request_ids.discard(request_id)
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
