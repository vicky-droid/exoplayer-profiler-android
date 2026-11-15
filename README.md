# ExoPlayer Playback Profiler

Records one JSON file per playback session from an ExoPlayer instance. The files show startup
time (VST), network chunks, ABR switches, rebuffers, dropped frames, seeks, decoder init, and
ad-break tags, with aggregates. Everything stays on the device. Its only runtime dependency is
Media3 ExoPlayer.

## Modules

- `profiler/`: the library (`com.exoplayerprofiler`).
- `demo/`: an app that plays a DASH stream with the profiler attached and has an "Ad break 5 s"
  button for testing ad tags.

## Usage

```kotlin
ExoPlayerProfiler.install(context, ProfilerConfig(enabled = BuildConfig.DEBUG))
ExoPlayerProfiler.attach(player)          // when the ExoPlayer is created
ExoPlayerProfiler.markAdBreakStart()      // when an ad break starts (ads not played by this ExoPlayer)
ExoPlayerProfiler.markAdBreakEnd()        // when the ad break ends
ExoPlayerProfiler.reportCustomEvent("button_play")
ExoPlayerProfiler.setSessionAttribute("userTier", "gold") // written to the session header; null removes it
ExoPlayerProfiler.markPlayClick()         // user pressed play: start of the VST measurement
ExoPlayerProfiler.markAuthStart()         // optional: auth timing, part of the VST breakdown
ExoPlayerProfiler.markAuthEnd(durationMs = 120)
ExoPlayerProfiler.markPlaybackRequest("content-42", mapOf("quality" to "hd")) // starts a session; id is hashed by the library
ExoPlayerProfiler.beginSession()          // ends the running session (file written) and starts a new one
ExoPlayerProfiler.addSink(mySink)         // ProfilerSink: onEvent(json), onSessionEnded(id, json); writer thread
ExoPlayerProfiler.removeSink(mySink)
ExoPlayerProfiler.detach()                // before player.release()
```

## Output

Files are written to `filesDir/playback_profiler/`:

- `session_<yyyyMMdd_HHmmss>_<id8>.json`: final file, written when the session ends. Keys: `schemaVersion`
  (3), `session` (header with `eventCount`, duration, device, Media3 version, config),
  `events` (sorted by `tMs`), `aggregates`, `hash`. The header also has `attributes`
  (the values from `setSessionAttribute`, `{}` when none).
- `session_<...>.ndjson`: the same header and events, one per line.

Each event has `tMs` (ms since session start), `wallMs`, `thread`, `category`, `event`,
`contentKind` (`CONTENT`, `AD`, or `UNKNOWN`) and `details`.

Old sessions are removed by `ProfilerConfig.retentionSessions` and `retentionBytes`.

## Configuration

See `ProfilerConfig`. The defaults are safe for production. Raw chunk URIs and logcat mirroring
are only enabled with `captureRawLocal = true` in a debuggable build.

## Not included (compared with the Quickplay SDK profiler)

- Live streaming to a desktop viewer over WebSocket, and HTTP upload. Both need a network
  client; the file output is the supported path for now.
- SDK-only report calls: startup observations, common reporting data. (Play click, auth timing and
  playback request are available as `markPlayClick`, `markAuthStart`/`markAuthEnd`, `markPlaybackRequest`.)

## Running the tests

```bash
./gradlew :profiler:testDebugUnitTest
./gradlew :demo:installDebug
```

## Production status

- Retention: enforced after every session end (`retentionSessions`, `retentionBytes`).
- Files: written atomically (temporary file, then rename).
- Threading: public methods and player callbacks share one lock.
- Public API: explicit API mode; only four types are public.
- Build: version catalog in `gradle/libs.versions.toml`, pinned Gradle wrapper checksum, CI in
  `.github/workflows/ci.yml`.

Open items before a public release are listed in `CHANGELOG.md` under "Known gaps". In
particular, a license file and a publication repository are not set up yet.
# exoplayer-profiler
