"""One-time: log in to the Tile cloud and save each Tile's BLE auth key to tile-keys.json.

Run it yourself in a terminal (it prompts for the Tile account email + password; nothing is
stored but the keys):   .venv/bin/python mac/get_keys.py

Endpoints/headers follow joostlek/python-tile-ble src/tile_ble/cloud.py (read 2026-11-22,
ref/ checkout). Stdlib only.
"""

import getpass
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar

API_URL = "https://production.tile-api.com/api/v1"
APP_ID = "ios-tile-production"
APP_VERSION = "2.89.1.4774"
HEADERS = {
    "User-Agent": "Tile/4774 CFNetwork/1312 Darwin/21.0.0",
    "tile_api_version": "1.0",
    "tile_app_id": APP_ID,
    "tile_app_version": APP_VERSION,
}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tile-keys.json")


def main() -> None:
    client_uuid = str(uuid.uuid4())
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))

    def request(method: str, endpoint: str, form: dict | None = None):
        data = urllib.parse.urlencode(form).encode() if form else None
        req = urllib.request.Request(
            f"{API_URL}/{endpoint}",
            data=data,
            method=method,
            headers={**HEADERS, "tile_client_uuid": client_uuid},
        )
        with opener.open(req, timeout=30) as resp:
            return json.load(resp)["result"]

    email = input("Tile account email: ").strip()
    password = getpass.getpass("Tile account password (not echoed, not saved): ")

    request("PUT", f"clients/{client_uuid}", {"app_id": APP_ID, "app_version": APP_VERSION, "locale": "en-US"})
    try:
        request("POST", f"clients/{client_uuid}/sessions", {"email": email, "password": password})
    except urllib.error.HTTPError as err:
        raise SystemExit(f"Login failed: HTTP {err.code} {err.read()[:300]!r}")

    tiles = []
    for state in request("GET", "tiles/tile_states"):
        tile_id = state["tile_id"]
        try:
            t = request("GET", f"tiles/{tile_id}")
        except urllib.error.HTTPError as err:
            print(f"  {tile_id}: skipped (HTTP {err.code}; 412 = Tile Label, no BLE)")
            continue
        fw = t.get("firmware") or {}
        tiles.append(
            {
                "tile_id": t.get("tile_uuid", tile_id),
                "name": t.get("name"),
                "auth_key": t.get("auth_key"),
                "product": t.get("product"),
                "expected_tdt_cmd_config": fw.get("expected_tdt_cmd_config"),
            }
        )
        print(f"  {t.get('name')!r}  id={tile_id}  key={'yes' if t.get('auth_key') else 'MISSING'}")

    fd = os.open(OUT, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(tiles, f, indent=2)
    print(f"Saved {len(tiles)} Tiles to {os.path.normpath(OUT)}")


main()
