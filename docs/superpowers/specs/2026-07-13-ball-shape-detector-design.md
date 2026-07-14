# Ball-Shape Detector Redesign

Status: approved in conversation on 2026-07-13.

## Goal

Reliably distinguish a golf ball from printed mat markings and other ball-sized scene details while the camera is handheld. Preserve the existing launch-zone workflow, but remove empty-frame calibration as a detection dependency. The immediate implementation remains deterministic Kotlin vision code; an on-device learned verifier is deferred until field logging provides enough representative training data.

## Field Evidence and Root Cause

The 2026-07-13 range test produced a false candidate on a curved white mat marking before a ball was placed. The real ball in the closer screenshot was outside the launch-zone box and therefore correctly unavailable to the detector. The important failure is that the empty patterned mat was reported as `BALL DETECTED`.

The current `ZoneBallDetector` does not identify a closed object. It evaluates every possible center using local disc/annulus brightness, interior variation, and independent radial brightness comparisons. A line or curved marking can satisfy those local checks. Temporal persistence then strengthens the stationary false candidate rather than rejecting it. The exhaustive per-pixel scoring also reduces effective analysis to approximately one frame per second in the field build.

Background subtraction is not the remedy. A reference frame becomes invalid when the phone moves, and any newly entering object can still become foreground. The detector must first decide whether a candidate has ball-specific spatial structure.

## Approaches Considered

### Tune the current thresholds

Rejected as the main solution. Tightening contrast, circularity, or persistence thresholds can suppress the observed marking but will create new misses under different ball sizes, mats, focus, and lighting. It does not address the missing closed-object test or the processing cost.

### Fast classical proposal plus ball-shape verification

Selected for the immediate implementation. A cheap multi-scale blob pass proposes a small number of locations. A separate verifier then tests closed edge coverage, radial edge orientation, interior consistency, isolation, and whether the apparent edge continues outside the candidate as a line. Only verified candidates participate in temporal confirmation.

### Add a learned verifier immediately

Deferred. A compact `ball` versus `not ball` crop classifier is the intended extension point, but the current project lacks enough labeled phone-camera crops to validate it across range conditions. Shipping a model now would make failures harder to explain without establishing better reliability.

## Architecture

Split still-ball acquisition into four independently tested components behind the existing `ZoneBallDetector.analyzeFrame` public boundary:

1. `BallScaleEstimator` derives the expected radius range from launch-zone geometry. It produces a scale range rather than a single hard radius and preserves the existing physical ball-size configuration.
2. `BallBlobProposer` performs a real scale-normalized Difference-of-Gaussians-style blob search followed by spatial/scale non-maximum suppression. It emits at most eight proposals and avoids running expensive shape measurements at every pixel.
3. `BallShapeVerifier` scores each proposed crop. It requires evidence distributed around a closed perimeter, checks that edge gradients point toward or away from the candidate center, measures a consistent edge radius across angular sectors, evaluates disc-versus-annulus appearance without assuming bright or dark contrast, and rejects line-like structures whose edges continue through the outer crop.
4. `BallCandidateTracker` confirms verified candidates over a short frame window. It associates candidates using radius-scaled motion tolerance so ordinary handheld drift does not reset confirmation. It never uses a stored background. A lock requires one clearly leading verified hypothesis; ambiguity remains searching.

These components produce the existing candidate/debug structures where practical so `AutoShotTracker`, the overlay, and flight-tracking boundaries need minimal changes.

## Per-Frame Data Flow

1. Extract the launch-zone crop and estimate the expected ball scale.
2. Generate no more than eight scale-aware blob proposals.
3. For each proposal, compute:
   - sign-invariant local contrast;
   - closed-perimeter angular coverage;
   - radial gradient alignment;
   - edge-radius consistency;
   - interior consistency relative to the surrounding annulus;
   - outer-line continuation evidence;
   - weak relative chroma veto for strongly colored non-balls.
4. Reject proposals that lack closed, isolated object evidence. A curved stripe or line intersection must fail because its edges continue beyond the candidate and do not form a consistent closed perimeter.
5. Rank the remaining proposals. Confirm a leader across a short recent-frame window using scale-relative position tolerance. Camera movement may shift the leader; confirmation does not require a fixed absolute pixel coordinate.
6. Emit `acceptedCandidate` only after confirmation. Until then, expose proposal diagnostics without claiming a ball lock.

The detector remains sign-invariant so a ball may be brighter or darker than its immediate surface.

## State and User Interface

