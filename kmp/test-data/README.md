# Local video import fixture

`import-smoke.mp4` is an original generated blue frame sequence: one second, 128×72, 10 fps, H.264 Baseline/yuv420p, no audio. It contains no third-party footage, fonts, music or personal data and is covered by the repository's Apache-2.0 license. The file is test data, not an application resource or bundled codec implementation.

Reproduce with an installed FFmpeg (the application does not run this command):

```sh
ffmpeg -f lavfi -i color=c=blue:s=128x72:r=10:d=1 -an -c:v libx264 -profile:v baseline -pix_fmt yuv420p -movflags +faststart import-smoke.mp4
```

Tests use independent temporary directories/emulator installations. Desktop UI tests inject picker results while using the real file-copy adapter; a separate test opens and cancels the native FileDialog. Android/iOS UI tests use their real system document pickers. Import availability verifies the managed file's presence and byte size; playback/codec analysis is a later milestone.
