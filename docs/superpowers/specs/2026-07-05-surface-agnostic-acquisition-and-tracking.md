# Surface-Agnostic Ball Acquisition and Tracking

Status: proposed. Supersedes the acquisition method (§3) of
`2026-07-03-auto-ball-acquisition-redesign.md`. That spec's camera control (AE/AWB lock),
coordinate-mapper rule (§1d), `AutoShotTracker` lifecycle shape, and debug-telemetry philosophy
are retained. Its calibrated-background detection model is replaced entirely.

Goal: acquire and track the ball in **all** field conditions — ball brighter or darker than the
surface, grass/mat/sand/concrete, hard sun or shade, partial occlusion by grass blades —
comparable to commercial shot-tracer apps.

## 1. Why the calibrated-background model cannot get there

Field evidence, 2026-07-05 driving-range session (screenshot + telemetry archived):

```
shot=BallLocked ... fg=0.1% best={s=7.9 area=6 fill=0.67 asp=1.00 dc=0.2} margin=Infinity
```

The detector locked a 6-pixel static speck on the tree line. `margin=Infinity` means it was the
**only** candidate — the real ball, plainly visible on the mat inside the zone, produced *no
candidate at all*. Raising `minArea` (done as an interim patch on
`codex-surface-agnostic-ball-detection`: minArea 6→20, `abs(dY)` foreground, dark-chroma guard)
converts this false lock into *no lock*; it does not make the ball detectable.

The architectural flaw: `ZoneBallDetector` compares pixels against a **calibrated absolute
background**, so it can only see "things that changed since calibration." Every observed and
predicted failure mode follows from that:

- **Ball baked into the background.** Calibrate with the ball already placed → the ball has zero
  delta forever and is invisible. Nothing guards against this, and it is the most likely
  explanation for "no candidate" at the range.
- **Absolute thresholds vs. lighting.** Foreground needs `dY ≥ max(18, 4σ)` (up to 48 counts with
  σ capped at 12). A white ball on a bright sunlit mat, or any AE drift between calibration and
  detection, can fail this even with AE locked.
- **Contrast-sign assumptions.** Positive-only misses dark balls; the interim `abs(dY)` fix
  introduces a new regression: in hard sun the ball's own shadow becomes foreground adjacent to
  the ball, flood-fill merges ball+shadow into one non-compact component, and the shape gates
  reject it. The bright-ball-in-sun case — the most common case — gets *worse*.
- **Weak shadow discrimination.** `minDarkChromaShift = 4` is porous: outdoor shadows are not
  chroma-neutral (sky-lit shadows shift blue; on green turf mean |dU|+|dV| of 4 is easy), while a
  genuinely shadowed ball on neutral concrete has ~0 chroma shift and is rejected as "shadow."
- **Staleness flapping.** `abs(dY)` roughly doubles foreground events; moving background inside
  the zone (trees, netting — exactly what the range screenshot shows) trips the 35% stale guard
  and silences the detector.
- **Hardcoded pixel-area gates.** `minArea = 20` at 1280×720 is borderline for a real ball with
  the phone a few meters back (~5 px diameter ≈ area 20). Resolution- and distance-dependent
  constants cannot be tuned once and work everywhere.

No amount of threshold tuning fixes a detector whose reference frame is "the scene at calibration
time." The replacement below needs **no calibration** and uses only lighting-independent,
contrast-sign-independent cues.

## 2. Design principles

1. **Two problems, two detectors.** Still-ball acquisition is an *appearance* problem (small,
   round, uniform blob that stays put). Flight tracking is a *motion* problem (fastest object in
   frame, motion-blurred streak, ballistic path). Do not share a pipeline between them.
2. **Local, relative, sign-invariant measurements only.** Every acquisition cue compares the ball
   to its *immediate surroundings in the same frame* — never to a stored background, never to an
   absolute level, never assuming brighter-than or darker-than.
3. **Permissive per-frame, strict over time.** Single-frame evidence proposes; temporal
   persistence disposes. The ball is the one blob whose centroid holds still for dozens of frames
   while grass jiggles, shadows crawl, and range balls roll by.
