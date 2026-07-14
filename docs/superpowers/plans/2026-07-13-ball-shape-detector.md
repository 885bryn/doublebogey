# Ball-Shape Detector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the slow, marking-prone still-ball detector with a fast, handheld-safe pipeline that proposes ball-sized blobs, rejects open or continuing mat lines, confirms one verified candidate across a short frame window, and reports truthful UI states.

**Architecture:** Keep `ZoneBallDetector.analyzeFrame` as the integration boundary, but compose it from four focused Kotlin units: geometry-based scale estimation, scale-space blob proposal, closed-object shape verification, and short-window candidate confirmation. Remove background/calibration state from still-ball acquisition; preserve launch-zone crop mapping and keep flight tracking separate.

**Tech Stack:** Kotlin/JVM 17, Android SDK 34/minSdk 30, existing `kotlin.test` JVM tests, Camera2/YUV420 frames, no new runtime dependencies.

## Global Constraints

- Do not add OpenCV, LiteRT/TFLite, a neural-network model, or another runtime dependency.
- Never compare with a stored empty/background frame. Still-ball evidence is current-frame, local, and sign-invariant.
- Generate at most eight expensive shape-verification requests per analyzed frame.
- Reject line-like structures before temporal confirmation; persistence never accumulates rejected markings.
- Tolerate radius-scaled position movement caused by normal handheld tremor.
- Keep acquisition inside the launch-zone crop and preserve `FrameCoordinateMapper` behavior.
- `CANDIDATE` means shape-verified but unconfirmed. `BALL LOCKED` requires shape and temporal confirmation.
- Keep flight tracking unchanged except for adapting to the new still-ball interface.
- Target at least 10 analyzed acquisition fps on the current range phone before path testing resumes.
- Preserve existing dirty camera work in `CameraCaptureController.kt`, `CameraFrameAnalysisGate.kt`, `CameraRequestPolicy.kt`, and their tests. Before Task 8 touches the controller, checkpoint those changes separately or stage only the detector-specific hunk.
- Use TDD for every behavior change: focused failing test, expected RED, minimal implementation, focused and neighboring GREEN, then commit.

## File Map

Create:

- `BallScaleEstimator.kt` — physical geometry to expected/supported radii.
- `BallBlobProposer.kt` — sign-invariant DoG response and non-maximum suppression.
- `BallShapeVerifier.kt` — closed edge, radial alignment, radius consistency, appearance, chroma, and line continuation.
- `BallCandidateTracker.kt` — seven-frame handheld-tolerant confirmation.
- One matching JVM test file per component.
- `FieldBallFixtureTest.kt`, `ZoneBallDetectorPerformanceTest.kt`, and `app/src/test/resources/field/`.

Modify:

- `ZoneBallDetector.kt` and tests — compose the new units and delete calibration/persistence scanning.
- `AutoShotTracker.kt` and tests — search immediately without background calibration.
- `CameraStatusBadgeModel.kt`, `DetectionDebugFilter.kt`, `MainActivity.kt`, and tests — truthful states and reset copy.
- `ZoneCropLogger.kt`, `CameraCaptureController.kt`, and tests — explicit diagnostic labels and metrics.

---

### Task 1: Scale Estimation

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/BallScaleEstimator.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/BallScaleEstimatorTest.kt`

**Interfaces:**
- Consumes: launch-zone crop width, physical ball diameter, assumed physical zone width.
- Produces: `BallScale(expectedRadiusPx, radiiPx)` from `BallScaleEstimator.estimate(zoneWidthPx)`.

- [ ] **Step 1: Write failing geometry tests**

```kotlin
class BallScaleEstimatorTest {
    private val estimator = BallScaleEstimator(1500.0, 42.7)

    @Test fun derivesExpectedRadiusAndThreeScales() {
        val scale = estimator.estimate(240)
        assertEquals(3.416, scale.expectedRadiusPx, 0.01)
        assertEquals(listOf(2.562, 3.416, 4.782), scale.radiiPx.map { (it * 1000).roundToInt() / 1000.0 })
    }

    @Test fun clampsUnsupportedExtremes() {
        assertEquals(2.0, estimator.estimate(20).expectedRadiusPx)
        assertEquals(40.0, estimator.estimate(4000).expectedRadiusPx)
    }
}
```

- [ ] **Step 2: Verify RED**

Run `.\gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.BallScaleEstimatorTest" --console=plain`.

Expected: compilation fails because `BallScaleEstimator` does not exist.

