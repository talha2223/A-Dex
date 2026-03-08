import hashlib
import hmac
import json
from typing import Any


def sign_body(secret: str, timestamp: str, body: str | dict[str, Any] | None) -> str:
    """Match backend HMAC format: sha256(secret, f"{timestamp}.{raw_json_body}")."""
    if isinstance(body, str):
        payload = body
    else:
        payload = json.dumps(body or {}, separators=(",", ":"), ensure_ascii=False)

    return hmac.new(secret.encode("utf-8"), f"{timestamp}.{payload}".encode("utf-8"), hashlib.sha256).hexdigest()
