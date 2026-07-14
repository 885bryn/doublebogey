# Automatic Ball Acquisition Redesign

Status: superseded (detection method). The calibrated-background detection approach (§3) failed
in the field (2026-07-05 range session: false lock on a 6 px speck while the real ball produced
no candidate) and is replaced by
`2026-07-05-surface-agnostic-acquisition-and-tracking.md`. The camera-control prerequisite
(§3.1), coordinate-mapper rule (§1d), and `AutoShotTracker` lifecycle shape carry forward.

Originally: proposed. Supersedes the acquisition half of `2026-06-30-m3-detection-debug-first-design.md`.
The launch/track half of M3 (`ShotTracker`, `LumaMotionDetector`) is retained.

## 0. Calibrate against reference imagery before tuning constants

Every threshold in this doc (`minYDelta≈18`, `kY≈4σ`, `minArea`/`maxArea`, `chromaShiftTolerance≈45`,
the `1.5×` acceptance margin) is a principled *starting point* derived from reasoning about the
failure mode in §1, not a measurement. Before/alongside implementation, collect a small reference
set and use it to replace guesses with numbers:

- **What to collect**: photos of the actual mat/hitting area, ball placed in the launch-zone spot,
  across the lighting conditions you actually play in (midday sun, overcast, shade/golden hour,
  indoor net if relevant). For each condition, ideally one frame *with* the ball and one of the
  *empty* mat — the empty one stands in for the calibrated background, the ball one for a
  post-lock detection frame.
- **What to extract from each pair**: crop to the launch-zone region; compute per-pixel Y/U/V mean
  and stddev over the empty-mat crop (this is what §3.2 calibration would have produced); compute
  the ball region's actual `dY` (brightness delta from that mean) and chroma shift (`dU`, `dV`);
  measure the ball's pixel footprint (radius/area) at the real camera distance and the app's
  actual capture resolution.
- **What it answers**:
  1. Does the ball's `dY` clear `max(minYDelta, kY·σY)` in *every* condition, with margin? If a
     shaded/overcast frame's delta is only 20 against a proposed `kY·σY` of 24, tighten `minYDelta`
     down or accept that condition needs supplemental lighting — better to learn this from a photo
     than from a failed field session.
  2. Is the chroma veto (`chromaShiftTolerance≈45`) even necessary on this mat, or does real turf
     color make it too aggressive/lenient? Compute real `|dU|+|dV|` for the ball vs. for colored
     mat artifacts (seam dye, logos) if any appear in the photos.
  3. What are realistic `minArea`/`maxArea` in pixels, given real ball-to-camera distance? A guess
     of "6…4000 px" is wide enough to be nearly unconstrained; real photos narrow it to the actual
     expected range and let `maxBlobPixels` reject bystander objects more aggressively.
  4. Does the AE/AWB-lock assumption in §3.1 hold — i.e., does a *locked-exposure* comparison keep
     the ball's delta roughly stable across conditions, or does even locked exposure drift enough
     between calibration and shot that adaptive per-session thresholds are needed?
- **Turn the results into fixtures, not just numbers**: once real pixel data is in hand, save small
  crops (e.g. 40×40 launch-zone regions) as test resources and add them as "golden" regression
  tests alongside the synthetic ones in §6 step 8. Synthetic frames prove the *logic* is correct
  (positive-only gating, margin rule, chroma veto behavior); real crops prove the *constants* work
  on an actual mat. Keep both — synthetic tests pin edge-case behavior that's hard to photograph
  (e.g. exact σ-boundary cases), golden tests pin real-world validity.
- **If no photos are available yet**: implement with the §1-derived defaults, but treat them as
  provisional and flag the exact constants in code comments/config so a later reference-image pass
  can update them without re-deriving the reasoning.

## 1. Why the current approach fails in the field

