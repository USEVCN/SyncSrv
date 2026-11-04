# Contributing

Use Java 17, Node 22.12 or later, Python 3.9 or later, Maven 3.9, and the Go version pinned in `packaging/go-toolchain.lock.json`. Do not commit data, signing credentials, service logs, personal deployment configuration, downloaded installers or generated native binaries.

1. Run `python3 scripts/build-native-io.py --host-only` before backend development. Omit `--host-only` to build the six center platforms. This bridge uses only the Go standard library; it requires no service installation, enrollment or publisher key.
2. Run `npm ci`, `npm run check:licenses`, `npm test` and `npm run build` under `Frontend`.
3. Run `python3 scripts/release_gate.py stamp` from the repository root after the frontend build, then `mvn verify` under `Backend`. Tests create disposable fixtures; do not point tests at real backup roots. Run `go test -race ./...` under `NativeIO` and `Agent` on supported hosts.
4. Run `python3 scripts/check-source.py` and the Python `scripts/test-*.py` checks relevant to your change. After staging, run `python3 scripts/public-source-check.py`.

UI dependencies are version- and notice-hash-reviewed in `licenses/ui-reviewed.json`. A new package, version or changed license must be reviewed before updating this allowlist. Never remove upstream notices to pass a check. React Bits adaptations retain their separate license and must not be extracted as a reusable component product.

Contributions must be your own work or have documented compatible permission. Original project contributions are supplied under Apache-2.0 unless a file states a retained third-party license. The logo and product names are not a grant of trademark rights. Do not submit copied proprietary code, third-party customer material, private keys or test data from actual deployments.

Official installer signing and publication remain separate from development builds. Contributors should use their own test signing identity and must never request or receive the project's publisher private key. A development binary must not be advertised as an official trusted release.
