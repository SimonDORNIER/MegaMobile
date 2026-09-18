#!/usr/bin/env python3
"""Rebuild the APK referenced by live/app.json and verify its integrity."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "live" / "app.json"
APK_DIR = ROOT / "live" / "apk"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def fail(message: str) -> None:
    raise SystemExit(f"LIVE_UPDATE_INVALID: {message}")


def main() -> None:
    args = parse_args()
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    version_code = manifest.get("versionCode")
    version_name = manifest.get("versionName")
    expected_hash = str(manifest.get("sha256", "")).lower()
    parts = manifest.get("apkParts")

    if not isinstance(version_code, int) or version_code <= 0:
        fail("versionCode absent ou invalide")
    if not isinstance(version_name, str) or not version_name.strip():
        fail("versionName absent")
    if len(expected_hash) != 64:
        fail("sha256 absent ou invalide")
    if not isinstance(parts, list) or not parts:
        fail("apkParts doit contenir au moins un fragment")

    rebuilt = bytearray()
    for index, url in enumerate(parts):
        if not isinstance(url, str):
            fail(f"fragment {index} invalide")
        name = Path(urlparse(url).path).name
        if not name or name != Path(name).name:
            fail(f"nom de fragment {index} invalide")
        path = APK_DIR / name
        if not path.is_file():
            fail(f"fragment manquant : {name}")
        try:
            encoded = b"".join(path.read_bytes().split())
            rebuilt.extend(base64.b64decode(encoded, validate=True))
        except binascii.Error as exc:
            fail(f"fragment Base64 invalide : {name} ({exc})")

    actual_hash = hashlib.sha256(rebuilt).hexdigest()
    if actual_hash != expected_hash:
        fail(f"SHA-256 réel {actual_hash}, attendu {expected_hash}")

    with tempfile.NamedTemporaryFile(suffix=".apk") as temporary:
        temporary.write(rebuilt)
        temporary.flush()
        try:
            with zipfile.ZipFile(temporary.name) as archive:
                required = {"AndroidManifest.xml", "classes.dex"}
                missing = required.difference(archive.namelist())
                if missing:
                    fail("entrées APK manquantes : " + ", ".join(sorted(missing)))
                bad_file = archive.testzip()
                if bad_file:
                    fail(f"CRC invalide : {bad_file}")
        except zipfile.BadZipFile as exc:
            fail(f"archive APK invalide ({exc})")

        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(temporary.name, args.output)

    print(
        f"LIVE_UPDATE_VALID version={version_name} code={version_code} "
        f"bytes={len(rebuilt)} parts={len(parts)} sha256={actual_hash}"
    )


if __name__ == "__main__":
    main()
