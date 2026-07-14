# DoubleBogey Golf Tracer

Single-APK Android app for a two-phone golf ball tracer.

## M0 Scope

- One Android app module.
- Runtime role selection for Camera or Display.
- Last selected role remembered locally.
- MVP permissions declared for camera, local networking, and nearby Wi-Fi discovery.

Later milestones add NSD/WebSocket networking, high-speed camera capture, detection, trajectory fitting, and tracer rendering.

## M2 Scope

- CAMERA opens a Camera2 preview.
- CAMERA selects and displays the best available YUV/FPS mode per device.
- CAMERA reads Y-plane frames and reports measured FPS/frame count.
- CAMERA shows a draggable, persisted launch-zone box.
- DISPLAY M1 discovery/heartbeat remains intact.

## M2 Verification

- Local verification: `rtk ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` passed.
- Physical-device verification passed on Nothing Phone 3a Pro / A059P: debug APK installed, CAMERA preview ran, selected mode/FPS and Y-plane frame count displayed, launch-zone drag/persist/reset worked.
- Physical-device verification passed on Samsung Galaxy S23+ / SM-S916W: debug APK installed, CAMERA preview ran, selected mode/FPS and Y-plane frame count displayed, launch-zone drag/persist/reset worked.
- M1 networking remained intact during M2 verification: DISPLAY discovery/heartbeat worked with CAMERA preview active on the shared hotspot.