- [ ] **Step 3: Implement the scale unit**

```kotlin
data class BallScale(val expectedRadiusPx: Double, val radiiPx: List<Double>)

class BallScaleEstimator(
    private val assumedZoneWidthMm: Double = 1500.0,
    private val ballDiameterMm: Double = 42.7,
) {
    init {
        require(assumedZoneWidthMm > 0.0)
        require(ballDiameterMm > 0.0)
    }

    fun estimate(zoneWidthPx: Int): BallScale {
        require(zoneWidthPx > 0)
        val expected = (zoneWidthPx * ballDiameterMm / assumedZoneWidthMm / 2.0).coerceIn(2.0, 40.0)
        return BallScale(
            expectedRadiusPx = expected,
            radiiPx = listOf(0.75, 1.0, 1.4)
                .map { (expected * it).coerceIn(2.0, 40.0) }
                .distinctBy { (it * 100.0).roundToInt() },
        )
    }
}
```

- [ ] **Step 4: Verify GREEN and commit**

Run the focused test; expect PASS.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/BallScaleEstimator.kt app/src/test/java/com/doublebogey/golftracer/camera/BallScaleEstimatorTest.kt
git commit -m "feat(vision): derive scale-aware ball radii"
```

---

### Task 2: Fast Scale-Space Blob Proposals

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/BallBlobProposer.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/BallBlobProposerTest.kt`

**Interfaces:**
- Consumes: `YuvFrame`, `BallScale.radiiPx`.
- Produces: sorted `List<BallBlobProposal>`, capped at eight.

- [ ] **Step 1: Write failing proposal tests**

Use 96×64 deterministic textured frames. Test a bright disc, dark disc, flat frame, and nine separated discs.

```kotlin
val proposals = BallBlobProposer().propose(frame, listOf(3.0, 4.0, 5.6))
val best = assertNotNull(proposals.firstOrNull())
assertEquals(48.0, best.centerX, 2.0)
assertEquals(32.0, best.centerY, 2.0)
assertTrue(proposals.size <= 8)
```

Flat-frame output must be empty; nine separated discs must yield exactly eight proposals.

- [ ] **Step 2: Verify RED**

Run the focused proposer test. Expected: missing proposer types.

- [ ] **Step 3: Implement public types and scale-space search**

```kotlin
data class BallBlobProposal(
    val centerX: Double,
    val centerY: Double,
    val radiusPx: Double,
    val response: Double,
)

data class BallBlobProposerConfig(
    val maxProposals: Int = 8,
    val minNormalizedResponse: Double = 6.0,
    val nmsRadiusMultiplier: Double = 1.0,
)

class BallBlobProposer(
    private val config: BallBlobProposerConfig = BallBlobProposerConfig(),
) {
    fun propose(frame: YuvFrame, radiiPx: List<Double>): List<BallBlobProposal>
}
```

For each radius `r`:

1. Build normalized 1-D Gaussian kernels for `sigmaInner=max(0.8, 0.55r)` and `sigmaOuter=max(1.2, 1.10r)`, truncated at `ceil(3 sigma)`.
2. Apply horizontal then vertical convolution to Y with clamped edges.
3. Compute `abs(innerBlur-outerBlur)*sqrt(r)`.
4. Estimate noise as the median absolute response sampled every fourth pixel.
5. Keep 3×3 local maxima above `max(6.0, 3*median)` and outside a `ceil(2.2r)` border.
6. Merge scales, sort descending, suppress centers within `max(existing.r, candidate.r)`, stop at eight.

Allocate four frame-sized `DoubleArray` buffers once per `propose` call and reuse them across scales; allocate nothing inside pixel loops.

- [ ] **Step 4: Verify GREEN and commit**

Run proposer and scale tests; expect PASS.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/BallBlobProposer.kt app/src/test/java/com/doublebogey/golftracer/camera/BallBlobProposerTest.kt
git commit -m "feat(vision): propose scale-space ball blobs"
```

---

### Task 3: Closed-Object Shape Verification

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/BallShapeVerifier.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/BallShapeVerifierTest.kt`

**Interfaces:**
- Consumes: one `BallBlobProposal`, its `YuvFrame`.
- Produces: explicit metrics, score, acceptance, and rejection reason.

- [ ] **Step 1: Write the failing shape matrix**

Use 64×64 fixtures centered at `(32,32)`, radius 5. Separate tests must accept bright/dark discs, a disc with a 100-degree occluded rim, and a disc with an adjacent hard shadow. Separate tests must reject a straight stripe, curved stripe, cross, endpoint, open 220-degree arc, elongated ellipse, and strongly colored compact blob.

