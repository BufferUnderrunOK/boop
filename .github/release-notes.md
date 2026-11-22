## Install

Not for the faint of heart: you'll need a computer, a terminal, and USB debugging on your phone.
Full details are in the [README](https://github.com/BufferUnderrunOK/boop#install-a-release).

**You need:** an Android 13+ phone, a computer with Python 3 and
[adb](https://developer.android.com/tools/releases/platform-tools), and your Tile account login.

1. **Get your Tile keys** (once). Download `get_keys.py` below and run it:
   ```
   python3 get_keys.py
   ```
   It asks for your Tile email and password and writes `tile-keys.json`. Only the keys are saved.
2. **Turn on USB debugging**: Settings → About phone → tap *Build number* 7 times, then
   Settings → System → Developer options → *USB debugging*. Plug in the phone and tap **Allow**.
3. **Install Boop and copy your keys over:**
   ```
   adb install -r boop-{{TAG}}.apk
   adb push tile-keys.json /sdcard/Download/
   ```
4. Open Boop, tap **Import tile-keys.json**, pick it from Downloads, then **delete the copy** in
   Downloads. USB debugging can go back off.

**Updating:** `adb install -r boop-{{TAG}}.apk`. Your imported keys are kept.
If you built Boop yourself before, uninstall that copy first: releases are signed with a
different key. Then re-import your keys.

**Never** factory-reset a Tile or delete your Tile/Life360 account. That changes or kills the keys.
Hiding a Tile in the Tile app is fine.

Unofficial; not affiliated with Tile or Life360.
