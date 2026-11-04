# Source publication review · 2026-11-04

This review covers the clean source tree published in this repository. It does not certify historical binary archives, an existing installation, or every NAS/Windows filesystem configuration.

The public tree is generated from explicit source roots. Publisher private keys, real environment files, databases, backups, execution logs, IDE settings and generated binaries are excluded. The exact Git index is scanned by `scripts/public-source-check.py`; an independent Gitleaks 8.30.1 scan found no secrets. Deliberately invalid key-shaped test strings are narrowly documented and are not actual key material.

Original code has Apache-2.0, NOTICE and third-party exceptions. Current npm UI runtime dependencies retain their complete MIT notices. React Bits application effects keep MIT + Commons Clause and are excluded from the root Apache grant. Reviewed asset hashes cover only existing project logo PNGs. Generated native I/O bridges retain the Go BSD notice and are not committed as binary files.

Archive cleanup also keeps a confirmed completed plan terminal, so a stale polling summary cannot re-enable its destructive action. A regression test covers this state reversal.

The local storage race was replaced by a mandatory rooted native I/O bridge. Tests race directory replacement against read, write, delete, move, directory creation and modification time. There is no fallback to the vulnerable Java check-then-open implementation. See the trust boundaries in [SECURITY-MODEL.md](SECURITY-MODEL.md), including root ancestors, hard links, mounts, host administrators and backup-library ownership.

The center uses Spring Boot 4.1.2, Spring Framework 7.0.10, Tomcat 11.0.27 and aligned Bouncy Castle 1.86. Jackson 2 compatibility is retained to preserve existing signed state serialization. Vite is 8.1.6, Vitest 4.1.12 and source-map-js 1.2.2. Go module graph advisories with available fixes were upgraded. A new-user `npm ci` and npm audit completed successfully with no npm vulnerabilities reported.

The full dependency review covers 326 unique package versions, including build dependencies, the full Go module graph, and all 53 JARs actually bundled by the current center. Empty Boot starter JARs are verified as metadata-only instead of silently skipped; the repackage plugin's extra tools JAR is compared with the official Maven Central bytes. There are no unmapped packaged JARs.

One OSV module warning remains as **not affected**, not hidden: `GO-2026-5932` concerns unmaintained OpenPGP packages in x/crypto. None is imported by the Agent on the six Windows/Linux/macOS x64/ARM64 targets. The audit regenerates those import graphs and rejects the exception if any affected package appears. The exception matches the exact version and advisory and expires on 2026-12-04. See `security-vex.json` and `scripts/test-source-dependency-audit.py`.

Local verification includes full frontend tests (143 passed), complete backend regression with three explicit external-environment skips, Agent race tests, native path containment/race tests, 13 Python suites (103 checks), isolated real HTTP startup/login/state/settings/SPA/license delivery, and six native bridge cross-builds. Source size is under 3 MiB before dependency caches. CI independently runs on Linux, macOS and Windows; its actual results must be inspected rather than assumed from cross-compilation.

The skipped backend checks require native Agent end-to-end or real SMB environments. Cross-builds and hosted CI do not replace physical install/upgrade, Windows junction, NAS firmware, disk-full, power-loss or application-consistent restore acceptance. These remain gates for formal binary releases. No unverified historical binary, installer or publisher key is released with this source publication.

No software audit establishes zero unknown risk. New dependencies, notice changes, an expired VEX, unreviewed binaries and secret-bearing Git paths must fail the checks rather than inherit this review.
