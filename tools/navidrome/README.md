# Testing against a real Navidrome

These scripts build Navidrome from source, fill it with made-up music (invented
artists and albums, made with ffmpeg) and set it up like the car's server: user
`me` / `secret`, and the car's player transcoding to Opus 128k.  The app's tests
in `RealNavidromeTest` then run against it, through a relay that can break the
connection.  Without a server those tests are skipped, so the release workflow
doesn't need one.

```
tools/navidrome/build.sh ~/nd      # Navidrome 0.64.2 from source (needs git, Go, gcc)
tools/navidrome/start.sh ~/nd      # in its own terminal; Ctrl-C stops it
cd tools/buildcheck
NAVIDROME_URL=http://127.0.0.1:4533 gradle :app:testDebugUnitTest --rerun --tests '*RealNavidromeTest*'
```

- Start the server again before each run: its transcoding cache starts empty,
  so songs stream as they're transcoded, as they do the first time in the car
  (a song played again comes from that cache, with its length).
- `--rerun`: Gradle doesn't notice `NAVIDROME_URL`, and would otherwise report
  the last results without running anything.
- `NAVIDROME_LOG=1` prints the app's log; `NAVIDROME_USER` / `NAVIDROME_PASSWORD`
  for another login; `NAVIDROME_DUMP=<dir>` keeps the songs compared.
- Navidrome's `Transcoding.MaxConcurrentPerUser` is off by default.  At 1, the
  player and the look-ahead take turns (each waits when told "too many
  transcodes"), so songs can start late: 2 or more suits the app.