4. **Scale from geometry, not constants.** Expected ball diameter in pixels is derived from the
   launch-zone size; every area/radius gate is expressed relative to it.
5. **One active hypothesis with prediction.** After launch, detect only inside small crops whose
   location a Kalman filter predicts (per arXiv:2012.09393, "Efficient Golf Ball Detection and
   Tracking Based on CNNs and Kalman Filter" — full-frame search for a golf-ball-sized object is
   both wasteful and error-prone). Multiple inconsistent candidates ⇒ refuse, don't guess.
6. **Deterministic first, learned second.** Classical vision is the proposer and must work
   standalone (~90% of conditions). A small learned crop verifier is a planned *addition* gated
   on our own field data — not a prerequisite, and not a dependency on third-party models.

## 3. Still-ball acquisition (replaces §3.2–3.5 of the 2026-07-03 spec)

All processing on the launch-zone Y-plane crop, view↔frame mapping via `FrameCoordinateMapper`
as before. No calibration state, no background model, no "keep the box clear" phase. AE/AWB lock
is kept (stability still helps) but is no longer load-bearing.

### 3.1 Expected ball scale

```
expectedDiameterPx r_e = zoneWidthPx × (BALL_DIAMETER_MM / assumedZoneWidthMM)
```

with `BALL_DIAMETER_MM = 42.7` and `assumedZoneWidthMM` defaulting to a hitting-mat width
(~1500 mm; make it a config value, later refinable from user setup). Clamp `r_e/2` (radius) to
[2, 40] px. All subsequent gates are multiples of this: candidate radius in [0.5×, 2×],
area in [0.3×, 3×] of the expected disc area.

### 3.2 Blob proposal — sign-invariant Difference-of-Gaussians

- Compute DoG response over the zone crop at 3 scales bracketing `r_e` (σ ≈ r_e/2 × {0.75, 1.0,
  1.4}); separable Gaussian passes on the crop only, integer/fixed-point is fine.
- Take **|response|** — a compact blob that differs from its surroundings in either direction
  scores identically. This removes the bright/dark asymmetry at the root.
- Non-max suppression over space and scale → up to ~8 proposal centers per frame. Permissive by
  design; downstream cues and persistence do the rejecting.

### 3.3 Per-candidate scoring (all relative, all in-frame)

For each proposal at center c, radius r (from the winning DoG scale):

1. **Annulus contrast** `C = |mean(Y_disc) − mean(Y_annulus)| / max(σ_annulus, σ_floor)` where
   the disc is radius r and the annulus spans [1.3r, 2.2r]. Self-normalizing: dawn, noon glare,
   and overcast move disc and annulus together. Gate `C ≥ ~2.5`.
2. **Interior uniformity** `U = σ_disc / max(σ_annulus, σ_floor)`. A ball interior is smooth;
   grass texture is not. Gate `U ≤ ~0.8`.
3. **Edge circularity** — RANSAC circle fit on gradient-magnitude maxima in [0.7r, 1.3r]:
   inlier fraction `E` of the fitted circle's circumference that has supporting edge points.
   Gate `E ≥ ~0.5`. This is the occlusion answer: grass blades covering part of the ball kill a
   fill-ratio test but leave 60% of the rim fittable. **Do not use bounding-box fill ratio.**
4. **Chroma sanity (weak veto only)** — mean |U−128|+|V−128| of the disc vs. the annulus, from
   the subsampled U/V planes. Veto only *strongly* colored blobs (relative shift > ~45, as
   before). Balls may be white or yellow; this must stay permissive.

Frame score `q = C × E × (1 − U)` (any monotone combination; unit tests pin behavior, field
tuning adjusts). Emit all gated candidates with q, center (view space), and r.

### 3.4 Persistence voting → lock (replaces stableFramesRequired-on-a-fragile-detector)

- Maintain a decaying accumulator over zone positions (grid at ~r_e/2 resolution, decay ~0.9 per
  frame). Each frame, every gated candidate deposits q at its cell.
