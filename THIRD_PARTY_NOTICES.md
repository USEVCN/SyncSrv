# Third-party notices

The root Apache-2.0 license applies to original SyncSrv code. It does not replace or sublicense any third-party license.

## React and React DOM

React 19.2.7 and React DOM 19.2.7 are MIT licensed, copyright Meta Platforms, Inc. and affiliates. Commercial use, modification, redistribution and use in open-source applications are permitted subject to retaining their notices. The complete texts are in `licenses/react-MIT.txt` and `licenses/react-dom-MIT.txt`.

Source: https://github.com/react/react/blob/v19.2.7/LICENSE

## React Bits application UI

The following adapted application UI files retain the React Bits MIT + Commons Clause license, copyright 2026 David Haz. They are excluded from the root Apache-2.0 license:

- `Frontend/src/modules/auth/LoginAurora.tsx`
- `Frontend/src/modules/auth/LoginBorderGlow.tsx`
- `Frontend/src/modules/auth/LoginBorderGlow.css`

The complete license shipped with these adaptations is `Frontend/public/licenses/react-bits.txt`. It must accompany source distributions and built applications. These files are part of SyncSrv's application UI, not a reusable component product. Do not relicense them under Apache-2.0 or extract/repackage them as a standalone UI library, component collection or competing template product.

The maintainer explicitly clarified that adapted component source may remain in a public application/website repository when the original license and exclusion are retained:

- Public source repository: https://github.com/DavidHDev/react-bits/discussions/1111
- Commercial application bundles: https://github.com/DavidHDev/react-bits/discussions/1101
- Upstream license: https://github.com/DavidHDev/react-bits/blob/main/LICENSE.md

This exception covers only the listed free React Bits adaptations. It does not authorize React Bits Pro code. `loginBrandRenderer.ts` is a local implementation, not React Bits Pro source.

MIT + Commons Clause is not the standard MIT license. Do not describe every file in this repository as Apache-2.0 or as permissively licensed without this exception. The retained license text matches upstream commit `ecdd797468fc87f7b463157627d1174b0c36fdf5`; its exact hash and review evidence are recorded in `licenses/ui-reviewed.json`.

## Dependency distributions

Maven, npm and Go dependencies retain their own copyright and license terms. Their notices are collected by the dependency inventory for each release. Bundled Java runtimes retain the vendor's `legal` directory and license documents. A release must not claim all packaged dependencies are Apache-2.0.

## UI dependency notice bundle

Run `python3 scripts/ui-licenses.py` after `npm ci`. The generated `licenses/ui-inventory.json` records exact versions, terms, evidence and notice hashes. `Frontend/public/licenses/ui-third-party.txt` contains complete license texts and is included in the built application at `/licenses/ui-third-party.txt`. `licenses/ui-reviewed.json` is the reviewed allowlist: new coordinates or changed notice text fail the build, including new packages declaring MIT, until reviewed. Missing notices or unknown licenses also fail. Current npm runtime UI dependencies are MIT licensed; there is no per-seat fee or runtime royalty in those terms.

The published `@ant-design/icons-svg` package omits a full license file; its MIT text is retained under `licenses/upstream/`, pinned to the package registry git commit. `is-mobile` includes its complete MIT license inside README.md; that section is included in the generated notice bundle. Neither notice is silently omitted.

## Go native local I/O bridge

The bridge is original project code using only the Go standard library. Bundled bridge executables retain the Go BSD-3-Clause notice in `licenses/go-BSD-3-Clause.txt` and JAR metadata. Generated binaries are excluded from the source repository. The bridge does not include Agent service credentials or publisher keys.
