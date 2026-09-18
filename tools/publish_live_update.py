#!/usr/bin/env python3
"""Generate independently decodable APK chunks and update live/app.json."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LIVE_DIR = ROOT / "live"
APK_DIR = LIVE_DIR / "apk"
VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--note", required=True)
    parser.add_argument("--chunk-bytes", type=int, default=48 * 1024)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.version_code <= 0:
        raise SystemExit("versionCode doit être positif")
    if not VERSION_PATTERN.fullmatch(args.version_name):
        raise SystemExit("versionName contient des caractères non pris en charge")
    if args.chunk_bytes < 1024:
        raise SystemExit("chunk-bytes doit être supérieur ou égal à 1024")

    apk = args.apk.resolve()
    payload = apk.read_bytes()
    if len(payload) < 1024 or not payload.startswith(b"PK"):
        raise SystemExit("Le fichier fourni n'est pas un APK valide")

    APK_DIR.mkdir(parents=True, exist_ok=True)
    prefix = f"v{args.version_name}.part"
    for old_part in APK_DIR.glob(f"{prefix}*.b64"):
        old_part.unlink()

    urls: list[str] = []
    for index, start in enumerate(range(0, len(payload), args.chunk_bytes)):
        encoded = base64.b64encode(payload[start : start + args.chunk_bytes]).decode("ascii")
        name = f"{prefix}{index:02d}.b64"
        (APK_DIR / name).write_text(encoded + "\n", encoding="ascii")
        urls.append(
            f"https://raw.githubusercontent.com/{args.repository}/main/live/apk/{name}"
        )

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": "",
        "apkParts": urls,
        "sha256": hashlib.sha256(payload).hexdigest(),
        "note": args.note,
    }
    (LIVE_DIR / "app.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"LIVE_PAYLOAD version={args.version_name} code={args.version_code} "
        f"bytes={len(payload)} parts={len(urls)} sha256={manifest['sha256']}"
    )


if __name__ == "__main__":
    main()