```kotlin
val result = BallShapeVerifier().verify(frame, BallBlobProposal(32.0, 32.0, 5.0, 20.0))
assertTrue(result.accepted, result.toString())
```

Negative tests also assert the expected `BallShapeRejection`, preventing threshold changes from passing for unrelated reasons.

- [ ] **Step 2: Verify RED**

Expected: missing verifier types.

- [ ] **Step 3: Define verifier data and gates**

```kotlin
enum class BallShapeRejection {
    LowContrast, InteriorTooTextured, InsufficientClosedEdge,
    PoorRadialAlignment, RadiusInconsistent, LineContinuation, StrongChromaShift,
}

data class BallShapeMetrics(
    val annulusContrast: Double,
    val interiorUniformity: Double,
    val closedEdgeCoverage: Double,
    val radialAlignment: Double,
    val radiusVariation: Double,
    val lineContinuation: Double,
    val chromaShift: Double,
)

data class BallShapeEvaluation(
    val proposal: BallBlobProposal,
    val metrics: BallShapeMetrics,
    val score: Double,
    val accepted: Boolean,
    val rejection: BallShapeRejection?,
)

data class BallShapeVerifierConfig(
    val angularSectors: Int = 32,
    val minAnnulusContrast: Double = 2.0,
    val maxInteriorUniformity: Double = 0.95,
    val minClosedEdgeCoverage: Double = 0.56,
    val minRadialAlignment: Double = 0.60,
    val maxRadiusVariation: Double = 0.35,
    val maxLineContinuation: Double = 0.35,
    val chromaShiftTolerance: Double = 45.0,
    val sigmaFloor: Double = 6.0,
)
```

- [ ] **Step 4: Implement bilinear closed-edge measurements**

For each of 32 angular sectors, bilinearly sample central-difference gradients from `0.65r` to `1.45r` in `0.20r` steps. Select the largest magnitude. Alignment is `abs(gx*cos(theta)+gy*sin(theta))/max(magnitude,1e-6)`. Support requires magnitude `>=max(8,1.5*annulusSigma)` and alignment `>=0.55`.

Compute closed coverage, average alignment, and `stddev(edgeRadius)/mean(edgeRadius)`. For line continuation, sample magnitudes at `1.7r`, `2.1r`, `2.5r`; a sector continues when any is at least 70% of its rim magnitude. Use a `0.60r` interior disc and `[1.3r,2.2r]` annulus for sign-invariant contrast, uniformity, and relative U/V shift.

Apply enum gates in order, with line continuation after radius consistency. Accepted score:

```kotlin
contrast * closedCoverage * radialAlignment *
    (1.0 - uniformity.coerceIn(0.0, 0.95)) *
    (1.0 - continuation.coerceIn(0.0, 0.95))
```

- [ ] **Step 5: Verify GREEN and commit**

Run the entire shape matrix. Every positive and negative must pass before changing a default threshold.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/BallShapeVerifier.kt app/src/test/java/com/doublebogey/golftracer/camera/BallShapeVerifierTest.kt
git commit -m "feat(vision): verify closed isolated ball shapes"
```

---

### Task 4: Handheld-Tolerant Confirmation

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/BallCandidateTracker.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/BallCandidateTrackerTest.kt`

**Interfaces:**
- Consumes: accepted `BallShapeEvaluation` values only.
- Produces: leader, confirmed candidate, hits/window, margin, ambiguity.

- [ ] **Step 1: Write failing sequence tests**

Test a radius-five candidate shifting `(0,0),(2,1),(4,2),(5,3),(7,3)` and confirming on hit five; five hits plus two misses; deletion after three consecutive misses; two equal candidates never confirming; and a 1.35× leader confirming.

- [ ] **Step 2: Verify RED**

Expected: missing tracker types.

- [ ] **Step 3: Implement the tracker**

```kotlin
data class BallCandidateTrackerConfig(
    val windowSize: Int = 7,
    val hitsRequired: Int = 5,
    val maxConsecutiveMisses: Int = 3,
    val associationRadiusMultiplier: Double = 3.0,
    val minAssociationDistancePx: Double = 4.0,
    val leaderMargin: Double = 1.35,
)

data class BallCandidateTrackingResult(
    val leader: BallShapeEvaluation?,
    val confirmed: BallShapeEvaluation?,
    val hits: Int,
    val windowSize: Int,
    val margin: Double,
    val ambiguous: Boolean,
)
```

