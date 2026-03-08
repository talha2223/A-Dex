import re
from dataclasses import dataclass
from typing import Any

REMOTE_COMMANDS = {
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
    "show",
    "message",
    "lockapp",
    "unlockapp",
    "lockedapps",
    "usage",
}

ADMIN_COMMANDS = {"pair", "bind", "unbind", "admins", "devices"}
TOKEN_REGEX = re.compile(r'"([^"]+)"|\'([^\']+)\'|(\S+)')


@dataclass(frozen=True)
class ParsedCommand:
    name: str
    args: list[str]
    is_remote: bool
    is_admin: bool



def tokenize(text: str) -> list[str]:
    """Tokenize command text with quoted argument support."""
    tokens: list[str] = []
    for match in TOKEN_REGEX.finditer(text):
        tokens.append(match.group(1) or match.group(2) or match.group(3))
    return tokens



def parse_command_input(content: str | None, prefix: str) -> ParsedCommand | None:
    if not content or not content.startswith(prefix):
        return None

    raw = content[len(prefix) :].strip()
    if not raw:
        return None

    tokens = tokenize(raw)
    if not tokens:
        return None

    name = tokens[0].lower()
    args = tokens[1:]
    return ParsedCommand(
        name=name,
        args=args,
        is_remote=name in REMOTE_COMMANDS,
        is_admin=name in ADMIN_COMMANDS,
    )



