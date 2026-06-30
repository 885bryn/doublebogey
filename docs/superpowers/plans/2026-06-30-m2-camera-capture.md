# M2 Camera Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the M2 CAMERA capture spine: full-screen Camera2 preview, best available YUV/FPS selection, Y-plane frame access, live FPS diagnostics, and a draggable persisted launch-zone box while preserving M1 networking.

**Architecture:** Keep Android framework code thin and push testable behavior into pure Kotlin helpers. Add a focused `camera` package for capture mode selection, FPS counting, launch-zone math, overlay drawing, and a Camera2 controller. `MainActivity` owns role lifecycle and starts both the existing M1 network controller and the new camera controller for CAMERA role.

**Tech Stack:** Kotlin, Android Camera2, TextureView, ImageReader with YUV_420_888, SharedPreferences, JVM unit tests with kotlin.test, existing Gradle Android app module.

---

## File Structure

- Create `app/src/main/java/com/doublebogey/golftracer/camera/CaptureMode.kt`: pure data model for chosen capture size, FPS range, and high-speed/standard mode.
- Create `app/src/main/java/com/doublebogey/golftracer/camera/CaptureModeSelector.kt`: pure selection logic from candidate modes.
- Create `app/src/test/java/com/doublebogey/golftracer/camera/CaptureModeSelectorTest.kt`: JVM tests for FPS/size fallback behavior.
- Create `app/src/main/java/com/doublebogey/golftracer/camera/FpsCounter.kt`: pure rolling FPS calculation from frame timestamps.
- Create `app/src/test/java/com/doublebogey/golftracer/camera/FpsCounterTest.kt`: JVM tests for measured FPS and empty state.
- Create `app/src/main/java/com/doublebogey/golftracer/camera/LaunchZone.kt`: normalized rectangle model, default, clamp, drag, serialization helpers.
- Create `app/src/test/java/com/doublebogey/golftracer/camera/LaunchZoneTest.kt`: JVM tests for default, drag clamping, and persistence values.
- Create `app/src/main/java/com/doublebogey/golftracer/camera/LaunchZoneOverlayView.kt`: Android View that draws and drags the normalized box.
- Create `app/src/main/java/com/doublebogey/golftracer/camera/CameraCaptureController.kt`: Camera2 lifecycle, mode enumeration, TextureView preview, ImageReader Y-plane read/count, status callback.
- Modify `app/src/main/java/com/doublebogey/golftracer/MainActivity.kt`: full-preview-first CAMERA layout and lifecycle wiring for both network and camera controllers.

---

### Task 1: Pure Capture Mode Selection

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/CaptureMode.kt`
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/CaptureModeSelector.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/CaptureModeSelectorTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `CaptureModeSelectorTest.kt` with tests that assert the selector chooses the highest FPS candidate, prefers high-speed mode when FPS ties, chooses the candidate closest to 1280x720 among equal FPS modes, and falls back to the best standard mode when no high-speed candidate exists.

- [ ] **Step 2: Run tests to verify RED**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.CaptureModeSelectorTest"`

Expected: FAIL because `CaptureModeSelector`, `CaptureModeCandidate`, and `CaptureMode` do not exist.

- [ ] **Step 3: Implement minimal model and selector**

Implement `CaptureModeCandidate(width, height, minFps, maxFps, highSpeed)`, `CaptureMode(width, height, minFps, maxFps, highSpeed)`, and `CaptureModeSelector.select(candidates)`. The comparator must sort by max FPS, then high-speed true, then closeness to 1280x720.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.CaptureModeSelectorTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/camera/CaptureMode.kt app/src/main/java/com/doublebogey/golftracer/camera/CaptureModeSelector.kt app/src/test/java/com/doublebogey/golftracer/camera/CaptureModeSelectorTest.kt`

Run: `rtk git commit -m "feat: add camera capture mode selection"`

---

### Task 2: FPS Counter

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/FpsCounter.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/FpsCounterTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `FpsCounterTest.kt` with one test for `0.0` before two frames exist and one test where five timestamps from 1.0s to 2.0s report `4.0` FPS.

- [ ] **Step 2: Run tests to verify RED**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.FpsCounterTest"`

Expected: FAIL because `FpsCounter` does not exist.

- [ ] **Step 3: Implement minimal FPS counter**

Create `FpsCounter(maxSamples: Int = 30)` with `recordFrame(timestampNs: Long): Double` and `reset()`. Keep a rolling `ArrayDeque<Long>`, return zero for fewer than two timestamps or non-positive elapsed time, otherwise compute `(sampleCount - 1) * 1_000_000_000.0 / elapsedNs`.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.FpsCounterTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/camera/FpsCounter.kt app/src/test/java/com/doublebogey/golftracer/camera/FpsCounterTest.kt`

Run: `rtk git commit -m "feat: add camera fps counter"`

---

### Task 3: Launch Zone Model

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/LaunchZone.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/camera/LaunchZoneTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `LaunchZoneTest.kt` with tests for default values `left=0.35`, `top=0.68`, `width=0.30`, `height=0.18`; drag clamping inside the unit rectangle; invalid persisted values restoring the default; and pixel drag conversion from a 1280x720 view.

- [ ] **Step 2: Run tests to verify RED**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.LaunchZoneTest"`