Private tracks hold smoothed center/radius (`alpha=0.55`), latest evaluation, seven booleans, and consecutive misses. Greedily associate descending-score candidates to the nearest unmatched track within `max(4px,3*max(radii))`. Append false to unmatched tracks and delete on miss three. Eligibility requires a current match and five hits. Confirm only an eligible leader whose score margin is at least 1.35; a missing runner-up gives infinite margin. `reset()` clears all tracks.

- [ ] **Step 4: Verify GREEN and commit**

Run tracker and shape tests; expect PASS.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/BallCandidateTracker.kt app/src/test/java/com/doublebogey/golftracer/camera/BallCandidateTrackerTest.kt
git commit -m "feat(vision): confirm balls under handheld drift"
```

---

### Task 5: Compose `ZoneBallDetector`

**Files:**
- Rewrite: `app/src/main/java/com/doublebogey/golftracer/camera/ZoneBallDetector.kt`
- Rewrite: `app/src/test/java/com/doublebogey/golftracer/camera/ZoneBallDetectorTest.kt`

**Interfaces:**
- Consumes: launch-zone `YuvFrame` (camera path) or existing full-frame/zone/mapper call.
- Produces: `ZoneBallDetection(acceptedCandidate, debug)` without background state.

- [ ] **Step 1: Write pipeline-level failing tests**

Required cases: patterned empty mat never locks over 20 frames; bright/dark balls lock after five hits; three-radius handheld drift locks; two balls remain ambiguous; proposal count never exceeds eight; rejected marking appears only in `topRejected`; reset clears confirmation; verified leader appears as `debug.best` before lock; rotation and crop-to-view mapping remain correct.

```kotlin
assertEquals(null, emptyResult.acceptedCandidate)
assertNotNull(emptyResult.debug.topRejected)
assertTrue(emptyResult.debug.proposalCount <= 8)
assertEquals(7, ballResult.debug.confirmationWindow)
```

- [ ] **Step 2: Verify RED**

Expected: revised debug fields are missing and legacy calibration assumptions fail.

- [ ] **Step 3: Replace legacy debug models**

```kotlin
data class ZoneBallCandidateDebug(
    val candidate: LumaMotionCandidate,
    val radiusPx: Double,
    val proposalResponse: Double,
    val rankScore: Double,
    val metrics: BallShapeMetrics,
    val rejection: BallShapeRejection? = null,
)

data class ZoneBallDebug(
    val candidates: List<ZoneBallCandidateDebug> = emptyList(),
    val topRejected: ZoneBallCandidateDebug? = null,
    val proposalCount: Int = 0,
    val rejectedCount: Int = 0,
    val expectedRadiusPx: Double = 0.0,
    val confirmationHits: Int = 0,
    val confirmationWindow: Int = 7,
    val margin: Double = 0.0,
    val ambiguous: Boolean = false,
) { val best get() = candidates.firstOrNull() }
```

Delete `ZoneBallCalibrationState`, calibration/background fields, old accumulator types, and exhaustive `scoreCandidate` scanning. `ZoneBallDetectorConfig` retains physical dimensions and nested component configs.

- [ ] **Step 4: Compose the four stages**

Map/extract the zone, estimate scale, propose up to eight blobs, verify each, pass accepted evaluations to the tracker, then map leader/confirmed positions back to view coordinates. `confidence=(shapeScore/12).coerceIn(0,1)` and `pixelCount=round(PI*r*r)`. Debug candidates contain accepted evaluations; `topRejected` is the highest-scoring rejection.

Status summary must contain `r_e`, proposal/verified counts, best score, closed edge, radial alignment, line continuation, radius variation, radius, confirmation hits/window, margin, and ambiguity. Remove calibration/AE claims.

- [ ] **Step 5: Verify GREEN and commit**

Run all component and detector tests; expect PASS.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/ZoneBallDetector.kt app/src/test/java/com/doublebogey/golftracer/camera/ZoneBallDetectorTest.kt
git commit -m "feat(vision): compose shape-verified detector"
```

---

### Task 6: Remove Acquisition Calibration from `AutoShotTracker`

**Files:**
- Modify: `app/src/main/java/com/doublebogey/golftracer/camera/AutoShotTracker.kt`
- Modify: `app/src/test/java/com/doublebogey/golftracer/camera/AutoShotTrackerTest.kt`
- Modify: `app/src/test/java/com/doublebogey/golftracer/camera/AutoShotTrackerLifecycleTest.kt`

