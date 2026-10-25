# Native Files diagnostic

This standalone simulator probe builds only UIKit/Swift/XCTest. It reuses the
existing `testFileProviderNativeControl` without changing its cancellation,
reopen, original one-second source, selection callback or three SHA-256 checks.
The product's five iOS cases and media-cleanup guards remain mandatory.
The diagnostic build replaces XCTest `.firstMatch` with the public
`.element(boundBy: 0)` full-query path. This controlled query experiment investigates
remote Files elements absent from fast queries but present in complete snapshots;
`query-mode.json` records the change. Product test sources remain untouched.
Navigation uses one native OR query for readiness rather than invoking several
remote queries inside an XCTest predicate closure. It waits for the source folder
while On My iPhone enumerates, instead of navigating back during that load.

On an Apple Silicon Mac with Xcode, run:

```sh
bash diagnostics/apple/run-file-provider-probe.sh
```

The separate Actions workflow uses the same current macOS/Xcode and latest
installed iOS runtime. Each probe owns one fresh device and shuts it down on exit;
it never resets the app between cancellation and reopening.
Set `MONTAGE_PROBE_PRE_SUITE_RESTART=1` to repeat the optional registration
experiment: restart the same device once after preparing sources, before XCTest.
It verifies the unchanged container and three hashes; `simulator.json` records it.
No restart occurs within a test case. This experiment timed out in navigation in
run 40218813221; it has not established a fix for URL delivery.

The workflow compares two fresh devices on separate jobs: the unchanged native
source producer, and `MONTAGE_PROBE_TRACK_IDENTITIES=1`. The latter uses public
Darwin `fchflags(UF_TRACKED)` and `fsync` on the source app's own three files and
requires nonzero document IDs before publishing readiness. It preserves inode,
filename and payload bytes. `source-identity-mode.json` records the generated
producer hashes; original product/source code remains untouched. This tests
whether preparing persistent identity before enumeration affects URL delivery.
XCTest targets an empty native host and launches the reader as a separate app,
matching the full suite's control topology without building Kotlin.

Evidence includes native XCTest results/screenshots, source hashes/inodes, host
and Simulator Foundation file-reference URL round trips, and read-only scalar
metadata from LocalStorage provider databases in this new test simulator.
The probe also records filesystem type, mount/device identities, public
`fsgetpath` inode lookups, kernel/Foundation document IDs, and path symlinks.
These observations do not replace a successful picker callback and never repair
or rewrite the provider's storage.
Raw SQLite files, binary bookmarks and tokens are not uploaded. Provider databases
remain read-only. The tracked producer changes only its own fixture identity
metadata; source payloads are checked before and after testing. This is CI tooling, not an
app backend or a replacement for full KMP validation.
