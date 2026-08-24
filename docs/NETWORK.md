# Network Configuration Guide

How box-app and tablet-app find and talk to each other, and how to set that up in a store.

## Architecture

```
   Tablet (tablet-app)                Android Box (box-app)
   ─────────────────────              ──────────────────────
   Browses catalog,        HTTP GET    Hosts an HTTP server
   sends commands    ───────────────►  on port 8080
                                       Runs the camera + pose
                                       tracking + garment overlay
```

- **box-app is the server.** It hosts a small embedded HTTP server (NanoHTTPD) on port **8080** and owns the only copy of the product database (SQLite, on-device).
- **tablet-app is the client.** It never touches the camera or the database directly — it only sends HTTP requests to the box and displays whatever the box's catalog returns.
- Everything runs over the **local Wi-Fi network only**. No internet connection is used or required anywhere in this system.

## Requirements

- Both devices must be connected to the **same Wi-Fi network** (a normal store router/access point, or a dedicated one set up for this system — either works, as long as both devices can reach each other on the local subnet).
- The Wi-Fi network doesn't need internet access — it's only used for the two devices to reach each other.
- No special router configuration (port forwarding, static IP reservation, etc.) is required for a single Box + single Tablet setup, though a static IP or DHCP reservation for the Box is convenient so its address doesn't change between sessions (see below).

## Finding the Box's IP address

box-app displays its own local IP address on-screen at all times (top-left corner, e.g. `Box IP: 192.168.0.101:8080`). This is the address to enter into tablet-app.

If the Box's IP changes (e.g. after a router restart), just read the new one off the Box's screen and update it in tablet-app — it's a plain text field.

## Setting up tablet-app

1. Open tablet-app.
2. Enter the Box's IP address (just the address, e.g. `192.168.0.101` — the app appends `:8080` itself).
3. The IP is remembered automatically between app launches (saved locally on the tablet).
4. A status line under the IP field shows **"Connected"** (green) or **"Box unreachable — check IP and Wi-Fi"** (red) after every request — use this to confirm connectivity before assuming something else is wrong.

## Command reference

All requests are plain HTTP GET, no authentication (this is a closed local-network system, not internet-facing).

### `GET /products`
Returns the full local catalog as JSON:
```json
[
  {"id": 1, "name": "Blue Shirt", "category": "shirt", "colorKey": "blue"},
  {"id": 4, "name": "Classic Pants", "category": "pants", "colorKey": null}
]
```

### `GET /set`
Changes what's shown on the mirror. Any combination of query parameters can be sent together; only the ones present are changed — omitted parameters leave that setting as-is.

| Parameter | Values | Effect |
|---|---|---|
| `shirt` | `0` or `1` | Hide / show the shirt |
| `pants` | `0` or `1` | Hide / show the pants |
| `shirtColor` | `blue`, `red`, `green` | Switch the shirt's color |

Example: `http://192.168.0.101:8080/set?shirt=1&shirtColor=red` — shows the shirt in red.

## Troubleshooting

- **"Box unreachable" on the tablet**: confirm both devices show the same Wi-Fi network name in their system settings. A phone accidentally still connected to mobile data (with Wi-Fi off) will fail here even if it looks connected.
- **Box's IP keeps changing**: most routers assign a new address to a device after it's been offline for a while. Setting a DHCP reservation for the Box's MAC address in the router's admin page keeps its IP stable permanently.
- **Guest/isolated Wi-Fi networks**: some routers put Wi-Fi guest networks in "client isolation" mode, which blocks devices on the same network from reaching each other — this will break the connection even though both devices show as connected. Use the main network, not a guest network, for this system.
- **Testing without two physical devices**: install both apps on the same device and use `127.0.0.1` as the Box IP in tablet-app — this works because both apps share that device's own network stack.