Expected: FAIL because `LaunchZone` does not exist.

- [ ] **Step 3: Implement launch-zone math**

Create `LaunchZone(left, top, width, height)` with `Default`, `dragBy(deltaX, deltaY)`, `dragByPixels(deltaX, deltaY, viewWidth, viewHeight)`, `isValid()`, and `fromPersisted(left, top, width, height)`. Clamp movement to keep the full rectangle inside `0.0..1.0`.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest --tests "com.doublebogey.golftracer.camera.LaunchZoneTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/camera/LaunchZone.kt app/src/test/java/com/doublebogey/golftracer/camera/LaunchZoneTest.kt`

Run: `rtk git commit -m "feat: add launch zone model"`

---

### Task 4: Launch Zone Overlay View

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/LaunchZoneOverlayView.kt`

- [ ] **Step 1: Create overlay view**

Create an Android `View` that draws the current `LaunchZone` as a green stroked rectangle with translucent fill. Handle `ACTION_DOWN` and `ACTION_MOVE`; on move, call `launchZone.dragByPixels(...)`, update the property, invalidate, and invoke `onLaunchZoneChanged` when the value changes.

- [ ] **Step 2: Run build**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/camera/LaunchZoneOverlayView.kt`

Run: `rtk git commit -m "feat: add draggable launch zone overlay"`

---

### Task 5: Camera2 Capture Controller

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/camera/CameraCaptureController.kt`

- [ ] **Step 1: Create controller shell**

Create `CameraCaptureController(context, textureView, onStatus)` with `start()` and `stop()`. It must check camera permission, start a `HandlerThread`, wait for `TextureView` availability, find the rear camera, select a YUV mode using `CaptureModeSelector`, and report visible status messages.

- [ ] **Step 2: Add ImageReader Y-plane access**

Inside the controller, create `ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, 3)`. In `setOnImageAvailableListener`, acquire latest image, read plane 0 enough to prove luma access, increment frame count, update measured FPS with `FpsCounter`, invoke `onStatus`, and always close the image in `finally`.

- [ ] **Step 3: Add repeating preview capture**

Use the `TextureView.surfaceTexture` as the preview surface and the `ImageReader.surface` as the analysis surface. Create a repeating `TEMPLATE_RECORD` request with both surfaces and `CONTROL_AE_TARGET_FPS_RANGE` set to the selected range. If constrained high-speed support is added later, keep the standard session fallback intact.

- [ ] **Step 4: Run build**

Run: `rtk ./gradlew.bat :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/camera/CameraCaptureController.kt`

Run: `rtk git commit -m "feat: add camera2 capture controller"`

---

### Task 6: CAMERA Role Full-Preview UI and Lifecycle Wiring

**Files:**
- Modify: `app/src/main/java/com/doublebogey/golftracer/MainActivity.kt`

- [ ] **Step 1: Split role lifecycle fields**

Replace the single `activeController` field with `activeNetworkController: RoleNetworkController?` and `activeCameraController: CameraCaptureController?`. Update `stopActiveController()` to stop camera first, then network.

- [ ] **Step 2: Add CAMERA preview screen**

Add `showCameraScreen()` that builds a `FrameLayout` containing a full-size `TextureView`, full-size `LaunchZoneOverlayView`, top-left camera status text, top-right network status text, bottom-left `Change role`, and bottom-right `Reset box`.

- [ ] **Step 3: Persist launch zone**

Add four SharedPreferences keys for left, top, width, and height. Add `readLaunchZone()` and `persistLaunchZone(zone)` helpers. Wire `LaunchZoneOverlayView.onLaunchZoneChanged` to persist the box. Wire `Reset box` to `LaunchZone.Default` and persist it.

- [ ] **Step 4: Start both CAMERA controllers**

For CAMERA role, start `CameraNetworkController` into `activeNetworkController` and `CameraCaptureController` into `activeCameraController`. For DISPLAY role, keep existing display behavior with only `DisplayNetworkController`.

- [ ] **Step 5: Run tests/build**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

Run: `rtk git add app/src/main/java/com/doublebogey/golftracer/MainActivity.kt`

Run: `rtk git commit -m "feat: wire camera preview role screen"`

---

### Task 7: Final Verification and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Run full local verification**

Run: `rtk ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install on both phones**

Run: `rtk adb devices`

Run: `rtk adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: both target devices install the debug APK.

- [ ] **Step 3: Verify CAMERA on each phone**

On Samsung SM-S916W and Nothing A059P, open CAMERA and confirm preview, visible selected mode/FPS, incrementing Y-plane frame count, draggable launch zone, persistence after leaving/returning, and Reset box behavior.

- [ ] **Step 4: Verify M1 network still works**

Put both phones on the same hotspot. Run one as CAMERA and the other as DISPLAY. Expected: DISPLAY discovers CAMERA with no typed IP and receives heartbeat while CAMERA preview is active.

- [ ] **Step 5: Document verification status**

Append an M2 section to `README.md` listing built M2 behavior and exact physical-device verification results. If physical testing has not been run yet, write `Pending physical-device verification on Samsung SM-S916W and Nothing A059P.`

- [ ] **Step 6: Commit README update**

Run: `rtk git add README.md`

Run: `rtk git commit -m "docs: record m2 verification status"`
