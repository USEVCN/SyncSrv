"""Verify the packaged launcher's isolation and failure behavior without a host JVM."""

import os
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("package_linux", ROOT / "scripts/package-linux.py")
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)


class ApplicationNoticesTest(unittest.TestCase):
    def test_package_retains_full_notices_and_rejects_missing_license(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            image = base / 'image'
            PACKAGER.include_application_notices(image)
            self.assertEqual((ROOT / 'LICENSE').read_bytes(), (image / 'LICENSE').read_bytes())
            self.assertEqual((ROOT / 'Frontend/public/licenses/ui-third-party.txt').read_bytes(),
                             (image / 'licenses/ui-third-party.txt').read_bytes())
            with patch.object(PACKAGER, 'ROOT', base / 'missing-source'):
                with self.assertRaisesRegex(ValueError, 'license notice missing'):
                    PACKAGER.include_application_notices(base / 'rejected')
            self.assertFalse((base / 'rejected').exists())

    def test_jar_requires_current_notices_in_served_assets_and_metadata(self):
        entries = {name: (ROOT / Path(name).name).read_bytes()
                   for name in ('META-INF/LICENSE', 'META-INF/NOTICE', 'META-INF/THIRD_PARTY_NOTICES.md')}
        for prefix in ('META-INF/licenses/', 'BOOT-INF/classes/static/licenses/'):
            for name in ('ui-third-party.txt', 'react-bits.txt'):
                entries[prefix + name] = (ROOT / 'Frontend/public/licenses' / name).read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            for mode in ('current', 'missing', 'stale'):
                with zipfile.ZipFile(jar, 'w') as app:
                    for name, content in entries.items():
                        if name == 'BOOT-INF/classes/static/licenses/ui-third-party.txt':
                            if mode == 'missing':
                                continue
                            if mode == 'stale':
                                content = b'Incomplete outdated license fixture'
                        app.writestr(name, content)
                with zipfile.ZipFile(jar) as app:
                    if mode == 'current':
                        PACKAGER.verify_application_notices(app)
                    else:
                        with self.assertRaisesRegex(ValueError, 'license notice'):
                            PACKAGER.verify_application_notices(app)


class AgentReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="syncsrv-agent-package-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.releases = self.base / "artifacts/agent-releases"
        self.directory = self.releases / "0.15.0-dev"
        self.directory.mkdir(parents=True)
        (self.releases / "latest.txt").write_text("0.15.0-dev\n")
        self.image = self.base / "image"
        self.manifest = {"version": self.directory.name, "assets": []}
        for system in ("windows", "linux", "darwin", "synology"):
            for arch in ("amd64", "arm64"):
                stem = f"syncsrv-agent-{system}-{arch}"
                binary = stem + (".exe" if system == "windows" else "")
                package = stem + (".zip" if system == "windows" else ".spk" if system == "synology" else ".tar.gz")
                asset = dict(platform=system, arch=arch, filename=binary, packageName=package)
                for name, digest_key, size_key in ((binary, "sha256", "size"), (package, "packageSha256", "packageSize")):
                    content = ("fixture:" + name).encode()
                    digest = hashlib.sha256(content).hexdigest()
                    (self.directory / name).write_bytes(content)
                    (self.directory / (name + ".sha256")).write_text(f"{digest}  {name}\n")
                    asset[digest_key], asset[size_key] = digest, len(content)
                self.manifest["assets"].append(asset)
        self.write_manifest()

    def write_manifest(self):
        (self.directory / "release.json").write_text(json.dumps(self.manifest))

    def rejects(self):
        with self.assertRaises(ValueError):
            PACKAGER.include_agents(self.image, self.directory)
        self.assertFalse(self.image.exists(), "Rejected releases must not leave a partially bundled image")

    def test_complete_eight_platform_release_copies_binary_packages_and_checksums(self):
        result = PACKAGER.include_agents(self.image, self.directory)
        self.assertEqual(result["version"], "0.15.0-dev")
        self.assertEqual(len(result["assets"]), 8)
        target = self.image / "agent-releases" / self.directory.name
        self.assertEqual({p.name for p in target.iterdir()}, {p.name for p in self.directory.iterdir()})
        for original in self.directory.iterdir():
            self.assertEqual(original.read_bytes(), (target / original.name).read_bytes())
        self.assertEqual((target.parent / "latest.txt").read_text(), "0.15.0-dev\n")

    def test_defaults_to_latest_and_explicit_directory_does_not_read_latest(self):
        with patch.object(PACKAGER, "ROOT", self.base):
            self.assertEqual(PACKAGER.resolve_agent_releases(), self.directory)
            (self.releases / "latest.txt").unlink()
            self.assertEqual(PACKAGER.resolve_agent_releases(self.directory), self.directory)

    def test_latest_rejects_traversal_and_link_escape(self):
        with patch.object(PACKAGER, "ROOT", self.base):
            for version in ("../other", "/tmp/other", "one\ntwo", "..", "windows\\path"):
                with self.subTest(version=version):
                    (self.releases / "latest.txt").write_text(version)
                    with self.assertRaises(ValueError):
                        PACKAGER.resolve_agent_releases()
            (self.releases / "linked").symlink_to(self.base, target_is_directory=True)
            (self.releases / "latest.txt").write_text("linked")
            with self.assertRaises(ValueError):
                PACKAGER.resolve_agent_releases()

    def test_missing_synology_platform_is_rejected(self):
        self.manifest["assets"].pop()
        self.write_manifest()
        self.rejects()

    def test_duplicate_or_unsupported_platform_is_rejected(self):
        original = self.manifest["assets"][-1]
        for replacement in (self.manifest["assets"][0], {**original, "platform": "unknown"}):
            with self.subTest(replacement=replacement["platform"]):
                self.manifest["assets"][-1] = replacement
                self.write_manifest()
                self.rejects()

    def test_binary_and_package_names_must_match_whitelist(self):
        asset = self.manifest["assets"][0]
        for key in ("filename", "packageName"):
            original = asset[key]
            for name in ("../outside", "/tmp/outside", "folder\\payload", "unexpected.zip"):
                with self.subTest(key=key, name=name):
                    asset[key] = name
                    self.write_manifest()
                    self.rejects()
            asset[key] = original

    def test_every_binary_and_package_is_hashed(self):
        for asset in self.manifest["assets"]:
            for key in ("filename", "packageName"):
                payload = self.directory / asset[key]
                original = payload.read_bytes()
                with self.subTest(name=payload.name):
                    payload.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                    self.rejects()
                payload.write_bytes(original)

    def test_checksums_sizes_and_manifest_version_are_verified(self):
        asset = self.manifest["assets"][-1]
        for key in ("size", "packageSize"):
            original = asset[key]
            asset[key] += 1
            self.write_manifest()
            self.rejects()
            asset[key] = original
        self.manifest["version"] = "0.9.0-dev"
        self.write_manifest()
        self.rejects()

    def test_checksum_files_require_matching_digest_and_filename(self):
        asset = self.manifest["assets"][-1]
        for key, digest in (("filename", "sha256"), ("packageName", "packageSha256")):
            sidecar = self.directory / (asset[key] + ".sha256")
            original = sidecar.read_text()
            for content in (f"{asset[digest]}  wrong-name\n", f"{'0' * 64}  {asset[key]}\n", original + original):
                with self.subTest(key=key, content=content):
                    sidecar.write_text(content)
                    self.rejects()
            sidecar.unlink()
            self.rejects()
            sidecar.write_text(original)

    def test_payload_or_checksum_symlinks_are_rejected(self):
        asset = self.manifest["assets"][-1]
        for name in (asset["filename"], asset["packageName"], asset["packageName"] + ".sha256", "release.json"):
            path = self.directory / name
            content = path.read_bytes()
            outside = self.base / "outside"
            outside.write_bytes(content)
            path.unlink()
            path.symlink_to(outside)
            with self.subTest(name=name):
                self.rejects()
            path.unlink()
            path.write_bytes(content)


class PackagedLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="syncsrv-package-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.package = self.base / "package with spaces"
        shutil.copytree(ROOT / "packaging/linux", self.package)
        (self.package / "app").mkdir()
        (self.package / "app/syncsrv.jar").write_bytes(b"test jar")
        (self.package / "runtime/bin").mkdir(parents=True)
        self.capture = self.base / "invocation"
        bundled = self.package / "runtime/bin/java"
        bundled.write_text(
            '#!/bin/sh\n'
            '{ printf "HOME=%s\\n" "$JAVA_HOME"; printf "ARG=%s\\n" "$@"; '
            'printf "PASS=%s\\n" "${SYNCSRV_INITIAL_PASSWORD:-}"; '
            'printf "DATA=%s\\n" "${SYNCSRV_DATA:-}"; '
            'printf "AGENTS=%s\\n" "${SYNCSRV_AGENT_RELEASES:-}"; '
            'printf "OPTIONS=%s:%s:%s:%s:%s:%s:%s\\n" '
            '"${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" '
            '"${CLASSPATH:-}" "${LD_PRELOAD:-}" "${LD_LIBRARY_PATH:-}" "${JAVA_OPTS:-}"; '
            '} > "$CAPTURE"\n'
        )
        bundled.chmod(0o755)
        system = self.base / "system-bin"
        system.mkdir()
        trap = system / "java"
        trap.write_text('#!/bin/sh\nprintf "host-java-called" > "$TRAP"\nexit 91\n')
        trap.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{system}:/usr/bin:/bin", JAVA_HOME="/wrong/java",
                        CAPTURE=str(self.capture), TRAP=str(self.base / "trap"))
        self.data = self.base / "data with spaces"
        self.config = self.package / "conf/syncsrv.env"
        self.config.write_text(f"SYNCSRV_DATA='{self.data}'\nSYNCSRV_INITIAL_PASSWORD='SyncSrv\\@2026'\n")

    def run_launcher(self, *args):
        result = subprocess.run(
            ["/bin/sh", str(self.package / "bin/start.sh"), *args], env=self.env,
            cwd=self.base, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.assertFalse((self.base / "trap").exists(), "Launcher must never invoke PATH java")
        return result

    def test_uses_bundled_java_and_clears_inherited_options(self):
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                    "LD_LIBRARY_PATH", "JAVA_OPTS"):
            self.env[key] = "INVALID_HOST_OPTIONS"
        self.config.write_text(self.config.read_text() + "LD_PRELOAD='INVALID_LIBRARY'\n")
        result = self.run_launcher("verify-audit", "/tmp/evidence with spaces", "/tmp/key")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = self.capture.read_text()
        self.assertIn(f"HOME={self.package}/runtime\n", captured)
        self.assertIn(f"ARG={self.package}/app/syncsrv.jar\n", captured)
        self.assertIn("ARG=/tmp/evidence with spaces\n", captured)
        self.assertIn("PASS=SyncSrv\\@2026\n", captured)
        self.assertIn("OPTIONS=::::::\n", captured)
        self.assertTrue(self.data.is_dir())
        self.assertIn(f"AGENTS={self.package}/agent-releases\n", captured)

    def test_agent_download_location_can_be_overridden_or_disabled(self):
        original = self.config.read_text()
        for value in ("/srv/custom agents", ""):
            with self.subTest(value=value):
                self.config.write_text(original + f"SYNCSRV_AGENT_RELEASES='{value}'\n")
                result = self.run_launcher()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"AGENTS={value}\n", self.capture.read_text())

    def test_missing_runtime_fails_without_system_java_fallback(self):
        (self.package / "runtime/bin/java").unlink()
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("不会使用系统 Java", result.stderr)
        self.assertFalse(self.capture.exists())

    def test_runtime_info_does_not_require_configuration(self):
        self.config.unlink()
        result = self.run_launcher("--runtime-info")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ARG=-version\n", self.capture.read_text())
        self.assertFalse(self.data.exists())

    def test_missing_configuration_stops_before_java(self):
        self.config.unlink()
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.capture.exists())

    def test_invalid_memory_settings_do_not_become_jvm_arguments(self):
        config = self.config.read_text()
        for value in ("512 -javaagent:bad", "512:1024", "-1", "32"):
            with self.subTest(value=value):
                self.config.write_text(config + f"SYNCSRV_HEAP_MAX_MIB='{value}'\n")
                result = self.run_launcher()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.capture.exists())

    def test_relative_data_path_is_rejected(self):
        self.config.write_text("SYNCSRV_DATA='./relative'\n")
        result = self.run_launcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.capture.exists())


if __name__ == "__main__":
    unittest.main()
