# Two-Phone Golf Ball Tracer — Architecture & Build Brief

> Paste this whole document into superpowers (GPT-5.5) as the build brief. Work milestone by milestone. Do not skip a milestone whose acceptance test has not passed.

---

## OBJECTIVE

Build a single Android app, installed as one APK on two phones, that traces a golf ball's flight. One phone (CAMERA role) is tripod-mounted, captures a white ball off the tee, detects its path frame-by-frame, fits a smooth trajectory, and sends it to the other phone (DISPLAY role) over the local network. The display phone renders a glowing arc over the captured scene and keeps it on screen until the next shot. No cloud, no external services, no paid hardware.

---

## CONTEXT (carry forward — these are locked decisions, do not re-litigate)

- **Target devices:** Samsung Galaxy S23+ and Nothing Phone 3a Pro, both on latest Android. Both are real targets.
- **Roles are swappable.** Either phone must be able to run as CAMERA or DISPLAY. The weaker device (3a Pro) is the performance floor for the CAMERA role — design must run acceptably on it, not just the S23+.
- **Network:** both phones sit on one phone's Wi-Fi hotspot. They share a local subnet and talk over local IP. Do NOT use Wi-Fi Direct (inconsistent across Samsung One UI vs Nothing OS). The phone hosting the hotspot may be running EITHER role — never assume the CAMERA is the hotspot host or the network server is on a fixed device.
- **Distribution:** sideload to the developer's own devices, manual updates. No Play Store. No store-review constraints on permissions.
- **Latency budget:** a few seconds from impact to arc-on-screen is acceptable. Real-time-while-airborne is NOT required (the user watches the real ball flight live; the on-screen arc is a review artifact). This permits a **capture-then-process** model — exploit it.
- **Persistence:** arc stays on screen until the next shot replaces it. MVP shows the single most-recent shot only.
- **Ball:** white balls only for the MVP. Colored-ball support is explicitly deferred.
- **History:** reviewing earlier shots is a planned post-MVP feature. The shot data model must accommodate it now (see Data Model) but the MVP must not build the history UI.

---

## CRITICAL RULES (MUST / NEVER)

- The app is ONE codebase, ONE APK. Role (CAMERA / DISPLAY) is chosen at runtime, not at build time.
- NEVER hardcode an IP address or assume which phone is the server. Use service discovery so DISPLAY finds CAMERA automatically (see Networking).
- NEVER hardcode the capture frame rate. Enumerate the device's supported high-speed ranges at runtime and pick the best available with a documented fallback ladder.
- NEVER add a cloud call, analytics SDK, or external network host. All traffic stays on the local subnet.
- Do all heavy detection work on the camera's luminance (Y) plane, not full RGB. A white ball is a high-luma object — this is faster and is the floor-device's lifeline.
- STOP and ask the developer before: changing the Shot JSON schema, adding a heavy native dependency (e.g. OpenCV), or touching anything outside the project directory.
- After each milestone, output: `✅ [milestone] — what was built — how it was verified on a physical device`. Do not advance past a milestone whose acceptance test failed.

---

## SYSTEM ARCHITECTURE

### Topology
```
[ Phone A: hotspot host ]            [ Phone B ]
   either role                          either role
        \                                  /
         \------ shared local subnet ------/
                       |
   DISPLAY discovers CAMERA via NSD (mDNS), connects to its
   WebSocket, receives one Shot object per detected shot.
```

### Role model
- Launch screen offers two buttons: **Camera** and **Display**. Remember the last choice. Either phone can pick either role any session.
- CAMERA role = the full capture → detect → fit → transmit pipeline + an embedded local server.
- DISPLAY role = discover → connect → receive Shot → render arc.
- Both roles ship in the same APK; the role just selects which subsystem boots.

### Networking
- **Discovery:** CAMERA advertises an NSD (`NsdManager`) service, e.g. `_golftracer._tcp`, on its assigned port. DISPLAY browses for that service and resolves the host/port. This decouples the connection from device identity and from who hosts the hotspot — directly satisfying the swap requirement. No typed IPs.
- **Transport:** CAMERA runs a small embedded WebSocket server (Ktor embedded server or NanoHTTPD-WS). DISPLAY connects as a WebSocket client (OkHttp). One trajectory per shot is tiny payload; latency budget is generous, so this is more than sufficient. A length-prefixed plain TCP socket is an acceptable alternative if it reduces build friction — agent's choice, document it.
- **Reconnect:** DISPLAY auto-reconnects if the socket drops; CAMERA keeps advertising while running. Show a clear connection-state indicator on both screens.

### CAMERA pipeline (capture-then-process)
1. **Capture** at the best supported high frame rate (see Detection). Feed frames as `YUV_420_888` via `ImageReader`; process the **Y plane only**.
2. **Live lightweight detection** runs per frame and appends `(tMs, x, y, confidence)` to an in-memory point list. Do NOT buffer raw frames (memory blowout at 240fps) — buffer points plus ONE clean background keyframe.
3. **Trigger / finalize** logic (launch-zone gating) decides when a shot starts and ends.
4. On finalize: outlier-reject → smooth → fit curve → grab background JPEG → assemble Shot JSON → push over WebSocket.