**Interfaces:**
- Consumes: already-confirmed detector candidate.
- Produces: lifecycle beginning at `Searching`; launch and flight behavior preserved.

- [ ] **Step 1: Write lifecycle failing tests**

Assert new tracker starts/searches immediately, empty mat stays searching, zone changes reset confirmation while staying searching, reset/review re-arm returns searching, rotated and cropped locks map correctly, and disappearance/launch behavior remains intact.

- [ ] **Step 2: Verify RED**

Expected: current code reports `Calibrating` and calls removed calibration methods.

- [ ] **Step 3: Simplify lifecycle**

Remove `Calibrating` from `AutoShotTrackerStatus`; remove `ZoneMotionMeter` from constructor/acquisition. Initialize/reset to `Searching`. Replace `beginCalibration` with `resetAcquisition`, which resets detector/delegate and lock/review state. Analyze immediately in full-frame and crop methods. Set `stableFramesRequired=1` because detector confirmation is already 5-of-7. Preserve lock maintenance, launch motion confirmation, tracking, reviewing, and coordinate transforms.

- [ ] **Step 4: Verify GREEN and commit**

Run tracker, detector, mapper, and shot-tracker tests. Confirm `rg -n "ZoneBallCalibrationState|collectCalibrationFrame" app/src` returns no matches.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/camera/AutoShotTracker.kt app/src/test/java/com/doublebogey/golftracer/camera/AutoShotTrackerTest.kt app/src/test/java/com/doublebogey/golftracer/camera/AutoShotTrackerLifecycleTest.kt
git commit -m "refactor(vision): search without background calibration"
```

---

### Task 7: Truthful UI and Overlay

**Files:**
- Modify: `app/src/main/java/com/doublebogey/golftracer/CameraStatusBadgeModel.kt`
- Modify: `app/src/test/java/com/doublebogey/golftracer/CameraStatusBadgeModelTest.kt`
- Modify: `app/src/main/java/com/doublebogey/golftracer/camera/DetectionDebugFilter.kt`
- Modify: `app/src/test/java/com/doublebogey/golftracer/camera/DetectionDebugFilterTest.kt`
- Modify: `app/src/main/java/com/doublebogey/golftracer/MainActivity.kt`

**Interfaces:**
- Consumes: verified leader, locked ball, lifecycle status.
- Produces: exact badge copy and at most one still-ball overlay point.

- [ ] **Step 1: Write failing UI/filter tests**

Assert `DETECTOR STARTING`, `DETECTOR READY`, `NO BALL`, `CANDIDATE`, `BALL LOCKED`, and `BALL TRACKING`. Overlay shows only `debug.best` before lock, locked ball after lock, and never `topRejected` or raw motion.

- [ ] **Step 2: Verify RED**

Expected: current calibration and `BALL DETECTED` copy fails.

- [ ] **Step 3: Implement copy and controls**

Rename badge field `calibration` to `detector`. Candidate tone remains `Detected`; locked tone remains `Ready`. Rename `Calibrate mat` button to `Reset detection`; keep clearing overlays/tracks and calling `resetShotReview()`. Add `Keep the ball and hitting spot inside the green box.` below the badges. Do not capture an empty frame.

- [ ] **Step 4: Verify GREEN and commit**

Run status/filter tests. Confirm `rg -n "CALIBRAT|BALL DETECTED" app/src/main app/src/test` has no acquisition UI matches.

```powershell
git add app/src/main/java/com/doublebogey/golftracer/CameraStatusBadgeModel.kt app/src/test/java/com/doublebogey/golftracer/CameraStatusBadgeModelTest.kt app/src/main/java/com/doublebogey/golftracer/camera/DetectionDebugFilter.kt app/src/test/java/com/doublebogey/golftracer/camera/DetectionDebugFilterTest.kt app/src/main/java/com/doublebogey/golftracer/MainActivity.kt
git commit -m "fix(ui): distinguish candidates from locked balls"
```

---

### Task 8: Field Fixtures and Diagnostic Crop Labels

**Files:**
- Create: `app/src/test/resources/field/range-ball.png`
- Create: `app/src/test/resources/field/range-mat-curve.png`
- Create when clean pixels exist: `app/src/test/resources/field/grass-ball.png`
- Create: `app/src/test/resources/field/manifest.txt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/FieldBallFixtureTest.kt`
- Modify: `app/src/main/java/com/doublebogey/golftracer/camera/ZoneCropLogger.kt`
- Modify: `app/src/test/java/com/doublebogey/golftracer/camera/ZoneCropLoggerTest.kt`
- Modify: `app/src/main/java/com/doublebogey/golftracer/camera/CameraCaptureController.kt`

**Interfaces:**
- Consumes: supplied screenshots and shape telemetry.
- Produces: permanent field regressions and labeled PGM/JSON logs.

- [ ] **Step 1: Create documented 48×48 crops**

Crop the clean real ball outside the green box in `Screenshot_20260713-150803.png`; crop a curved white mat marking without the cyan dot; use the least-contaminated grass ball only if ball pixels remain visible. Manifest records source path/dimensions, exact crop rectangle, label, and overlay contamination. Never describe cyan/green overlay as camera pixels.

- [ ] **Step 2: Write fixture tests and verify RED**

Use `javax.imageio.ImageIO` in test code to convert RGB to YUV. Search radii 3..9 at crop center and require the range ball to pass, mat curve to fail with a closed-edge/line rejection, and grass ball to pass when present. Run shape, proposer, and fixture tests together after any sampling/default adjustment; synthetic negatives may not weaken.

- [ ] **Step 3: Extend logging kinds/metadata**

```kotlin
enum class ZoneCropSampleKind(val wireName: String) {
    LockedBall("locked_ball"),
    VerifiedCandidate("verified_candidate"),
    RejectedShape("rejected_shape"),
    Ambiguous("ambiguous"),
    RandomNegative("random_negative"),
}
```

Add JSON fields `rejection`, `closedEdgeCoverage`, `radialAlignment`, `radiusVariation`, and `lineContinuation`; assert exact keys/values in logger tests.

- [ ] **Step 4: Update controller sample selection safely**

First checkpoint the pre-existing camera-thread work separately or use patch staging. Selection order: locked, ambiguous verified leader, verified leader, top rejected, random negative. Pass chosen shape metrics/rejection. Preserve one-second interval and crop-coordinate mapping.

- [ ] **Step 5: Verify GREEN and commit**

Run field/logger tests and compile camera source. Inspect `git diff --cached` so no unrelated screenshot originals or camera changes are bundled.

```powershell
git commit -m "test(vision): preserve range detector regressions"
```

---

### Task 9: Performance Guard and Full Verification

**Files:**
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/ZoneBallDetectorPerformanceTest.kt`
- Modify only with evidence: the four new component files.

