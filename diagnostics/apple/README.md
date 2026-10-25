# Native Files diagnostic

This standalone simulator probe builds only UIKit/Swift/XCTest. It reuses the
existing `testFileProviderNativeControl` without changing its cancellation,
reopen, original one-second source, selection callback or three SHA-256 checks.
The product's five iOS cases and media-cleanup guards remain mandatory.

On an Apple Silicon Mac with Xcode, run:

```sh
bash diagnostics/apple/run-file-provider-probe.sh
```

The separate Actions workflow uses the same current macOS/Xcode and latest
installed iOS runtime. Each probe owns one fresh device and shuts it down on exit;
it never resets the app between cancellation and reopening.
XCTest targets an empty native host and launches the reader as a separate app,
matching the full suite's control topology without building Kotlin.

Evidence includes native XCTest results/screenshots, source hashes/inodes, host
and Simulator Foundation file-reference URL round trips, and read-only scalar
metadata from LocalStorage provider databases in this new test simulator.
The probe also records filesystem type, mount/device identities, public
`fsgetpath` inode lookups, kernel/Foundation document IDs, and path symlinks.
These observations do not replace a successful picker callback and never repair
or rewrite the provider's storage.
Raw SQLite files, binary bookmarks and tokens are not uploaded. The source and
provider database are not modified by diagnostics. This is CI tooling, not an
app backend or a replacement for full KMP validation.
