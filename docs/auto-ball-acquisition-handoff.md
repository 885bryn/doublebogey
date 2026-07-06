# Auto Ball Acquisition Handoff

> **2026-07-05 update:** the calibrated-background detector below false-locked at the driving
> range (6 px speck; real ball produced no candidate). An interim patch lives on
> `codex-surface-agnostic-ball-detection` (minArea 6→20, abs(dY) foreground, dark-chroma guard),
> but the detection approach is being replaced — see
> `docs/superpowers/specs/2026-07-05-surface-agnostic-acquisition-and-tracking.md`. The "Next
> Work" list below is superseded by that spec's Phase 1–5 plan; do not invest further in
> background-model tuning or paired empty-mat/ball calibration imagery.

Branch: `codex-auto-ball-acquisition-redesign`

## Current State

- Automatic acquisition now uses `ZoneBallDetector` and `ZoneMotionMeter`.
- The detector builds a per-pixel launch-zone background model with mean and sigma, then accepts bright compact foreground components by significance, shape, relative chroma shift, and runner-up margin.
- `AutoShotTracker` now owns the lifecycle: calibrating, searching, ball locked, tracking, reviewing, then automatic re-arm.
- Camera repeating requests now set AE/AWB lock and fixed-FPS mode ranking prefers `30-30fps` when max FPS ties.
- Uploaded sample photos were copied to `docs/reference-images/` for handoff context.

## Verified

- `rtk ./gradlew.bat testDebugUnitTest --tests "com.doublebogey.golftracer.camera.*"`
- `rtk ./gradlew.bat assembleDebug`

## Next Work

- Collect paired reference imagery: same camera position and lighting, one empty launch-zone frame and one ball frame. The current uploaded ball photos help with context but cannot measure calibrated `dY` without matching empty frames.
- Turn paired crops into golden tests for real mat/ball conditions.
- Tune `ZoneBallDetectorConfig` from measured crops: `minYDelta`, `significanceMultiplier`, `minArea`, `maxArea`, `chromaShiftTolerance`, and `runnerUpMargin`.
- Finish crop-based live acquisition wiring. `YuvFrameExtractor.extractCrop` exists and is tested, but `CameraCaptureController` still feeds full-frame `YuvFrame` into the acquisition path.
- Add the foreground-mask overlay/dump path described in the spec. Current overlay uses ranked candidates/lock/track, but not the tinted mask bitmap yet.
- After phone testing, consider deleting or fully replacing legacy `StillBallScorer` / `LumaStillBallDetector` once no rollback path is needed.
