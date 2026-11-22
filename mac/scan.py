"""Scan BLE adverts through the USB dongle (Bumble) and print anything that looks like a Tile.

Usage: BUMBLE_RTK_FIRMWARE_DIR=firmware .venv/bin/python mac/scan.py [seconds] [--all]
Tile Inc. registered 16-bit service UUIDs: 0xFEEC, 0xFEED.
"""

import asyncio
import sys

from bumble.core import AdvertisingData
from bumble.device import Device
from bumble.hci import Address
from bumble.transport import open_transport

TRANSPORT = "usb:2357:0604"
TILE_UUIDS = {"FEEC", "FEED"}


def describe(adv) -> tuple[bool, str]:
    data = adv.data
    name = data.get(AdvertisingData.COMPLETE_LOCAL_NAME) or data.get(
        AdvertisingData.SHORTENED_LOCAL_NAME
    )
    uuids = []
    for t in (
        AdvertisingData.COMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS,
        AdvertisingData.INCOMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS,
    ):
        uuids += [str(u).upper()[-4:] for u in (data.get(t) or [])]
    service_data = data.get_all(AdvertisingData.SERVICE_DATA_16_BIT_UUID) or []
    sd = [(str(u).upper()[-4:], bytes(v).hex()) for u, v in service_data]
    is_tile = bool(TILE_UUIDS & (set(uuids) | {u for u, _ in sd})) or (
        name is not None and "tile" in str(name).lower()
    )
    return is_tile, (
        f"{adv.address} rssi={adv.rssi} connectable={adv.is_connectable} "
        f"name={name!r} uuids={uuids} service_data={sd} raw={bytes(data).hex()}"
    )


async def main() -> None:
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1][0].isdigit() else 15
    show_all = "--all" in sys.argv
    async with await open_transport(TRANSPORT) as (source, sink):
        device = Device.with_hci("scanner", Address("F0:F1:F2:F3:F4:F5"), source, sink)
        seen: dict[str, str] = {}

        def on_adv(adv) -> None:
            is_tile, line = describe(adv)
            key = str(adv.address)
            if (is_tile or show_all) and seen.get(key) != line.split(" rssi=")[1].split(" ", 1)[1]:
                seen[key] = line.split(" rssi=")[1].split(" ", 1)[1]
                print(("TILE  " if is_tile else "      ") + line, flush=True)

        device.on("advertisement", on_adv)
        await device.power_on()
        print(f"controller up: {device.public_address}", flush=True)
        await device.start_scanning(active=True, filter_duplicates=False)
        await asyncio.sleep(seconds)
        await device.stop_scanning()
        print(f"done; {len(seen)} matching devices", flush=True)


asyncio.run(main())