def build_remote_payload(name: str, args: list[str], attachment: dict[str, Any] | None, max_image_bytes: int) -> dict[str, Any]:
    if name in {
        "apps",
        "lock",
        "stopaudio",
        "pauseaudio",
        "resumeaudio",
        "audiostatus",
        "screenshot",
        "files",
        "camerasnap",
        "randomquote",
        "info",
        "permstatus",
        "location",
        "usage",
        "lockedapps",
    }:
        return {"payload": {}}

    if name == "open":
        if not args:
            return {"error": "Usage: !open <app package or display name>"}
        return {"payload": {"target": " ".join(args)}}

    if name == "say":
        if not args:
            return {"error": "Usage: !say <text>"}
        return {"payload": {"text": " ".join(args)}}

    if name == "sayurdu":
        if not args:
            return {"error": "Usage: !sayurdu <urdu text>"}
        return {"payload": {"text": " ".join(args)}}

    if name == "playaudio":
        if not args:
            return {"error": "Usage: !playaudio <url> [repeat]"}
        repeat = 1
        if len(args) > 1:
            try:
                repeat = int(float(args[1]))
            except ValueError:
                return {"error": "Usage: !playaudio <url> [repeat]"}
            if repeat < 1 or repeat > 100:
                return {"error": "Usage: !playaudio <url> [repeat]"}
        return {"payload": {"url": args[0], "repeat": repeat}}

    if name == "parentpin":
        if not args:
            return {"error": "Usage: !parentpin <4-12 digit pin>"}
        return {"payload": {"pin": args[0]}}

    if name == "shield":
        action = args[0].lower() if args else "status"
        if action not in {"enable", "disable", "status", "relock"}:
            return {"error": "Usage: !shield <enable|disable|status|relock>"}
        return {"payload": {"action": action}}

    if name == "download":
        if not args:
            return {"error": "Usage: !download <path>"}
        return {"payload": {"path": " ".join(args)}}

    if name == "filestat":
        if not args:
            return {"error": "Usage: !filestat <path>"}
        return {"payload": {"path": " ".join(args)}}

    if name == "mkdir":
        if not args:
            return {"error": "Usage: !mkdir <path>"}
        return {"payload": {"path": " ".join(args)}}

    if name == "rename":
        if len(args) < 2:
            return {"error": "Usage: !rename <path> <new_name>"}
        return {"payload": {"path": args[0], "new_name": args[1]}}

    if name == "move":
        if len(args) < 2:
            return {"error": "Usage: !move <source> <target_dir>"}
        return {"payload": {"source": args[0], "target_dir": args[1]}}

    if name == "delete":
        if not args:
            return {"error": "Usage: !delete <path> [recursive:true|false]"}
        recursive = False
        if len(args) > 1:
            recursive = args[1].lower() == "true"
        return {"payload": {"path": args[0], "recursive": recursive}}

    if name == "uploadfile":
        if len(args) < 2:
            return {"error": "Usage: !uploadfile <target_dir> <url> [file_name]"}
        payload = {"target_dir": args[0], "url": args[1]}
        if len(args) > 2:
            payload["file_name"] = args[2]
        return {"payload": payload}

    if name == "readtext":
        if not args:
            return {"error": "Usage: !readtext <path> [max_chars]"}
        max_chars = 2000
        if len(args) > 1:
            try:
                max_chars = int(float(args[1]))
            except ValueError:
                return {"error": "Usage: !readtext <path> [max_chars]"}
        return {"payload": {"path": args[0], "max_chars": max_chars}}

    if name == "volume":
        try:
            value = int(float(args[0]))
        except (IndexError, ValueError):
            return {"error": "Usage: !volume <0-100>"}

        if value < 0 or value > 100:
            return {"error": "Usage: !volume <0-100>"}
        return {"payload": {"value": value}}

    if name == "show":
        try:
            seconds = int(float(args[0]))
        except (IndexError, ValueError):
            return {"error": "Usage: !show <1-60> with an image attachment"}

        if seconds < 1 or seconds > 60:
            return {"error": "Usage: !show <1-60> with an image attachment"}

        if not attachment:
            return {"error": "Attach a single image to use !show"}

        content_type = (attachment.get("content_type") or "").lower()
        if not content_type.startswith("image/"):
            return {"error": "Attachment must be an image"}

        size = attachment.get("size")
        if isinstance(size, int) and size > max_image_bytes:
            return {"error": f"Attachment too large; max {max_image_bytes // (1024 * 1024)} MB"}

        return {
            "payload": {
                "seconds": seconds,
                "imageUrl": attachment.get("url"),
                "imageName": attachment.get("name") or "image",
                "imageContentType": content_type or "image/*",
            }
        }

    if name == "message":
        if not args:
            return {"error": "Usage: !message <text>"}
        return {"payload": {"text": " ".join(args)}}

    if name == "contactlookup":
        if not args:
            return {"error": "Usage: !contactlookup <query> [limit]"}
        payload: dict[str, Any] = {"query": args[0]}
        if len(args) > 1:
            try:
                payload["limit"] = int(float(args[1]))
            except ValueError:
                return {"error": "Usage: !contactlookup <query> [limit]"}
        return {"payload": payload}

    if name == "smsdraft":
        if len(args) < 2:
            return {"error": "Usage: !smsdraft <number> <message>"}
        return {"payload": {"number": args[0], "message": " ".join(args[1:])}}

    if name == "fileshareintent":
        if not args:
            return {"error": "Usage: !fileshareintent <path> [mime_type]"}
        payload: dict[str, Any] = {"path": args[0]}
        if len(args) > 1:
            payload["mimeType"] = args[1]
        return {"payload": payload}

    if name == "quicklaunch":
        if not args:
            return {"error": "Usage: !quicklaunch <package_or_url>"}
        target = args[0]
        if target.startswith("http://") or target.startswith("https://"):
            return {"payload": {"url": target}}
        return {"payload": {"packageName": target}}

    if name == "torchpattern":
        repeats = 3
        on_ms = 250
        off_ms = 250
        if len(args) > 0:
            try:
                repeats = int(float(args[0]))
            except ValueError:
                return {"error": "Usage: !torchpattern [repeats] [on_ms] [off_ms]"}
        if len(args) > 1:
            try:
                on_ms = int(float(args[1]))
            except ValueError:
                return {"error": "Usage: !torchpattern [repeats] [on_ms] [off_ms]"}
        if len(args) > 2:
            try:
                off_ms = int(float(args[2]))
            except ValueError:
                return {"error": "Usage: !torchpattern [repeats] [on_ms] [off_ms]"}
        return {"payload": {"repeats": repeats, "on_ms": on_ms, "off_ms": off_ms}}

    if name == "ringtoneprofile":
        if not args:
            return {"error": "Usage: !ringtoneprofile <normal|vibrate|silent>"}
        mode = args[0].lower()
        if mode not in {"normal", "vibrate", "silent"}:
            return {"error": "Usage: !ringtoneprofile <normal|vibrate|silent>"}
        return {"payload": {"mode": mode}}

    if name == "screentimeoutset":
        if not args:
            return {"error": "Usage: !screentimeoutset <seconds>"}
        try:
            seconds = int(float(args[0]))
        except ValueError:
            return {"error": "Usage: !screentimeoutset <seconds>"}
        return {"payload": {"seconds": seconds}}

    if name == "mediacontrol":
        if not args:
            return {"error": "Usage: !mediacontrol <play|pause|next|previous|stop|toggle>"}
        action = args[0].lower()
        if action not in {"play", "pause", "next", "previous", "stop", "toggle"}:
            return {"error": "Usage: !mediacontrol <play|pause|next|previous|stop|toggle>"}
        return {"payload": {"action": action}}

    if name == "fakecallui":
        caller_name = " ".join(args) if args else "Unknown Caller"
        return {"payload": {"callerName": caller_name}}

    if name == "shakealert":
        action = args[0].lower() if args else "status"
        if action not in {"start", "stop", "status"}:
            return {"error": "Usage: !shakealert <start|stop|status>"}
        return {"payload": {"action": action}}

    if name == "lockapp":
        if not args:
            return {"error": "Usage: !lockapp <package>"}
        return {"payload": {"packageName": args[0]}}

    if name == "unlockapp":
        if not args:
            return {"error": "Usage: !unlockapp <package>"}
        return {"payload": {"packageName": args[0]}}

    return {"error": "Unknown remote command"}
