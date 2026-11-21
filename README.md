# TabletBridge — Q90 barcode scanner + remote control bridge

Turns the **Q90 Android 14 tablet** into a camera barcode scanner and controllable
peripheral for a Windows/Mac/Linux PC. Scans land directly in
**Mendure DME** (`../Relational-Database`) exactly like a USB keyboard-wedge
scanner — no changes to Mendure DME are needed.

```
Q90 tablet (TabletBridge APK)                 PC
┌─────────────────────────────┐   USB/adb    ┌────────────────────────────────┐
│ Camera → JPEG frames        │◄────────────►│ wedge.ps1 / wedge.js           │
│ ML Kit barcode decode       │  port 8787   │  → types scan + Enter into     │
│ Remote input injection      │              │    the focused window (DME)    │
│ (optional) screen mirroring │              │ browser console (camera view,  │
└─────────────────────────────┘              │  remote taps, scan log)        │
                                             └────────────────────────────────┘
```

## What you get

- **Barcode scanning**: point the tablet camera at an equipment label; the value
  is decoded on-device (ML Kit, offline) and typed into the PC's focused window
  as a fast keystroke burst + Enter. Mendure DME's wedge detector
  (`scan.js`) accepts it: `AB-1234`, `AB1234`, `ab-1234` all normalize to `AB-1234`.
- **Live camera view**: open `http://127.0.0.1:8787` in the PC browser to watch
  the feed, see scan history with beep + bounding box, toggle torch, switch
  front/back camera.
- **Remote input**: clicks/drags on the (optional) screen pane inject
  taps/swipes/text on the tablet; Back/Home/Recents buttons included.
- **Passcode**: every session requires the 6-digit passcode shown in the app
  (it regenerates if the app is reinstalled — read it from the app's status
  screen). The browser console picks it up automatically; only the wedge
  scripts need it passed in.
- **Diagnostics**: `http://<tablet-ip>:8787/status` shows the server's last 60
  connection events plus persistent app diagnostics (survives crashes).
- **Low bandwidth**: the console's 🐢 toggle halves resolution/fps for
  marginal Wi-Fi or Bluetooth links.

## Tablet setup (one time)

1. Enable **Developer options**: Settings → About tablet → tap *Build number* 7×.
2. Enable **USB debugging** in Developer options.
3. Install the APK (from CI artifact or `adb install app-debug.apk`).
4. Open TabletBridge → **Start bridge** (grants camera permission).
5. **Enable input control**: tap the Accessibility button and enable
   *TabletBridge Input Control* (required only for remote taps/swipes/text).
6. Note the **passcode** shown on the status panel.

## PC setup

1. Download [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
   and put `adb` on your PATH (or edit the scripts to use its full path).
2. Connect the tablet by USB (accept the "Allow USB debugging?" prompt on the tablet).

### Scanning into Mendure DME (wedge)

Windows (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File host\wedge.ps1                       # USB (adb forward)
powershell -ExecutionPolicy Bypass -File host\wedge.ps1 -Passcode 123456 -Host_ 192.168.43.1   # Wi-Fi direct
```

macOS/Linux (Node 22+; macOS asks for Accessibility permission once):
```bash
node host/wedge.js 123456           # USB (adb forward)
node host/wedge.js 123456 192.168.43.1   # Wi-Fi direct
```

The wedge keeps running; scan a label with the tablet camera and the code is
typed into whatever window has focus. Click into Mendure DME's Home scan field
(or leave focus on a non-text view — both work by design) and scan.

### Viewing / remote control (browser console)

```bash
adb forward tcp:8787 tcp:8787
# then open http://127.0.0.1:8787 and enter the passcode
```

## Building the APK

No local Android toolchain needed. Push this folder to GitHub and the included
workflow (`.github/workflows/android.yml`) builds a debug APK on every push
(or manually via *Run workflow*), then attaches it as the `TabletBridge-apk`
artifact. Download and sideload it.

To build locally instead: open this folder in Android Studio (JDK 17) and run
`assembleDebug`.

## Notes & limitations

- **Wi-Fi**: the bridge also listens on the LAN while the tablet is on Wi-Fi
  (`http://<tablet-ip>:8787`) — passcode still required. Unplug/deny if that
  is not wanted; USB (`adb forward`) is the primary, safer path.
- **Screen sharing** is optional (user-consented each time) and only needed
  when you want to see the tablet screen to tap it. Camera scanning works
  without it.
- **Autofocus**: fixed-focus budget cameras may struggle with close-up scans;
  hold labels 10–20 cm away, use the torch for glossy labels.
- The accessibility input path can't send arbitrary key events — text is set
  on the focused editable field; Back/Home/Recents/tap/swipe/long-press are
  supported.
- If the first keystrokes get eaten (e.g. focus lands in a text field), the
  scan is intentionally ignored by Mendure DME's `isTextEntry` rule — scan
  again with focus in the Home scan field.
