# M2 Camera Capture Design

## Scope

M2 proves the CAMERA role can run the real capture spine on both target phones without adding detection yet. The milestone opens the rear camera, selects the best available YUV capture mode, reads the Y plane from ImageReader, shows a live preview, reports actual FPS, and provides a draggable launch-zone box that persists locally.

DISPLAY remains on the M1 networking screen except for compile-safe integration changes. CAMERA keeps the M1 WebSocket heartbeat running while the camera preview is active.

## Decisions

- Use Camera2 directly, not CameraX, because this project needs explicit YUV output, FPS range selection, and high-speed capture behavior.
- Choose the best available FPS per phone and display it honestly. M2 does not fail just because a device exposes less than 120 FPS for the selected YUV mode.
- Use a full-preview-first CAMERA screen with compact overlays.
- Persist the launch zone in local app preferences as normalized coordinates: left, top, width, height in 0.0..1.0.
- Avoid OpenCV and other heavy native dependencies.

## CAMERA UI

The CAMERA role replaces the placeholder copy with a full-screen preview surface. Lightweight overlays show:

- Actual measured FPS.
- Selected YUV size and FPS range.
- Y-plane frame count/status.
- M1 network status.
- Draggable launch-zone rectangle.
- Change role and Reset box controls.

The launch zone defaults to a bottom-center tee region when no saved value exists. Dragging clamps the rectangle inside the preview bounds and writes normalized coordinates back to preferences. Reset box restores the default and persists it.

## Capture Pipeline

Camera2 opens the rear-facing camera and enumerates supported YUV output sizes and FPS ranges at runtime. Selection prefers the highest practical FPS, then a moderate YUV size suitable for later luma processing. The implementation should log and display the selected mode rather than assuming a fixed rate.

Frames arrive as YUV_420_888 through an ImageReader. M2 reads only enough from plane 0 to prove Y-plane access, increments frame counters, and closes images promptly. No raw frame buffering is introduced in M2.

The preview uses a TextureView or equivalent Android view managed by the activity lifecycle. Starting CAMERA starts both the M1 network controller and the camera controller; leaving CAMERA stops both.

## Error Handling

If camera permission is missing, CAMERA shows a visible permission/error state instead of crashing. If no suitable rear camera or YUV mode is available, the screen shows a diagnostic message with the reason. If high-speed constrained sessions are unavailable or unsuitable, M2 falls back to the best standard repeating YUV capture mode and reports that fallback on screen.

Image processing must close every acquired image even on read errors. Camera resources, background threads, sessions, and network controllers must be released when changing role or destroying the activity.

## Testing

Unit tests should cover pure logic:

- FPS/range and size selection.
- Normalized launch-zone defaults, clamping, drag math, and persistence serialization.
- FPS counter calculations from frame timestamps.

Manual device verification gates the milestone:

- Build and unit tests pass.
- APK installs on Samsung Galaxy S23+ / SM-S916W and Nothing Phone 3a Pro / A059P.
- CAMERA preview runs on both devices.
- Selected FPS/mode is visible and logged.
- Y-plane frame count increments.
- Launch zone drags and persists after leaving and returning to CAMERA role.
- M1 DISPLAY discovery/heartbeat still works while CAMERA preview is active.
