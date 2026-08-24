# Smart Virtual Fitting Mirror System

Two Android apps: `box-app` (runs on the Android Box + display + camera) and `tablet-app` (the store staff/customer controller).
They're separate Android Studio projects — open each one independently. They talk to each other over plain HTTP on the local Wi-Fi network; no internet connection is used anywhere.

## Status: Phase 1 MVP — working end to end
- Live camera feed with MediaPipe Pose Landmarker tracking the body, on-device, fully offline.
- Landmark smoothing that's resistant to MediaPipe's left/right ambiguity in symmetric poses (see `SymmetricPairSmoother`), not just naive per-point averaging.
- Shirt: multi-band mesh warp (shoulder/chest/waist/hem independently sized) tracking real body proportions.
- Pants: independent per-leg mesh (hip/knee/ankle) sharing a crotch seam, waist width matched directly to the shirt's hem so the two garments read as one continuous outfit, correct shirt-over-pants layering at the waistband.
- Any number of shirt/pants products can exist in the catalog (not a fixed color set) — the admin panel adds/removes them live; both garments toggle on/off independently.
- box-app hosts a local product catalog (SQLite) and a small HTTP command server.
- tablet-app fetches that catalog with real product thumbnails and lets you select/wear items, live.
- Admin panel (on the tablet): add, edit, and delete catalog products — including uploading a new garment photo from the tablet's gallery — without touching code. Newly added products are immediately wearable on the mirror, not just listed.
- Uploaded photos get automatic background removal (on-device, no ML model/internet — see `docs/NETWORK.md`) so staff can upload a plain phone photo directly instead of a pre-processed transparent PNG.

Shirt and pants art are real product-style photos (currently test/stock images, not the client's actual catalog) — swapping in the client's real photos later is just replacing the PNG assets in `box-app/app/src/main/assets/products/`, no code changes needed.

## Setup
See **[docs/INSTALL.md](docs/INSTALL.md)** for full setup steps, including a JDK compatibility issue worth reading before your first build.

Quick version:
1. Install Android Studio (developer.android.com/studio), Standard setup.
2. Open `box-app/` and `tablet-app/` as separate projects, build and run each onto a device.
3. **Both devices must be on the same Wi-Fi network** — see [docs/NETWORK.md](docs/NETWORK.md) for the full protocol and troubleshooting.

## How the apps talk to each other
Full reference in **[docs/NETWORK.md](docs/NETWORK.md)**. Short version: box-app hosts an HTTP server on port **8080** (`GET /products`, `GET /set?shirt=0|1&pants=0|1&shirtProductId=...&pantsProductId=...`, plus `/addProduct`, `/updateProduct`, `/deleteProduct`), tablet-app is the only client, both must share a Wi-Fi network, no internet involved anywhere.

## Known limitations (by design, for this phase)
- Only shirt + pants categories (no shoes/glasses/hats yet — glasses/hats need a face-landmark model in addition to Pose).
- No barcode scanning, no offline sync beyond "same Wi-Fi required."
- Garment images are real photos but not the client's actual products yet.
- Overlay is a 2D image warp, not cloth simulation — expected to look like a tracked garment silhouette, not photorealistic drape.
- **Measured Pose FPS is ~9-10fps on the current test phone (Sharp AQUOS R5G), against the spec's ~30fps target.** Investigated two optimization angles (capped analysis resolution, GPU delegate) — neither moved the number, meaning this looks like a genuine model/hardware ceiling on this device rather than a fixable inefficiency. Landmark smoothing partially compensates visually. Performance on the actual target Android Box is unknown and could be better or worse.
- Not yet tested on the actual target hardware (Android Box model, USB camera, tablet model are still unconfirmed with the client) — this is the single biggest open unknown for this project.

## Project layout
- `box-app/app/src/main/java/com/smartmirror/box/`
  - `MainActivity.kt` — camera preview, pose overlay composition (shirt + pants mesh geometry), screen state
  - `PoseLandmarkerHelper.kt` — MediaPipe Pose Landmarker wrapper (LIVE_STREAM mode)
  - `LandmarkSmoother.kt` — generic per-index exponential smoothing with low-confidence hold
  - `SymmetricPairSmoother.kt` — center+width smoothing for hip/ankle, immune to MediaPipe's left/right flicker in symmetric poses
  - `CommandServer.kt` — NanoHTTPD server: `/set`, `/products`, `/productImage`, `/addProduct`, `/updateProduct`, `/deleteProduct`
  - `ProductDbHelper.kt` / `Product.kt` — local SQLite catalog (CRUD)
  - `assets/products/` — bundled garment PNGs (transparent background) + the `.task` pose model; admin-uploaded images live in app-internal storage instead, since assets/ is read-only at runtime
- `tablet-app/app/src/main/java/com/smartmirror/tablet/`
  - `MainActivity.kt` — IP field, shirt/pants switches, product list with thumbnails, admin panel (add/edit/delete with image picker), all networking (OkHttp + Coil)