**Interfaces:**
- Consumes: representative 240×180 textured crop containing markings and one ball.
- Produces: proposal-count and coarse JVM runtime guards; phone telemetry remains authoritative.

- [ ] **Step 1: Write the performance guard**

Warm three frames; time twelve with `measureNanoTime`; sort and inspect median.

```kotlin
assertTrue(result.debug.proposalCount <= 8)
assertTrue(medianMs < 250.0, "median detector time was $medianMs ms")
```

- [ ] **Step 2: Run performance and focused suites**

If the ceiling fails, profile buffers/convolution/NMS and optimize without weakening shape gates. Then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.Ball*Test" --tests "com.doublebogey.golftracer.camera.ZoneBallDetector*Test" --tests "com.doublebogey.golftracer.camera.AutoShotTracker*Test" --tests "com.doublebogey.golftracer.camera.DetectionDebugFilterTest" --tests "com.doublebogey.golftracer.camera.ZoneCropLoggerTest" --tests "com.doublebogey.golftracer.CameraStatusBadgeModelTest" --console=plain
```

Expected: all focused tests pass.

- [ ] **Step 3: Run complete unit/build verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`, no failing tests or compiler errors.

- [ ] **Step 4: Scope check and commit**

Run `git status -s`, `git diff --stat`, and `git diff --check`. Do not stage screenshot originals or unrelated dirty files.

```powershell
git add app/src/test/java/com/doublebogey/golftracer/camera/ZoneBallDetectorPerformanceTest.kt
git commit -m "test(vision): guard acquisition proposal cost"
```

- [ ] **Step 5: Prepare field handoff without claiming phone success**

Field acceptance:

- green box contains intended ball/hitting spot;
- empty patterned mat remains `NO BALL` for 30 seconds;
- placed ball becomes `CANDIDATE`, then `BALL LOCKED`, while handheld;
- mat marks, clubhead, shoes, and outside-box balls never lock;
- acquisition telemetry is at least 10 fps;
- path testing resumes only after these checks pass.

Record actual phone results after testing. JVM/build success does not prove field accuracy or phone fps.
