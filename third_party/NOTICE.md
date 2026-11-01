# Third-party notices

## Modifications

Modifications to files derived from the upstream repository below are listed in
`upstream.json`; the remainder of this repository is original code. The
modified work is distributed under Apache-2.0.
Copyright for the modifications: ZcodeRemote contributors.

## zai-org/ZCode — packages/android

- Source: https://github.com/zai-org/ZCode (`packages/android`)
- Upstream commit: `3c5b227649371507ddc4eb46ac5133afe6db1cab` (2026-10-20)
- License: Apache-2.0 (repository root `LICENSE`, Copyright 2026 Z.AI Co., Ltd)
- Used as the application shell base (Gradle setup, theme, launcher icons,
  status-bar drawable, ML Kit scan flow). Package renamed to
  `com.zcodeapp.remote`; substantial modifications listed in `upstream.json`.
- Apache-2.0 compliance: this distribution retains the upstream `LICENSE`
  (repository root) and this NOTICE. The upstream repository's `NOTICE.md`
  documents product features and data-handling statements; it contains no
  attribution lines applicable to the files copied here, so it is not
  reproduced in this file. Modified files are enumerated in `upstream.json`.

## Reference-only projects (no code copied)

The following community repositories were consulted for protocol knowledge and
behavior verification only. No source files were copied into this project:

- `HumanAILoop/zemote` (MIT) — IPC value codec and rpc transport semantics
- `pjpv/zremote`, `my-dlq/zcode-mobile-app`, `Enc-hanted/zcode-remote`,
  `245678000000/ZCode-Mobile`, `Damianjiang/ZCode-Android`,
  `woshihoujinxin/zcode-mobile-android` — event semantics, WebView lifecycle,
  notification transaction patterns

Repositories without a root LICENSE file were treated as read-only references;
no code from them was copied.

## Runtime dependencies (resolved via Gradle, licenses at build time)

- AndroidX (core-ktx, activity-compose, Compose UI/Material3, lifecycle,
  CameraX, webkit) — Apache-2.0
- Google ML Kit barcode-scanning — distributed under the ML Kit Terms of
  Service (https://developers.google.com/ml-kit/terms), not Apache-2.0
- kotlinx-coroutines (transitive) — Apache-2.0

A dependency license report should be generated for release builds; unknown or
incompatible terms block the release per the design document decision tree.