- Remove calibration as a logical prerequisite for acquisition. The existing `CALIBRATE MAT` control may temporarily reset detector state, but it must not capture an empty reference frame or imply that the scene has been learned.
- Replace the `BALL DETECTED` badge with `CANDIDATE` while an unconfirmed proposal exists.
- Show `BALL LOCKED` only after the shape verifier and short temporal confirmation succeed.
- Show `NO BALL` when no verified proposal exists.
- Preserve the draggable launch-zone box. Detection is intentionally limited to that box; UI guidance must state that the intended hitting location belongs inside it.
- Preserve telemetry, extending it with closed-edge, radial-alignment, line-continuation, and confirmation-window values. The status must make rejection reasons inspectable during field tests.

## Handheld Behavior

The detector does not compare the current frame with an empty calibration frame. Candidate confirmation uses a brief window and radius-scaled association rather than a single stationary coordinate. Small translation and normal hand tremor therefore do not invalidate a real candidate. Large camera movement or blur causes confirmation to pause or reset instead of locking a low-quality guess.

Still-ball acquisition and flight tracking remain separate. This redesign removes the approximately one-frame-per-second acquisition bottleneck and unblocks later path testing, but it does not claim to complete the planned flight-tracker upgrade.

## Failure Handling

- No proposal passes shape verification: remain `NO BALL`/searching.
- One proposal passes but lacks temporal confirmation: show `CANDIDATE` and continue.
- Multiple similar verified proposals: remain searching and report ambiguity; never guess.
- Candidate becomes blurred or leaves the launch zone: decay/reset its confirmation without switching to a different weak proposal.
- Unsupported or implausibly small scale: report the scale limitation in debug telemetry rather than relaxing shape gates until markings pass.

## Testing Strategy

Implementation follows test-driven development. Each production change begins with a failing test that reproduces the required behavior.

### Synthetic unit fixtures

- Accept bright and dark isolated discs across supported radii.
- Accept a partially occluded disc with enough remaining perimeter evidence.
- Accept mild spherical shading and hard adjacent shadow without shifting to the shadow.
- Reject straight stripes, curved stripes, crosses, stripe endpoints, line intersections, and circular arcs that do not close.
- Reject textured grass clumps, isolated glare, and elongated blobs.
- Confirm a true candidate under radius-scaled position jitter.
- Do not lock two similarly plausible balls.
- Do not accumulate rejected static markings.

### Field regression fixtures

Create cropped, immutable test fixtures from the supplied field evidence where clean pixels are available:

- grass scene: the visible ball is accepted;
- empty range mat: the curved white marking selected by the current build is rejected;
- range scene: the visible white ball crop is accepted when evaluated inside a launch-zone fixture;
- background/line details are rejected.

Screenshot overlays must not be treated as camera pixels. If a clean raw crop is unavailable, use the screenshot only to derive the smallest uncontaminated positive or negative crop and retain synthetic coverage for the contaminated case. Future app crop logs become the preferred golden fixtures.

### Integration and performance

- `ZoneBallDetector` emits a candidate only after shape and temporal verification.
- `AutoShotTracker` receives mapped candidate coordinates correctly for rotated camera frames and launch-zone crops.
- Badge tests distinguish `NO BALL`, `CANDIDATE`, and `BALL LOCKED`.
- A deterministic JVM benchmark fixture records acquisition cost on a representative launch-zone size. The implementation must remove exhaustive expensive scoring; performance acceptance is based on phone telemetry, targeting at least 10 analyzed acquisition frames per second on the current range device before path testing resumes.
- Run focused detector/tracker tests, the complete debug unit-test suite, and `assembleDebug` before field handoff.

## Learned Verifier Extension

Crop logging continues for verified balls, rejected proposals, ambiguous proposals, and random negatives. Once the dataset covers multiple mats, grass, concrete, sunlight, shade, blur, and common false positives, a small offline-trained classifier may be inserted between `BallShapeVerifier` and `BallCandidateTracker`.

The model would consume a small candidate crop and output ball/not-ball confidence. It would run locally on the phone, require no network access, and would not learn or change during normal use. Classical geometry remains responsible for proposing locations and enforcing scale/temporal rules. Adding this model requires a separate reviewed design and measured validation against held-out field sessions.

## Scope Boundaries

Included:

- still-ball proposal, shape verification, short confirmation, debug telemetry, truthful badges, regression fixtures, and acquisition performance verification;
- preservation of existing launch-zone mapping and downstream tracker interfaces where practical.

Excluded:

- empty-frame/background calibration;
- a learned model in this implementation;
- full-frame semantic object detection;
- completion or field validation of flight-path tracking;
- colored-ball product support beyond the existing permissive chroma policy.
