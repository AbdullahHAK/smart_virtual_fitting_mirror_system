# Smart Virtual Fitting Mirror System

Two Android apps: `box-app` (runs on the Android Box + display + camera) and `tablet-app` (the store staff/customer controller).
They're separate Android Studio projects — open each one independently. They talk to each other over plain HTTP on the local Wi-Fi network; no internet connection is used anywhere.

## Status: Phase 1 MVP — working end to end
- Live camera feed with MediaPipe Pose Landmarker tracking the body.
- Shirt + pants garment images warp onto the tracked body (mesh warp anchored at shoulders/elbows/hips/knees/ankles), toggle on/off independently, shirt has 3 color variants.
- box-app hosts a local product catalog (SQLite, seeded with 4 placeholder products) and a small HTTP command server.
- tablet-app fetches that catalog and lets you select/wear items and switch colors, live.

Current garment art is placeholder (programmatically drawn shapes, not real product photos) — swapping in real photos later is just replacing the PNG assets in `box-app/app/src/main/assets/products/`, no code changes needed.

## Setup
1. Install Android Studio (developer.android.com/studio), Standard setup (bundles JDK + SDK).
2. Open `box-app/` as a project (File > Open), let Gradle sync (needs internet the first time).
3. Connect your device via USB (Developer Options + USB debugging enabled on the device), hit Run.
4. Repeat for `tablet-app/` on a second device — or the same device via split-screen / same-device loopback (see below).
5. **Both devices must be on the same Wi-Fi network** for tablet-app to reach box-app. USB is only used to install from Android Studio.

If Android Studio shows a "Gradle wrapper not found" prompt on first open, accept it — it regenerates the missing files automatically.

### Testing with only one device
Install both apps on the same phone and use Android's split-screen (or just switch between them). In tablet-app, use IP `127.0.0.1` instead of the box's Wi-Fi IP.

### Sideloading without a USB data cable
Build → Build Bundle(s)/APK(s) → Build APK(s) in Android Studio (no device connection needed), then serve the resulting `.apk` file from a local HTTP server on your laptop (e.g. `python -m http.server` in the output folder) and download it from the target device's browser over the same Wi-Fi network. Enable "install unknown apps" for the browser when prompted.

## How the apps talk to each other
box-app hosts an HTTP server on port **8080**. It shows its IP address on-screen (top-left) while running.

- `GET /products` — returns the local catalog as JSON: `[{id, name, category, colorKey}, ...]`
- `GET /set?shirt=0|1&pants=0|1&shirtColor=blue|red|green` — any combination of params; only the ones present are changed.

tablet-app has an IP field (persisted between launches) and shows a connection status line after any request.

## Known limitations (by design, for this phase)
- Only shirt + pants categories (no shoes/glasses/hats yet — glasses/hats need a face-landmark model in addition to Pose).
- No barcode scanning, no full admin/catalog-editing UI, no offline sync beyond "same Wi-Fi required."
- Garment images are placeholders, not real product photography.
- Overlay is a 2D image warp (6-point mesh via `drawBitmapMesh`), not cloth simulation — expected to look like a tracked sticker, not photorealistic drape.
- Not yet tested on the actual target hardware (Android Box model, USB camera, tablet model are still unconfirmed with the client).

## Project layout
- `box-app/app/src/main/java/com/smartmirror/box/`
  - `MainActivity.kt` — camera preview, pose overlay composition, screen state
  - `PoseLandmarkerHelper.kt` — MediaPipe Pose Landmarker wrapper (LIVE_STREAM mode)
  - `CommandServer.kt` — NanoHTTPD server: `/set` and `/products` routes
  - `ProductDbHelper.kt` / `Product.kt` — local SQLite catalog
  - `assets/products/` — garment PNGs (transparent background) + the bundled `.task` pose model
- `tablet-app/app/src/main/java/com/smartmirror/tablet/`
  - `MainActivity.kt` — IP field, shirt/pants switches, product list, all networking (OkHttp)
