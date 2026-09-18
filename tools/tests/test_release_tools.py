from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PUBLISH_SCRIPT = PROJECT_ROOT / "tools" / "publish_live_update.py"
VERIFY_SCRIPT = PROJECT_ROOT / "tools" / "verify_live_update.py"
SIGNER_SCRIPT = PROJECT_ROOT / "tools" / "verify_release_signer.sh"


class LiveUpdateToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        (self.root / "tools").mkdir()
        (self.root / "live" / "apk").mkdir(parents=True)
        shutil.copy2(PUBLISH_SCRIPT, self.root / "tools")
        shutil.copy2(VERIFY_SCRIPT, self.root / "tools")

        self.apk = self.root / "MegaMobile-test.apk"
        with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex\n" + bytes(range(256)) * 16)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def run_tool(self, script: str, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(self.root / "tools" / script), *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

    def publish(self) -> subprocess.CompletedProcess[str]:
        return self.run_tool(
            "publish_live_update.py",
            "--apk",
            str(self.apk),
            "--version-code",
            "42",
            "--version-name",
            "2.3.4",
            "--repository",
            "example/MegaMobile",
            "--note",
            "Version de test",
            "--chunk-bytes",
            "1024",
        )

    def test_publish_then_verify_round_trip(self) -> None:
        publication = self.publish()
        self.assertEqual(publication.returncode, 0, publication.stderr)

        manifest = json.loads((self.root / "live" / "app.json").read_text())
        self.assertEqual(manifest["versionCode"], 42)
        self.assertEqual(manifest["versionName"], "2.3.4")
        self.assertGreater(len(manifest["apkParts"]), 1)

        rebuilt = self.root / "rebuilt.apk"
        verification = self.run_tool(
            "verify_live_update.py", "--output", str(rebuilt)
        )
        self.assertEqual(verification.returncode, 0, verification.stderr)
        self.assertEqual(rebuilt.read_bytes(), self.apk.read_bytes())

    def test_publish_replaces_stale_parts_for_same_version(self) -> None:
        stale = self.root / "live" / "apk" / "v2.3.4.part99.b64"
        stale.write_text("stale\n", encoding="ascii")

        publication = self.publish()

        self.assertEqual(publication.returncode, 0, publication.stderr)
        self.assertFalse(stale.exists())

    def test_verify_rejects_corrupted_part(self) -> None:
        self.assertEqual(self.publish().returncode, 0)
        first_part = self.root / "live" / "apk" / "v2.3.4.part00.b64"
        first_part.write_text("not-base64!\n", encoding="ascii")

        verification = self.run_tool("verify_live_update.py")

        self.assertNotEqual(verification.returncode, 0)
        self.assertIn("fragment Base64 invalide", verification.stderr)

    def test_publish_rejects_unsafe_version_name(self) -> None:
        publication = self.run_tool(
            "publish_live_update.py",
            "--apk",
            str(self.apk),
            "--version-code",
            "42",
            "--version-name",
            "../../escape",
            "--repository",
            "example/MegaMobile",
            "--note",
            "Version de test",
        )

        self.assertNotEqual(publication.returncode, 0)
        self.assertIn("versionName", publication.stderr)


class SignerVerificationTest(unittest.TestCase):
    FINGERPRINT = "74dfb03888f239ee3e7a9c8b16a04a20d706d73a2da3b570bbeaaa47ead76fb2"

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"test")
        self.expected = self.root / "expected.sha256"
        self.expected.write_text(self.FINGERPRINT + "\n", encoding="ascii")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def run_verification(self, signer_line: str) -> subprocess.CompletedProcess[str]:
        apksigner = self.root / "apksigner"
        apksigner.write_text(
            "#!/usr/bin/env bash\n"
            "echo 'Verified using v3 scheme: true'\n"
            f"echo '{signer_line}'\n",
            encoding="utf-8",
        )
        apksigner.chmod(0o700)
        environment = os.environ.copy()
        environment["PATH"] = f"{self.root}:{environment['PATH']}"
        return subprocess.run(
            ["bash", str(SIGNER_SCRIPT), str(self.apk), str(self.expected)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def test_accepts_numbered_signer_output(self) -> None:
        result = self.run_verification(
            f"Signer #1 certificate SHA-256 digest: {self.FINGERPRINT}"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SIGNER_VALID", result.stdout)

    def test_accepts_scheme_prefixed_signer_output(self) -> None:
        result = self.run_verification(
            f"V3.0 Signer: certificate SHA-256 digest: {self.FINGERPRINT}"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SIGNER_VALID", result.stdout)

    def test_rejects_another_certificate(self) -> None:
        result = self.run_verification(
            "V3.0 Signer: certificate SHA-256 digest: " + "0" * 64
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SIGNER_INVALID", result.stderr)


if __name__ == "__main__":
    unittest.main()