### DISPLAY pipeline
1. Receive Shot JSON.
2. Decode background keyframe, letterbox it to the screen.
3. Map normalized trajectory points onto the letterboxed image and render the glowing arc.
4. Keep it on screen until the next Shot arrives, then replace.

### Coordinate system
- CAMERA emits all points **normalized to [0,1]** relative to the capture frame, plus frame width/height/fps and the background image. DISPLAY maps normalized coords onto the letterboxed background. This is resolution-independent and survives the two phones having different screen sizes and the camera/display swapping devices.

---

## RECOMMENDED STACK (now that discovery is done)

- **Language:** Kotlin. Both targets are Android — pay no cross-platform tax.
- **Build:** single Gradle module, one APK. `minSdk 30` is safe (both devices on latest Android).
- **Camera:** Camera2 `CameraConstrainedHighSpeedCaptureSession` for high-speed capture, enumerated via `CameraCharacteristics` → `getHighSpeedVideoFpsRanges()`. Fall back to CameraX standard capture at max supported FPS if a device exposes no constrained high-speed range.
- **Vision:** custom Kotlin operating on the Y plane (frame differencing + luma threshold + connected-component centroid). No OpenCV for the MVP — fewer fragile native deps means fewer agentic build-failure cycles. OpenCV is a documented fallback ONLY if the custom detector underperforms, and only after asking.
- **Networking:** `NsdManager` (discovery) + Ktor embedded WebSocket server / OkHttp WebSocket client.
- **Rendering:** Android `Canvas` + `Paint` with `BlurMaskFilter` for the glow (or `RenderEffect` blur on API 31+, which both devices have).
- **Permissions:** `CAMERA`, `NEARBY_WIFI_DEVICES` (API 33+), `INTERNET`, `ACCESS_NETWORK_STATE`. No storage permission needed for MVP.

---

## DETECTION DESIGN (the crux)

White ball against an uncontrolled outdoor background. The hard window is the first fraction of a second after impact — ball low, fast, against ground clutter — because it sets launch angle.

**Frame rate:** enumerate and pick best, fallback ladder **240 → 120 → 60 → 30 fps**. Log the chosen rate. Higher is better; at 240fps a driver shot still yields tens of in-frame samples.

**Per-frame detector (Y plane):**
1. Frame-difference current vs previous Y plane (absolute difference) → isolates motion.
2. Threshold on high luma AND high motion → white ball is bright and fast; this rejects most static bright objects.
3. Connected components → candidate blobs. Filter by size (small, roughly ball-sized given expected distance) and reject blobs that are large/diffuse (clouds, glare patches).
4. Take the blob centroid as the candidate `(x, y)`.

**Launch-zone gating (also the primary false-positive defense):**
- Define a draggable **launch zone** rectangle the user positions over the tee region (default: bottom-center). Persist it.
- A shot STARTS when a qualifying bright/fast blob exits the launch zone above a speed threshold heading outward/upward.
- This rejects birds (slow/erratic, wrong origin), carts (slow, wrong origin), other golfers' balls (don't originate in YOUR launch zone), and waving flags (no net displacement).

**Tracking + occlusion bridging:**
- Maintain a motion model: last position + velocity. Each frame, match the blob nearest the predicted next position.
- If no blob matches (ball behind tree / club on follow-through / pole), **predict-forward** for up to K frames (~150 ms). Reacquire within K → bridge the gap and flag `occlusionBridged`. Gap exceeds K → finalize the shot and flag `lowConfidence`.
- This both tolerates occlusion and rejects spurious blobs far from prediction.

**Finalize** when: blob leaves frame, or no qualifying blob for N frames, or max-duration timeout (e.g. 2.5 s).

> If auto-trigger proves flaky in the field, a **tap-to-arm** fallback (user taps, app records a fixed window, then processes) is acceptable. You have autonomy to choose; document the decision.

---

## TRAJECTORY FITTING

Detected points are noisy, may have gaps, and the shot orientation in-frame is arbitrary (a ball coming toward the camera breaks any `y = f(x)` fit). So:
1. **Outlier reject:** fit a low-order parametric model `x(t), y(t)` (degree 2 each) and drop points whose residual exceeds a threshold (RANSAC-lite is fine).
2. **Smooth** the surviving points.
3. **Render curve:** Catmull-Rom (or cubic Bézier) spline through the smoothed points — gives the smooth arc directly, in any orientation, and naturally bridges small gaps. Optionally extrapolate the tail a short distance.
4. Store both the cleaned points and the curve control points in the Shot object.

---

## RENDERING THE GLOW (DISPLAY)

- Build a `Path` from the fitted curve control points (mapped from normalized coords onto the letterboxed background).
- **Glow layer:** thick stroke (~18–28 px, scaled to screen), bright color (white or cyan), `BlurMaskFilter(NORMAL)`.
- **Core layer:** thin stroke (~3–4 px), bright white, no blur, drawn on top.
- Optional polish: animate path reveal (animate stroke length) for a tracer "sweep." Nice-to-have, not required.
- Draw over the decoded background keyframe so the arc sits on the real scene. Keep the current Shot in state; replace on next Shot.

