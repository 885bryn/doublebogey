# M3 Detection Debug-First Design

## Scope Change

This updates the original M3 plan. The architecture brief describes M3 as one milestone containing detection, launch-zone triggering, motion-model tracking, occlusion bridging, and live overlay. For this codebase and the target use case, M3 will start with an inspectable detector pipeline before full auto-trigger/tracking tuning.

The reason is practical: outdoor golf-ball detection depends heavily on actual phone frame rate, exposure, distance, and range background. A complete trigger/tracker can fail silently if the first candidate detector is wrong. The first M3 slice must make candidate points and detector state visible on the preview so field tuning is possible.

## Current Slice

- Add a pure Kotlin Y-plane detector that compares the current frame with the previous frame.
- Detect small bright moving blobs using high-luma and high-motion thresholds.
- Return normalized candidate centroids and summary counts.
- Draw live candidate points on the CAMERA preview for debugging.
- Keep raw-frame buffering out of scope; only the previous luma frame is retained.

## Deferred Within M3

- Launch-zone speed trigger.
- Motion-model track continuity.
- Occlusion bridging.
- Shot finalization.

These remain part of M3, but they should be layered on after live candidate points look sane on both target phones.

## Testing

JVM unit tests cover the pure detector core with synthetic frames:

- no candidates before a previous frame exists;
- bright moving blobs produce normalized centroids;
- static bright blobs are rejected;
- large bright moving regions are rejected.

Manual field validation still gates the full M3 acceptance test.
