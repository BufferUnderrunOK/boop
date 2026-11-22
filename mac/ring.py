"""Mac test harness: ring a Tile through the USB dongle (Bumble), using the key from tile-keys.json.

Proves the auth key + TOA handshake on the real Tile before the Android app is built.
Protocol ported from joostlek/python-tile-ble src/tile_ble/{protocol,device,const}.py
(MIT; ref/ checkout), which follows lesleyxyz/node-tile.

Usage: BUMBLE_RTK_FIRMWARE_DIR=firmware .venv/bin/python -u mac/ring.py <MAC> [tile_id|name]
"""

import asyncio
import base64
import hashlib
import hmac
import json
import os
import sys

from bumble.core import UUID
from bumble.device import Device, Peer
from bumble.hci import Address
from bumble.transport import open_transport

TRANSPORT = "usb:2357:0604"
CMD_UUID = UUID("9d410018-35d6-f4dd-ba60-e7bd8dc491c0")
RSP_UUID = UUID("9d410019-35d6-f4dd-ba60-e7bd8dc491c0")
KEYS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tile-keys.json")

AUTHENTICATE, OPEN_CHANNEL, CLOSE_CHANNEL, READY, SONG = 0x14, 0x10, 0x11, 0x12, 0x05
R_READY, R_ERROR, R_SONG, R_OPEN, R_CLOSE, R_AUTH = 0x01, 0x05, 0x07, 0x12, 0x13, 0x15


def h(key: bytes, *parts: bytes) -> bytes:
    data = b"".join(parts)[:32].ljust(32, b"\x00")
    return hmac.new(key, data, hashlib.sha256).digest()


def pick_key(selector: str | None, mac: str) -> tuple[str, bytes]:
    tiles = json.load(open(KEYS))
    macb = bytes.fromhex(mac.replace(":", ""))
    for t in tiles:
        if not t.get("auth_key"):
            continue
        tid = bytes.fromhex(t["tile_id"]) if len(t["tile_id"]) == 16 else b""
        by_mac = tid[:6] in (macb, bytes([macb[0] & 0x3F]) + macb[1:]) if tid else False
        if (selector and selector in (t["tile_id"], t.get("name"))) or (not selector and by_mac):
            return t.get("name") or t["tile_id"], base64.b64decode(t["auth_key"])
    names = [(t.get("name"), t["tile_id"]) for t in tiles]
    raise SystemExit(f"No key matched {selector or mac}. Known: {names}")


async def main() -> None:
    mac = sys.argv[1]
    name, auth_key = pick_key(sys.argv[2] if len(sys.argv) > 2 else None, mac)
    print(f"key for {name!r}", flush=True)

    async with await open_transport(TRANSPORT) as (source, sink):
        device = Device.with_hci("ringer", Address("F0:F1:F2:F3:F4:F5"), source, sink)
        await device.power_on()
        print("connecting...", flush=True)
        conn = await device.connect(Address(mac, Address.RANDOM_DEVICE_ADDRESS), timeout=20)
        try:
            peer = Peer(conn)
            await peer.discover_services()
            await peer.discover_characteristics()
            cmd = peer.get_characteristics_by_uuid(CMD_UUID)[0]
            rsp = peer.get_characteristics_by_uuid(RSP_UUID)[0]

            inbox: asyncio.Queue[bytes] = asyncio.Queue()
            await peer.subscribe(rsp, lambda v: inbox.put_nowait(bytes(v)))

            token = os.urandom(4)

            async def send(frame: bytes, want_code: int, cid: int) -> bytes:
                print(f"  -> {frame.hex()}", flush=True)
                await peer.write_value(cmd, frame, with_response=False)
                while True:
                    msg = await asyncio.wait_for(inbox.get(), 10)
                    print(f"  <- {msg.hex()}", flush=True)
                    if cid == 0:
                        if msg[0] != 0 or msg[1:5] not in (token, b"\xff" * 4):
                            continue
                        code, payload = msg[5], msg[6:]
                    else:
                        if msg[0] != cid:
                            continue
                        code, payload = msg[1], msg[2:-4]
                    if code == R_ERROR:
                        raise SystemExit(f"Tile error: {payload.hex()}")
                    if code == R_CLOSE and want_code != R_CLOSE:
                        raise SystemExit(f"Tile closed channel, reason {payload[:1].hex()} (01 = MIC failure = wrong key)")
                    if code == want_code:
                        return payload

            rand_a = os.urandom(14)
            auth = await send(bytes([0]) + token + bytes([AUTHENTICATE]) + rand_a, R_AUTH, 0)
            rand_t, sres = auth[:10], auth[10:14]
            expect = hmac.new(auth_key, rand_a.ljust(16, b"\0") + rand_t.ljust(16, b"\0"), hashlib.sha256).digest()[4:8]
            print(f"SRES {'MATCHES' if sres == expect else 'DOES NOT MATCH'} the key", flush=True)

            opened = await send(bytes([0]) + token + bytes([OPEN_CHANNEL]) + rand_a, R_OPEN, 0)
            cid, ch_data = opened[0], opened[1:14]
            session_key = h(auth_key, rand_a, ch_data, bytes([cid]), token)[:16]
            nonce = 0

            async def chan(msg: bytes, want: int) -> bytes:
                nonlocal nonce
                nonce += 1
                mic = h(session_key, nonce.to_bytes(8, "little"), bytes([0x01, len(msg)]), msg)[:4]
                return await send(bytes([cid]) + msg + mic, want, cid)

            ready = await chan(bytes([READY, 0x13]), R_READY)
            print(f"channel {cid} open; READY payload {ready.hex()}", flush=True)
            song = await chan(bytes([SONG, 0x02, 0x01, 0x03]), R_SONG)
            print("RINGING" if song[:1] == b"\x02" else f"song refused: {song.hex()}", flush=True)
            await asyncio.sleep(1)
            nonce += 1
            close = bytes([CLOSE_CHANNEL, 0x00])
            mic = h(session_key, nonce.to_bytes(8, "little"), bytes([0x01, len(close)]), close)[:4]
            await peer.write_value(cmd, bytes([cid]) + close + mic, with_response=False)
        finally:
            await conn.disconnect()
            await device.power_off()


asyncio.run(main())