---

## DATA MODEL (future-proofed for history)

One Shot object, normalized coords, JSON over the wire. MVP keeps only the latest in memory. Post-MVP history just persists these objects (Room or JSON files) — no schema change needed.

```json
{
  "schemaVersion": 1,
  "shotId": "uuid",
  "capturedAtEpochMs": 0,
  "frame": { "width": 1280, "height": 720, "fps": 240 },
  "background": { "format": "jpeg", "encoding": "base64", "data": "..." },
  "points": [ { "tMs": 0, "x": 0.42, "y": 0.88, "confidence": 0.91 } ],
  "fit": { "type": "catmull-rom", "controlPoints": [[0.42,0.88],[0.5,0.6]] },
  "confidence": 0.0,
  "flags": ["occlusionBridged", "lowConfidence"]
}
```

---

## FAILURE-MODE HANDLING (mapped to discovery risks)

- **Glare / over-exposure:** lock auto-exposure once a shot is armed; if a frame is near-uniformly saturated (huge bright fraction), skip it rather than treat the glare patch as a ball.
- **Sky/ball brightness collision:** rely on **motion + size + launch-zone origin**, not brightness alone — a bright sky is static and large, the ball is small, fast, and originates at the tee.
- **Low light (golden hour / overcast):** the frame-diff + adaptive threshold approach degrades gracefully; expose a sensitivity setting and surface the per-shot confidence so the user knows when a trace is weak.
- **Occlusion:** handled by predict-forward bridging (above), with a `lowConfidence` flag when a gap is large.
- **False positives:** handled by launch-zone gating + speed threshold + motion-model continuity (above).
- **Two-skin variability (One UI vs Nothing OS):** never assume identical camera/high-speed support — enumerate at runtime; never assume identical hotspot/network behavior — use NSD discovery, not fixed IPs.

---

## BUILD MILESTONES (each independently testable — gate on its acceptance test)

**M0 — Scaffold.** Single Gradle project, one APK, role-select launch screen (Camera / Display), permissions wired.
- *Accept:* installs on both phones; both role screens reachable.

**M1 — Networking spine.** CAMERA advertises NSD + runs WebSocket server; DISPLAY discovers, connects, shows connection state; send a dummy heartbeat payload.
- *Accept:* on the shared hotspot, DISPLAY auto-finds CAMERA with no typed IP, and it works regardless of which phone hosts the hotspot and which role each plays.

**M2 — Capture + luma access.** Open the best supported high-speed session (enumerate; ladder 240→120→60→30), feed Y-plane frames to a processing callback, show a live preview with on-screen reported FPS and a draggable, persisted launch-zone box.
- *Accept:* preview runs on both devices; chosen FPS logged; launch zone drags and persists.

**M3 — Detection + tracking.** Frame-diff + luma/motion threshold + blob centroid; launch-zone gating + speed trigger; motion-model tracking with occlusion bridging; overlay detected points live on preview for debugging.
- *Accept:* hitting a white ball produces a clean point track on preview; slow/background motion does not trigger a shot.

**M4 — Fit + transmit.** On finalize: outlier-reject → smooth → build curve → capture background keyframe → emit Shot JSON over the socket.
- *Accept:* DISPLAY receives one well-formed Shot object per shot with sane points.

**M5 — Render.** DISPLAY draws the glowing arc over the background keyframe, persists until next shot.
- *Accept:* arc is smooth and glowing, replaces on next shot, end-to-end impact→arc within a few seconds.

**M6 — Field hardening.** FPS-fallback robustness, glare/over-exposure guard, confidence display, socket reconnect.
- *Accept:* works at a real range across midday, golden hour, and overcast.

**Deferred (do NOT build now; just leave the seams):** shot-history persistence + review UI, colored-ball detection mode, multi-shot overlay.

---

## AGENT OPERATING RULES

- Work one milestone at a time. After each: `✅ [milestone] — built X — verified by Y on device Z`.
- Build and run on a physical phone at each milestone; do not advance on a failed acceptance test.
- Resolve at runtime, never hardcode: supported high-speed FPS ranges, server IP/port (via NSD), screen/frame dimensions.
- STOP and ask before: changing the Shot schema, adding OpenCV or any heavy native dep, adding any external/cloud network call, or modifying files outside the project.
- Keep the solution minimal — implement what the current milestone requires, nothing speculative.

---

## OPEN ITEMS TO VERIFY DURING BUILD

1. Confirm each device's max constrained high-speed FPS via runtime enumeration (the Nothing 3a Pro's high-speed ceiling is the one to check — design must not depend on a specific number).
2. Decide auto-trigger vs tap-to-arm after M3 field-testing; document the call.
3. Confirm `NEARBY_WIFI_DEVICES` is sufficient for NSD on both skins, or whether legacy location permission is still needed for discovery on either device.
4. Tune launch-zone default size/position and the speed threshold against real range footage.
