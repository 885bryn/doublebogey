# M1 Networking Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CAMERA advertises itself over Android NSD, serves a heartbeat over WebSocket, and DISPLAY discovers/connects with visible connection state.

**Architecture:** Keep M1 dependency-light by implementing a tiny server-side WebSocket handshake/frame sender over `ServerSocket`; use Android `NsdManager` for discovery/advertising and OkHttp for the DISPLAY WebSocket client. The UI remains one activity with role-specific controllers started only while the role screen is active.

**Tech Stack:** Kotlin, Android platform NSD, Java sockets, OkHttp WebSocket client, JVM unit tests for protocol/server behavior.

---

### Task 1: WebSocket Protocol Core

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/network/WebSocketProtocol.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/network/WebSocketProtocolTest.kt`

- [ ] **Step 1: Write protocol tests**

Verify RFC accept-key generation and unmasked server text-frame encoding.

- [ ] **Step 2: Implement protocol helpers**

Add `acceptKey()` and `textFrame()` helpers.

- [ ] **Step 3: Run unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: protocol tests pass.

### Task 2: Local WebSocket Heartbeat Server

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/network/LocalWebSocketServer.kt`
- Create: `app/src/test/java/com/doublebogey/golftracer/network/LocalWebSocketServerTest.kt`

- [ ] **Step 1: Write integration test**

Start the server on loopback port `0`, connect with OkHttp, and assert a heartbeat text payload arrives.

- [ ] **Step 2: Implement server**

Accept clients, perform the WebSocket upgrade, and send one heartbeat per second.

- [ ] **Step 3: Run unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: server integration test passes.

### Task 3: Android NSD Controllers and UI

**Files:**
- Create: `app/src/main/java/com/doublebogey/golftracer/network/GolfTracerNetwork.kt`
- Create: `app/src/main/java/com/doublebogey/golftracer/network/CameraNetworkController.kt`
- Create: `app/src/main/java/com/doublebogey/golftracer/network/DisplayNetworkController.kt`
- Modify: `app/src/main/java/com/doublebogey/golftracer/MainActivity.kt`

- [ ] **Step 1: Add network constants/state model**

Define service type, service name prefix, and status callbacks.

- [ ] **Step 2: Add CAMERA controller**

Start the local WebSocket server, register NSD with the dynamic port, and surface status.

- [ ] **Step 3: Add DISPLAY controller**

Discover NSD service, resolve host/port, connect with OkHttp WebSocket, surface heartbeat/status, and reconnect after closure.

- [ ] **Step 4: Wire role UI**

Start/stop the correct controller per role and show connection status text.

- [ ] **Step 5: Verify build and phones**

Run unit tests/build, install on both phones, set one Camera and one Display on the same hotspot, and verify no typed IP is required.