- **Lock** when one cell's accumulated score exceeds a threshold equivalent to ~15–20 solid
  frames **and** exceeds the runner-up cell by ≥ 1.5× (keep the margin rule — it's good).
  Ambiguity ⇒ keep searching; never guess between two balls.
- **Lock maintenance**: while locked, re-verify each frame near the lock (± ~r_e). Candidate
  missing/far for ~2 s → unlock to Searching (unchanged from current tracker behavior).
- This subsumes both `stableFramesRequired` and the stale-background guard: there is no
  background to go stale, and transient junk (rolling range balls, feet, clubheads) never
  accumulates.

### 3.5 What is deleted

- `ZoneBackgroundModel`, calibration accumulation, `collectCalibrationFrame`, quiet gating /
  `ZoneMotionMeter`'s calibration role, `staleForegroundFraction`, `minYDelta`,
  `significanceMultiplier`, per-pixel foreground mask + despeckle + flood fill,
  `minDarkChromaShift`, absolute `minArea`/`maxArea`.
- The "Calibrate mat" button becomes unnecessary for acquisition; keep it temporarily as a
  detector reset, remove from UX once the new path is field-proven.
- The interim patch on `codex-surface-agnostic-ball-detection` (minArea 20 / abs(dY) / dark
  chroma guard) ships as-is for the next session but is superseded by this section; do not build
  further on the background-model path.

Keep: `FrameCoordinateMapper` discipline (never apply view-zone coords to a raw frame), the
runner-up margin concept, zone-crop-only processing, `AutoShotTracker` state machine (minus
Calibrating, or with Calibrating reduced to a few warm-up frames), the debug telemetry habit.

## 4. Launch trigger — motion-only first; audio deferred

**2026-07-06 field-testing decision:** skip the audio impact trigger for the current M3 range
build. The next validation sessions are at a public driving range with many adjacent hitters, so
phone-mic impact transients are expected to be ambiguous and likely to create confusing false
proposals. Keep launch detection vision-only for now: the locked ball must disappear from its spot
and a motion streak must leave the launch area.

Audio remains a deferred option if field evidence shows vision-only launch is insufficient in
quieter conditions or with a tighter directional microphone strategy. If revisited later, use the
original shape: continuous mic capture while `BallLocked`, short-window RMS/spectral-flux spike
against a rolling noise floor, ~1 s refractory period, and mandatory vision confirmation within
~250 ms.

## 5. Flight tracking (upgrade of the current LumaMotionDetector/ShotTracker path)

- **Three-frame differencing** (not background subtraction) restricted to a predicted crop.
  At ~12 fps effective, the ball is an elongated motion-blurred streak: detect elongated
  difference blobs, take the leading-edge centroid.
- **Single hypothesis + Kalman.** State (x, y, vx, vy); seed position from the lock point and
  initial velocity from the first post-impact detection. Each frame, search only a crop (~4× the
  prediction uncertainty) around the predicted position; associate the nearest consistent
  detection; coast through short misses (≤ ~5 frames) on prediction alone.
- **Ballistic gating.** Reject candidates implying impossible acceleration or origin outside the
  launch cone from the lock position (up = −y in view space, per the mapper rule). Physics is
  the strongest filter against birds, other balls, and netting flutter.
- Trace = smoothed Kalman track; end on N consecutive misses or leaving frame; then the existing
  Reviewing → auto re-arm flow.

## 6. Data collection now, learned verifier later

### 6.1 Crop logging (build in Phase 1 — this is the critical path for any future ML)

Every session, log to app-private storage: 48×48 Y (or RGB) crops of (a) every locked ball,
(b) top rejected candidates, (c) random zone negatives — with timestamp, condition of the lock
(later invalidated or not), and the frame-level scores. A debug "export session crops" action
zips them for offline labeling. A few sessions of this makes the verifier a weekend of labeling,
not a data-collection project.

### 6.2 Verifier (Phase 4, gated on data)

- Tiny CNN classifier ("ball / not-ball") on candidate crops, ~1 MB, LiteRT (TFLite) with
  GPU/NNAPI delegate. Runs on ≤ 8 crops/frame — negligible cost. Slots in between §3.3 and §3.4:
  the classical pipeline proposes, the verifier scores, persistence still decides.
- **Licensing constraints (hard rules):**
  - **No Ultralytics YOLOv8** code or weights, and no models trained with the `ultralytics`
    package — it is AGPL-3.0 and Ultralytics treats trained weights as encumbered. Shipping such
    a model in this app means open-sourcing the app or buying a commercial license.
  - No third-party model binaries of unknown training provenance (e.g. the `best.onnx` in
    emms204/GolfBallTracking).
  - No code from GPL repos (robopt/GolfBallOpenCV) or unlicensed repos.
  - Allowed stacks: plain Keras/PyTorch → LiteRT export, YOLOX (Apache-2.0), NanoDet
    (Apache-2.0), MediaPipe Model Maker.
- Surveyed repos (cochran-brian/shot-tracer, emms204/GolfBallTracking, DETR variants) are
  idea-only references; none contain a usable dataset or a cleanly-licensed golf-ball model, and
  none are Android-ready. The architecture they validate (crop-based detection + single-ball
  Kalman) is already this spec.

## 7. Debug telemetry / overlay

Status line while acquiring:

```
shot=Searching r_e=7 cand=3 best={q=4.1 C=3.2 E=0.71 U=0.4 r=6} persist=12.3/15 margin=2.1
```

- `r_e` expected radius px, `cand` gated candidates this frame, `best` its cue scores,
  `persist` leading accumulator cell vs. lock threshold, `margin` vs. runner-up cell.
- While locked: single locked point only (current DetectionDebugFilter behavior is right).
- Audio telemetry is deferred with the audio trigger; keep status focused on vision launch evidence for current range testing.
- Keep the debug dump action: zone crop + DoG response + candidate circles as PNGs on demand;
  extend it with the crop logger (§6.1).

## 8. Implementation plan

Phases ordered by field value per effort. Each lands with tests green (`rtk ./gradlew
testDebugUnitTest`, `rtk ./gradlew assembleDebug`) before the next starts.

1. **Acquisition rewrite** (pure Kotlin, no device needed until the end):
   a. `BallScale` (geometry → r_e), separable Gaussian/DoG over a crop, NMS — unit-tested against
      synthetic discs (bright *and* dark, on flat and textured backgrounds).
   b. Candidate scoring: annulus contrast, uniformity, RANSAC circle fit — tests must include a
      partially occluded disc (~35% of rim masked by "grass" strokes) that still passes, and a
      hard-shadowed bright disc (dark crescent attached) that still centers correctly.
   c. Persistence accumulator + lock/margin/maintenance rules; tests for jittering distractors
      never locking, a still ball locking through intermittent occlusion, two balls ⇒ no lock.
   d. New `ZoneBallDetector` implementation behind the same public surface
      (`analyzeFrame → ZoneBallDetection`) so `AutoShotTracker`/overlay changes are minimal;
      delete the background-model path and its config knobs (§3.5); rewrite affected tests.
   e. Golden fixtures: real crops from `docs/reference-images/` + every future range session,
      asserting accept/reject as in the synthetic suite.
2. **Crop logging + debug dumps** (§6.1, §7) — small, but must ship in the same field build as
   Phase 1 so every session grows the dataset.
3. **Audio impact trigger deferred** (§4) for public-range testing; keep launch vision-only.
4. **Flight tracking upgrade** (§5): predicted-crop differencing + Kalman + ballistic gate,
   replacing full-zone motion candidate association inside `ShotTracker`/`AutoShotTracker`.
5. **Learned verifier** (§6.2) once ≥ a few hundred labeled positives exist from Phase 2 logs.

### Field protocol (every session until stable)

- Zone covers the **mat/hitting area only** — never trees, netting, or sky. (The 2026-07-05
  false lock sat on the tree line inside an oversized zone; no detector should be asked to
  ignore a forest.)
- Test matrix per session: ball brighter than surface, ball in shadow/darker, ball partially
  behind grass, low sun/backlight. Record the debug status line + dump for every failure.
- Success criteria before Phase 1 is called done: lock on the real ball ≤ 3 s in all four matrix
  conditions, zero false locks over a full bucket, survives club waggle/address occlusion.