The `StillBallScorer` idea (empty-mat background model + delta scoring) is fundamentally sound —
it is the standard answer to textured mats, because seams, printed turf lines, and stains are all
*static* and subtract out of a per-pixel background model. The failure is in three implementation
assumptions, all confirmed by the field telemetry line:

```
score=0.00 b=0.34 c=0.12 shape=0.75 cand=6 rejB=49999 rejC=207 rejS=2 rejZ=21
```

### 1a. Auto-exposure and auto-white-balance are never locked (root cause)

`CameraCaptureController.startRepeatingRequest` builds a `TEMPLATE_RECORD` request that only sets
`CONTROL_AE_TARGET_FPS_RANGE`. AE and AWB run fully automatic (the `7-30fps` mode label is itself
an AE-variable frame duration). The background model is captured at one exposure/white-balance
point; the moment a large white ball enters the zone (or a cloud moves, or the user's shadow
shifts), AE re-meters and darkens the whole frame and AWB shifts chroma.

Consequences, visible in the telemetry:

- `rejB=49999` — essentially the *entire* launch zone, including most of the ball body, fails the
  `brightnessDelta >= 35` gate. A "visually obvious" white ball whose pixels are not 35 counts
  brighter than the calibrated mat means the global exposure moved between calibration and
  detection, cancelling the delta. The ball is bright *to your eye* because your eye adapts
  locally; AE adapted globally and pulled the ball down toward the calibrated mat level.
- `c=0.12` and `rejC=207` — chroma is scored against **absolute neutrality (U=V=128)**. Under any
  warm/cool illuminant (evening sun, overcast, indoor), a white ball is *not* at (128,128); AWB
  drift moves it further. A mean chroma distance of ~19 against a tolerance of 22 is exactly what
  a mild color cast produces.

No detector downstream of an unlocked AE/AWB can pass an absolute-threshold comparison against a
stale background. This must be fixed first; everything else is tuning.

### 1b. The score formula cannot reach its own acceptance threshold in real conditions

`score = 0.45·b + 0.35·c + 0.20·shape`, accept at `score >= 0.62`, where:

- `b = meanDelta / 140` — a *strong* real delta of 60–90 gives b ≈ 0.43–0.64, so the brightness
  term contributes ≈ 0.19–0.29 of its 0.45 weight.
- `c = 1 − meanChromaDist / tolerance` — any real illuminant cast puts c at 0.1–0.6, contributing
  0.03–0.21.
- Observed field case: `0.45·0.34 + 0.35·0.12 + 0.20·0.75 = 0.345` — barely half the threshold.
  Even with *perfect* chroma the observed frame scores 0.70; the synthetic unit test passes only
  because it uses mat=112 / ball=245 / chroma exactly (128,128).

Fixed magic weights summed against unreachable normalizers is the wrong acceptance model. The
replacement below accepts on **statistical significance vs. the calibrated noise** plus a margin
over the runner-up, which is self-calibrating per scene.

### 1c. Per-pixel hard gates fragment the ball

`brightnessDelta >= 35` for every pixel means the shaded lower half of the ball drops out, the
ball splits into fragments, and the fragments then fail the size/fill/aspect gates
(`rejZ=21`, `cand=6` tiny components). A per-pixel threshold that adapts to measured per-pixel
noise (k·σ) keeps the dim half of the ball while still rejecting mat speckle.

### Secondary defects (worth fixing while in there)

- `Component.score` sums deltas over the whole *bounding box*, not the component's own pixels.
- Calibration accumulates only inside the launch-zone bounds *of that moment*; dragging the box
  mid-calibration mixes zones. Nothing recalibrates when the box is dragged afterward, either.
- `AutoShotTracker` double-gates confidence (`minLockConfidence = 0.80`) on top of the scorer's
  own accept threshold (0.62) — even a scorer-accepted ball at 0.7 can never lock.
- Once `BallLocked`, the lock is never re-verified: pick the ball up and the stale lock remains,
  and any upward motion candidate near the stale lock starts a phantom track.
