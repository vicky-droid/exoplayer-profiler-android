# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Session retention: after each session, the oldest session files are deleted to honour
  `ProfilerConfig.retentionSessions` and `retentionBytes`. Open sessions are never deleted.
- Atomic session file writes: files are written to a `.tmp` file and renamed, so a crash or a full
  disk never leaves a truncated session file under its final name.
- Thread safety: public methods and ExoPlayer listener callbacks share one lock.
- Explicit API mode. Only `ExoPlayerProfiler`, `ProfilerConfig`, `CaptureLevel` and `ProfilerSink`
  are public; everything under `internal` is hidden from consumers.
- Public API: `setSessionAttribute`, `markPlayClick`, `markAuthStart`, `markAuthEnd`,
  `markPlaybackRequest`, `beginSession`, `addSink`, `removeSink`, `markAdBreakStart`,
  `markAdBreakEnd`, `reportCustomEvent`.
- Version catalog (`gradle/libs.versions.toml`), pinned Gradle wrapper checksum, and a GitHub
  Actions workflow that runs the unit tests and builds the demo.
- A `release` Maven publication with a sources jar. It is not yet published to a repository.

### Known gaps
- No JitPack release tag has been pushed yet; consumers can resolve the library after a tagged
  build succeeds on JitPack.
- The writer thread is not torn down on `detach()`; it lives for the process.
- In-memory event buffering per session is not capped.

## [0.1.0] - 2026-11-14

### Added
- First standalone release: ExoPlayer session capture to on-device JSON files (schema v3).
