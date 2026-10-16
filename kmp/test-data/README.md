# Local video import fixture

`import-smoke.mp4` is an original generated blue frame sequence: one second, 128×72, 10 fps, H.264 Baseline/yuv420p, no audio. It contains no third-party footage, fonts, music or personal data and is covered by the repository's Apache-2.0 license. The file is test data, not an application resource or bundled codec implementation.

Reproduce with an installed FFmpeg (the application does not run this command):

```sh
ffmpeg -f lavfi -i color=c=blue:s=128x72:r=10:d=1 -an -c:v libx264 -profile:v baseline -pix_fmt yuv420p -movflags +faststart import-smoke.mp4
```

Tests use independent temporary directories/emulator installations. Desktop UI tests inject picker results while using the real file-copy adapter; a separate test opens and cancels the native FileDialog. Android/iOS UI tests use their real system document pickers. Import availability verifies the managed file's presence and byte size; separate preview tests verify decoding.

`preview-smoke.mp4` is an original eight-second 160×90, 10 fps H.264 Baseline/yuv420p fixture: four seconds of blue, then four seconds of red, with a quiet synthetic 440 Hz AAC tone. SHA-256: `e894398d60b53b9370143cba19da5d35b61d72393e55b984bf3f88a98af26c1a`. It contains no third-party footage or music and has the same Apache-2.0 test-data terms. Real frame colors and positions distinguish decoding/seeking from merely constructing a player.

```sh
ffmpeg -f lavfi -i color=c=blue:s=160x90:r=10:d=4 -f lavfi -i color=c=red:s=160x90:r=10:d=4 -f lavfi -i sine=frequency=440:sample_rate=44100:duration=8 -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v];[2:a]volume=0.03[a]' -map '[v]' -map '[a]' -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 10 -c:a aac -b:a 64k -movflags +faststart -shortest preview-smoke.mp4
```

The iOS script also seeds a separate `.mp4` containing only `Invalid video fixture` text to exercise recoverable decode errors. Android/desktop corrupt a same-size managed copy, then restore the generated fixture to verify Retry without changing project metadata. These operations affect only isolated test installations/directories.