- After `Finalized` the tracker parks in `Reviewing` until the button is pressed — a manual step
  per shot, which violates the product goal.
- `YuvFrameExtractor` copies the full 1280×720 Y/U/V (~2.7 MB) every frame → ~65 MB/s of garbage
  at 24 fps on the floor device. Acquisition only needs the launch-zone crop.
- `LumaStillBallDetector` is dead code (referenced only by its own test).

### 1d. Coordinate-space mismatch (found during field debugging, fixed 2026-07-03)

The launch zone is dragged in **portrait view space**, but the sensor delivers **landscape
1280×720 buffers**; TextureView silently rotates the buffer 90° for display. The detectors were
applying view-space normalized zone coordinates directly to the raw frame, so they scanned a
different patch of the mat than the one under the green box — `fg=0.0%` with an obvious ball in
the box. Fixed by `FrameCoordinateMapper` (rotation = `(sensorOrientation − displayRotation) mod
360`, read at camera open, `rot=` in the status line): detectors map the zone view→frame before
scanning pixels and map centroids frame→view before emitting, so trackers/overlay/launch logic
("up = −y") all stay in view space. Any orientation-sensitive detector work must go through this
mapper; never apply zone coordinates to a raw frame directly.

## 2. Proposed architecture

Keep the two-detector split — it matches the physics:

- **Still-ball acquisition** (new `ZoneBallDetector` + calibrated `ZoneBackgroundModel`): finds a
  small, bright, compact *new* object against a calibrated empty-mat background, restricted to the
  launch zone. Runs while Searching / BallLocked.
- **Launch/flight detection** (existing `LumaMotionDetector` + `ShotTracker`): frame-differencing
  for the fast-moving ball. Unchanged.
- **`AutoShotTracker`** orchestrates the full automatic lifecycle:

```
        button or zone drag or stale background
                        │
                        ▼
   ┌────────────► Calibrating ──(zone quiet for K frames,
   │                              AE/AWB locked, model built)
   │                        │
   │                        ▼
   │   (ball absent    Searching ◄──────────────┐
   │    > unlock       │  best candidate stable │ (review hold
   │    timeout)       │  N frames              │  elapsed)
   │                   ▼                        │
   └────────────── BallLocked                Reviewing
                       │                        ▲
                       │ launch trigger         │ (ShotTracker
                       ▼                        │  finalized)
                    Tracking ───────────────────┘
```

Key property: **the background survives the shot** — only the ball left the scene — so the
post-shot path is Reviewing → Searching directly, with *no* recalibration and no button press.
Recalibration happens only on: the button, a launch-zone drag, a frame-size change, or the
stale-background guard firing. Ball placement, lock, launch, trace, and re-arm for the next ball
are all automatic.

## 3. Detection method (detailed spec)

### 3.1 Camera control (prerequisite)

- When calibration starts: set `CONTROL_AE_LOCK = true` and `CONTROL_AWB_LOCK = true` on the
  repeating request (keep the builder around; call `setRepeatingRequest` again). Unlock only when
  a recalibration is requested, then re-lock before collecting frames. Skip ~5 frames after
  locking before collecting, to let the pipeline settle.
- Prefer a fixed FPS range (30,30) over (7,30) when ranking capture modes, so frame timing doesn't
  wander with AE.

### 3.2 Calibration → `ZoneBackgroundModel`

For each pixel in the launch zone, over K = 30 quiet frames, accumulate sum and sum-of-squares of
Y, U, V. Produce per-pixel mean µ and standard deviation σ (clamped to [2, 12] so dead-still
pixels don't create infinite significance and flickery pixels don't go blind).

- **Quiet gating**: a `ZoneMotionMeter` (previous zone crop vs. current, fraction of pixels with
  |ΔY| ≥ 24) must stay below ~2%; any spike discards progress and restarts collection. This makes
  "empty mat" self-enforcing — a hand or club in the zone restarts the count.
