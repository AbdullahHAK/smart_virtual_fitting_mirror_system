# Smart Virtual Fitting Mirror System

Two Android apps: `box-app` (runs on the Android Box + 65" display + camera) and `tablet-app` (the controller).
They're separate Android Studio projects — open each one independently.

## Status: Phase 0 — skeleton
Both apps currently just build and show a placeholder screen. Nothing else works yet.
This is deliberate: confirm the toolchain builds and runs on a real device before any camera/pose/networking code is added.

## Setup
1. Install Android Studio (developer.android.com/studio), Standard setup (bundles JDK + SDK).
2. Open `box-app/` as a project (File > Open). Let it sync (first sync downloads Gradle — needs internet once).
3. Connect your phone via USB with Developer Options + USB debugging enabled, hit Run. You should see "Smart Mirror — Box App (Phase 0 skeleton)".
4. Repeat steps 2-3 for `tablet-app/`.

If Android Studio shows a "Gradle wrapper not found" prompt on first open, accept it — it regenerates the missing wrapper files automatically.

## Next steps (in order, each tested before moving on)
1. Box app: CameraX preview showing the live camera feed.
2. Box app: MediaPipe Pose Landmarker running on the feed, drawing skeleton points (visual sanity check).
3. Box app: overlay a static shirt image aligned to shoulder/torso landmarks.
4. Box app: add pants overlay, tune both for stability.
5. Networking: box app runs a local HTTP server; tablet app sends a "select product" command over Wi-Fi.
6. Tablet app: basic product list + color switch wired to the same command.
7. Local DB (seeded products) on the box app, tablet reads/writes over the network API.
