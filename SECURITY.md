# Security policy

This repository publishes source code. A source commit or a successful cross-build is not a certified appliance release. Do not treat development builds as official signed updates.

Report a potential vulnerability privately through GitHub's **Security → Report a vulnerability** for this repository when available. If private reporting is unavailable, create an issue containing only a request for a private contact channel. Do not publish exploit details, credentials, customer paths or backup contents in an issue. Do not upload diagnostic bundles without reviewing their contents.

The supported maintenance target is the current `main` source branch. Older local or historical binary versions are not certified by this publication. Updates must be rebuilt and tested from the exact source revision; official binary releases require the separate signed release checks.

See [the security model](docs/SECURITY-MODEL.md) for trust boundaries and [third-party notices](THIRD_PARTY_NOTICES.md) for license exceptions. There is no promise of zero unknown vulnerabilities, universal filesystem compatibility, or application-consistent database snapshots.