- Store the zone rect and frame size the model was built for; any mismatch later forces
  recalibration.
- Telemetry: median σY (scene noise floor), frames collected / required.

### 3.3 Per-frame foreground extraction (Searching / BallLocked)

For each zone pixel:

1. `dY = Y − µY`. Foreground requires `dY ≥ max(minYDelta, kY·σY)` with `minYDelta ≈ 18`,
   `kY ≈ 4`. **Positive-only**: shadows (darker) can never be foreground.
2. Chroma is *not* a per-pixel gate (that's what fragmented the ball). It is a component-level
   veto, measured **relative to the background**, not to absolute neutral: a component is vetoed
   if `|mean(dU)| + |mean(dV)| > chromaShiftTolerance (≈ 45)` — i.e. only strongly-colored new
   objects are rejected. A white ball under any illuminant shifts chroma only mildly from the mat.
3. Despeckle: keep a foreground pixel only if ≥ 2 of its 4 neighbours are foreground (one cheap
   pass, no full morphology).
4. Connected components (existing flood-fill pattern). Per component compute: area, bbox,
   fill ratio, aspect ratio, mean significance `s = mean(dY / σY)`, mean chroma shift.

### 3.4 Candidate acceptance (replaces the 0.62 magic score)

Filter: area in [minArea, maxArea] (defaults 6 … 4000 px to span a real ball through the lacrosse
test ball), aspect ≥ 0.5, fill ≥ 0.45, `s ≥ 6`, chroma veto above.

Rank survivors by `s · ((aspect + fill) / 2)`. Accept the top candidate iff it is the only one or
beats the runner-up by ≥ 1.5×. Report the top 3 with their stats regardless, for field tuning.

Everything is deterministic pure Kotlin on arrays; every rule unit-testable with synthetic frames.

### 3.5 Stale-background guard

If the foreground fraction of the zone exceeds ~35%, the background no longer matches the scene
(lighting change AE-lock couldn't absorb, mat moved, camera bumped). Flag `backgroundStale`; the
tracker returns to Calibrating and waits for a quiet zone. The status line must say so
("lighting changed — clear box, recalibrating") because this is the one case needing user action.

## 4. `AutoShotTracker` consumption

- Constructor takes the detector + `ShotTracker`; `update(zoneCrop, motionResult, launchZone)`
  drives the state machine in §2. `resetForNextShot()` remains as the button's manual override
  (jump straight to Calibrating with unlock/relock).
- **Lock**: detector's accepted candidate (no second confidence threshold — delete
  `minLockConfidence`) stable within 0.025 for 5 frames → `BallLocked`.
- **Lock maintenance**: accepted candidate near lock refreshes it; candidate missing/far for
  ~2 s (≈ 50 frames) → back to Searching. Generous enough to survive address/waggle occlusion,
  short enough to drop a picked-up ball.
- **Launch trigger** (backswing guard added): motion candidate above `zone.top` within
  `maxLaunchDistance (0.30, aligned with ShotTracker.maxStartDistance)` of the lock **and** the
  still candidate is absent or far from the lock in the same frame. During the takeaway the ball
  is still sitting on the tee, so a rising clubhead can no longer trigger; at real launch the
  ball has left its spot.
- **Reviewing**: hold the frozen track for `reviewHoldNs` (default 4 s) then automatically return
  to Searching. Background is still valid; no recalibration, no button.

## 5. Debug telemetry / overlays

Status line (replaces the current `ball={...}` blob):

```
shot=BallLocked cal=OK σ=3.4 aeLock=1 fg=1.2% best={s=11.2 area=214 fill=0.78 asp=0.86 dc=9} margin=2.4
```

- `σ` median calibrated noise, `aeLock` AE/AWB lock state, `fg` foreground fraction (stale guard
  input), `best` the top candidate's stats, `margin` best/runner-up ratio. During Calibrating:
  frames collected / required + "keep box clear".

Overlay (`LaunchZoneOverlayView`):

- Semi-transparent tint of the foreground mask inside the zone (small zone-sized `Bitmap`,
  scaled) — makes "why did/didn't it accept" visible at a glance in the field.
- Top-3 candidates: best as the cyan dot, runners-up as hollow yellow circles.
- Locked ball: cyan ring; track points unchanged.

Optional but cheap and very useful: a debug button that dumps zone crop + background mean + mask
as PNGs to app-private storage for offline analysis of a failed lock.

## 6. Implementation plan

0. **Reference imagery pass** (§0): collect mat/ball photos across lighting conditions if not
   already in hand; derive real `minYDelta`/`kY`/area bounds/`chromaShiftTolerance` from measured
   pixel data instead of the §1-derived defaults; save representative crops as golden test
   fixtures for step 8. Can run in parallel with step 1 if photos are still being gathered — land
   with the §1 defaults and update constants + add golden tests once imagery is available.
1. **`ZoneBackgroundModel` + `ZoneBallDetector`** (new file(s), replacing `StillBallScorer`):
   mean/variance calibration over zone crops, §3.3–3.5 extraction/acceptance, debug struct.
   Delete `LumaStillBallDetector.kt` + its test (dead), and `StillBallScorer.kt` + test once ported.
2. **`ZoneMotionMeter`** (new, tiny): previous-crop diff → zone motion fraction; used for quiet
   gating by calibration.
3. **`YuvFrameExtractor`**: add zone-crop extraction (only copy zone pixels for acquisition; keep
   full-Y extraction for `LumaMotionDetector`), killing the ~65 MB/s allocation churn.
4. **`AutoShotTracker`**: new statuses (`Calibrating`), owns detector + meter, lifecycle per §2/§4,
   backswing guard, lock maintenance, auto review-exit. Remove `minLockConfidence`.
   Remove `stillCandidates`/`stillBallDebug` from `LumaMotionResult` (state moves to
   `AutoShotTrackerState`).
5. **`CameraCaptureController`**: AE/AWB lock plumbing (keep request builder; relock on
   recalibrate), prefer fixed-fps mode, feed zone crop + motion result to tracker, new status
   line. `resetShotReview()` → tracker manual recalibrate.
6. **`DetectionDebugFilter`**: take `AutoShotTrackerState` (locked ball / top candidates) instead
   of `LumaMotionResult.stillCandidates`.
7. **`LaunchZoneOverlayView` / `MainActivity`**: mask + ranked-candidate rendering; button label
   stays "Calibrate mat".
8. **Tests** (deterministic, seeded synthetic frames: textured mat = base pattern + per-frame
   noise + bright seam lines):
   - model: σ reflects injected noise; seam pixels are background (never foreground).
   - detector: ball on textured mat accepted with s ≫ threshold; noise-only → none; global
     +25 luma shift → stale flag, not a candidate; colored bright object → chroma veto; shadow →
     nothing (positive-only); two similar candidates → no accept (margin rule).
   - meter: quiet vs. hand-in-zone.
   - tracker: full lifecycle (calibrate → lock → launch → review → auto re-arm), calibration
     restart on zone motion, recalibrate on zone drag, lost-ball unlock, backswing guard
     (ball still present + upward motion ⇒ no trigger).
   - golden fixtures from §0 (if imagery is available): real mat/ball crops assert the same
     acceptance behavior the synthetic tests assert with generated pixels.
9. **Field pass**: verify `aeLock=1`, σ and fg values sane, then tune `kY`, `s` threshold, area
   band against the real mat. The overlay mask makes each knob's effect visible live.

Suggested order: 0 in parallel with 1–4 (land with provisional constants if photos aren't ready
yet, revisit before 9), 1–4 together with tests (pure Kotlin, no device needed), then 5–7 (device
smoke test), then 8–9 in the field.
