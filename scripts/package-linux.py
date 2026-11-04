"""Build a Linux x64 archive with a pinned, checksum-verified Java 17 runtime."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import time
from release_sign import sign,verify
import release_gate

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "packaging/linux"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def get_runtime(lock, supplied):
    cache = ROOT / "work/runtime"
    cache.mkdir(parents=True, exist_ok=True)
    archive = supplied.resolve() if supplied else cache / lock["filename"]
    if not archive.exists():
        if supplied:
            raise ValueError(f"Runtime archive not found: {archive}")
        temporary = archive.with_suffix(".download")
        try:
            subprocess.run([
                "curl", "--fail", "--location", "--retry", "3", "--connect-timeout", "20",
                "--max-time", "600", "--output", str(temporary), lock["url"],
            ], check=True)
            if sha256(temporary) != lock["sha256"]:
                raise ValueError("Downloaded Java runtime checksum does not match runtime.lock.json")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if sha256(archive) != lock["sha256"]:
        raise ValueError(f"Runtime checksum mismatch: {archive}")
    return archive


def extract_runtime(archive, destination):
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        roots = set()
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts:
                raise ValueError("Unsafe path in runtime archive")
            roots.add(path.parts[0])
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError("Unexpected runtime archive entry")
            if member.issym() or member.islnk():
                base = destination / path.parent if member.issym() else destination
                resolved = (base / member.linkname).resolve()
                if not resolved.is_relative_to(destination.resolve()):
                    raise ValueError("Runtime archive link escapes its directory")
        if len(roots) != 1:
            raise ValueError("Expected one runtime root directory")
        source.extractall(destination, members=members)
    return destination / roots.pop()


def validate_runtime(runtime, lock):
    release = {}
    for line in (runtime / "release").read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator:
            release[key] = value.strip('"')
    if release.get("JAVA_RUNTIME_VERSION") != lock["version"]:
        raise ValueError("Runtime version does not match the lock file")
    if release.get("OS_NAME") != "Linux" or release.get("OS_ARCH") not in ("x86_64", "amd64"):
        raise ValueError("Expected a Linux x64 runtime")
    binary = runtime / "bin/java"
    with binary.open("rb") as stream:
        header = stream.read(20)
    if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 62:
        raise ValueError("Bundled java must be a 64-bit x86 ELF executable")
    if not (runtime / "legal/java.base/LICENSE").exists():
        raise ValueError("Runtime distribution must retain its license notices")


def archive_filter(info):
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.pax_headers = {}
    info.mode = 0o755 if info.isdir() or info.mode & 0o111 else 0o644
    return info


def resolve_agent_releases(supplied=None):
    if supplied is not None:
        return supplied.resolve()
    base = ROOT / "artifacts/agent-releases"
    version = (base / "latest.txt").read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}", version):
        raise ValueError("Invalid Agent version in latest.txt")
    directory = (base / version).resolve()
    if directory.parent != base.resolve():
        raise ValueError("Latest Agent release escapes the release directory")
    return directory


def include_agents(image, directory):
    version = directory.name
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,63}", version):
        raise ValueError("Invalid Agent release directory name")
    manifest_path = directory / "release.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise ValueError("Source Agent release.json is missing or not a regular release file")
    manifest = json.loads(manifest_path.read_text())
    platforms = {(system, arch) for system in ("windows", "linux", "darwin", "synology")
                 for arch in ("amd64", "arm64")}
    if not isinstance(manifest, dict) or manifest.get("version") != version:
        raise ValueError("Agent package manifest version mismatch")
    assets = manifest.get("assets")
    if not isinstance(assets, list) or len(assets) != len(platforms):
        raise ValueError("Agent package manifest must contain all eight supported platforms")
    seen, filenames, releases = set(), set(), {}
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("Invalid Agent manifest asset")
        system, arch = asset.get("platform"), asset.get("arch")
        if not isinstance(system, str) or not isinstance(arch, str):
            raise ValueError("Invalid Agent platform or architecture")
        platform = (system, arch)
        if platform not in platforms or platform in seen:
            raise ValueError("Agent manifest contains an unsupported or duplicate platform")
        seen.add(platform)
        stem = f"syncsrv-agent-{system}-{arch}"
        binary = stem + (".exe" if system == "windows" else "")
        package_name = stem + (".zip" if system == "windows" else ".spk" if system == "synology" else ".tar.gz")
        if asset.get("filename") != binary or asset.get("packageName") != package_name:
            raise ValueError("Agent manifest contains an unexpected binary or package path")
        for name, digest_key, size_key in ((binary, "sha256", "size"),
                                           (package_name, "packageSha256", "packageSize")):
            if name in filenames:
                raise ValueError("Agent manifest contains duplicate filenames")
            filenames.add(name)
            payload, checksum = directory / name, directory / (name + ".sha256")
            if any(not path.is_file() or path.is_symlink() for path in (payload, checksum)):
                raise ValueError(f"Source Agent release missing or unsafe: {name}; run scripts/build-agents.py")
            digest, size = asset.get(digest_key), asset.get(size_key)
            if (not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest)
                    or type(size) is not int or size <= 0 or payload.stat().st_size != size):
                raise ValueError(f"Agent manifest size or digest is invalid: {name}")
            parts = checksum.read_text().split()
            if len(parts) != 2 or parts[0] != digest or parts[1] != name or sha256(payload) != digest:
                raise ValueError(f"Source Agent checksum mismatch: {name}")
        releases[binary] = asset["sha256"]
    # Validate everything before creating a distributable image.
    target = image / "agent-releases" / version
    target.mkdir(parents=True)
    for name in sorted(filenames):
        for suffix in ("", ".sha256"):
            shutil.copyfile(directory / (name + suffix), target / (name + suffix))
    shutil.copyfile(directory / "release.json", target / "release.json")
    (target.parent / "latest.txt").write_text(version + "\n")
    return {"version": version, "assets": releases}


def include_application_notices(image):
    sources = {name: ROOT / name for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md')}
    for name in ('ui-third-party.txt', 'react-bits.txt'):
        sources['licenses/' + name] = ROOT / 'Frontend/public/licenses' / name
    for name, source in sources.items():
        if source.is_symlink() or not source.is_file() or not source.stat().st_size:
            raise ValueError('Required application license notice missing: ' + name)
    for name, source in sources.items():
        target = image / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def verify_application_notices(app):
    required = ('META-INF/LICENSE', 'META-INF/NOTICE', 'META-INF/THIRD_PARTY_NOTICES.md',
                'META-INF/licenses/ui-third-party.txt', 'META-INF/licenses/react-bits.txt',
                'BOOT-INF/classes/static/licenses/ui-third-party.txt',
                'BOOT-INF/classes/static/licenses/react-bits.txt')
    for notice in required:
        if notice not in app.namelist():
            raise ValueError('JAR is missing a required license notice: ' + notice)
        source = (ROOT / 'Frontend/public/licenses' / Path(notice).name if '/licenses/' in notice
                  else ROOT / Path(notice).name)
        if app.read(notice) != source.read_bytes():
            raise ValueError('JAR license notice is stale: ' + notice)


def package(jar, runtime_archive, output, lock, agents, verification, repository=None, allow_http_origin=None):
    version = ET.parse(ROOT / "Backend/pom.xml").getroot().find(
        "{http://maven.apache.org/POM/4.0.0}version"
    ).text
    identity=release_gate.evidence(jar,verification)
    verify(json.loads((agents/"release.json").read_text()),json.loads((ROOT/"Agent/release-trust.json").read_text()))
    with zipfile.ZipFile(jar) as app:
        properties = app.read("META-INF/maven/com.oadig/syncsrv/pom.properties").decode()
        if f"version={version}\n" not in properties:
            raise ValueError("JAR version differs from pom.xml; rebuild the application first")
        names = app.namelist()
        if not any(n.startswith("BOOT-INF/lib/h2-") for n in names):
            raise ValueError("The application must contain its embedded H2 driver")
        if any("mysql" in n.lower() or "mariadb" in n.lower() for n in names):
            raise ValueError("Legacy database dependencies are not allowed in an H2-only release")
        config = app.read("BOOT-INF/classes/application.yml").decode()
        if any(value in config.lower() for value in ("datasource:", "jdbc:mysql:", "syncsrv_database_", "syncsrv_db_")):
            raise ValueError("Legacy datasource configuration is not allowed in an H2-only release")
        if "BOOT-INF/classes/static/index.html" not in app.namelist():
            raise ValueError("Build the frontend before packaging")
        verify_application_notices(app)
    name = f"syncsrv-{version}-linux-x64"
    output.mkdir(parents=True, exist_ok=True)
    if (output / f"{name}.tar.gz").exists():
        raise ValueError("Published center versions are immutable; increment release version")
    with tempfile.TemporaryDirectory(prefix=".syncsrv-package-", dir=output) as work:
        staging = Path(work)
        unpack = staging / "unpack"
        unpack.mkdir()
        runtime = extract_runtime(runtime_archive, unpack)
        validate_runtime(runtime, lock)
        image = staging / name
        shutil.copytree(TEMPLATE, image)
        runtime.rename(image / "runtime")
        (image / "app").mkdir()
        shutil.copyfile(jar, image / "app/syncsrv.jar")
        include_application_notices(image)
        shutil.copyfile(ROOT / "docs/Linux安装包.md", image / "INSTALL.md")
        shutil.copyfile(ROOT / "docs/研发部署升级闭环.md", image / "OPERATIONS.md")
        shutil.copyfile(ROOT / f"docs/发行说明-{version}.md", image / "RELEASE-NOTES.md")
        source_agents = include_agents(image, agents)
        release = {
            "product": "SyncSrv", "version": version, "target": "linux-x64",
            "sourceSha256": identity["sourceSha256"], "schemaMin":1,"schemaMax":1,"rollbackMinimumVersion":"0.4.0",
            "java": lock, "applicationSha256": sha256(image / "app/syncsrv.jar"),
            "sourceAgents": source_agents,
        }
        (image / "release.json").write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n")
        for path in image.rglob("*"):
            if path.is_file() and not path.is_symlink():
                mode = 0o755 if path.suffix == ".sh" or os.access(path, os.X_OK) else 0o644
                path.chmod(mode)
        checksums = [
            f"{sha256(path)}  {path.relative_to(image).as_posix()}\n"
            for path in sorted(image.rglob("*")) if path.is_file()
        ]
        (image / "SHA256SUMS").write_text("".join(checksums))
        temporary_archive = staging / f"{name}.tar.gz"
        with tarfile.open(temporary_archive, "w:gz", compresslevel=6,dereference=True) as bundle:
            bundle.add(image, arcname=name, filter=archive_filter)
        final = output / temporary_archive.name
        temporary_archive.replace(final)
        digest = sha256(final)
        (output / f"{final.name}.sha256").write_text(f"{digest}  {final.name}\n")
        (output / f"{name}.release.json").write_text(json.dumps(release, indent=2) + "\n")
        import datetime
        now=datetime.datetime.now(datetime.timezone.utc)
        signed={**release,"schema":1,"channel":"stable","issuedAt":now.isoformat().replace('+00:00','Z'),
                "expiresAt":(now+datetime.timedelta(days=365)).isoformat().replace('+00:00','Z'),
                "asset":{"url":os.environ.get("SYNCSRV_RELEASE_BASE_URL","https://release.example.invalid")+"/"+final.name,"size":final.stat().st_size,"sha256":digest}}
        if repository:
            import sys
            sys.path.insert(0,str(TEMPLATE/'bin'))
            from release_source import validate_url
            validate_url(repository,allow_http_origin)
            if __import__('urllib.parse',fromlist=['urlsplit']).urlsplit(repository).path not in ('','/'):
                raise ValueError('RelsHub repository must be a site origin')
            signed['distribution']='relshub-v1'
            signed['asset']={'repository':repository.rstrip('/'),'name':final.name,'size':final.stat().st_size,'sha256':digest}
        (output/f"{name}.signed.json").write_text(json.dumps(sign(signed),indent=2)+'\n')
        print(f"Created: {final}")
        print(f"Size: {final.stat().st_size / 1024**2:.2f} MiB")
        print(f"Bundled Java: {lock['vendor']} {lock['version']} (Linux x64)")
        print(f"SHA-256: {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification",type=Path,required=True,help="Exact source/JAR verification evidence, at most 24 hours old")
    parser.add_argument("--jar", type=Path, default=ROOT / "Backend/target/syncsrv.jar")
    parser.add_argument("--runtime-archive", type=Path, help="Use an offline archive matching the lock")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--agent-releases", type=Path,
                        help="Explicit Agent release directory; defaults to agent-releases/latest.txt; all eight platforms are required")
    parser.add_argument("--repository-url",help="Bind native metadata to a RelsHub site origin")
    parser.add_argument("--allow-http-origin",help="Explicit intranet validation origin")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("JAR not found; run sh scripts/build.sh first")
    lock = json.loads((TEMPLATE / "runtime.lock.json").read_text())
    runtime = get_runtime(lock, args.runtime_archive)
    package(args.jar, runtime, args.output.resolve(), lock, resolve_agent_releases(args.agent_releases),args.verification,args.repository_url,args.allow_http_origin)


if __name__ == "__main__":
    main()
