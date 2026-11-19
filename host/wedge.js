#!/usr/bin/env node
// TabletBridge wedge (macOS/Linux): types barcode scans from the Q90 tablet
// into the focused window (e.g. Mendure DME) as a fast keystroke burst plus
// Enter - identical to a keyboard-wedge scanner.
//
// Usage: node wedge.js [passcode]
// Requires: adb on PATH, tablet connected via USB, Node 22+.
// macOS: the terminal needs Accessibility permission (System Settings >
// Privacy & Security > Accessibility) for System Events keystroke injection.

const { execFileSync } = require('child_process');
const os = require('os');

const passcode = process.argv[2] || process.env.TB_PASSCODE || process.env.TB_KEY;
if (!passcode) {
  console.error('Usage: node wedge.js <passcode>');
  process.exit(1);
}

execFileSync('adb', ['forward', 'tcp:8787', 'tcp:8787'], { stdio: 'inherit' });

const ws = new WebSocket(`ws://127.0.0.1:8787/ws?key=${encodeURIComponent(passcode)}`);
ws.binaryType = 'arraybuffer';

function typeMac(value) {
  execFileSync('osascript', [
    '-e', `tell application "System Events" to keystroke ${JSON.stringify(value)}`
  ]);
  execFileSync('osascript', ['-e', 'tell application "System Events" to key code 36']);
}

function typeLinux(value) {
  execFileSync('xdotool', ['type', '--delay', '15', value]);
  execFileSync('xdotool', ['key', 'Return']);
}

function typeScan(value) {
  try {
    if (os.platform() === 'darwin') typeMac(value);
    else typeLinux(value);
    console.log(`SCAN ${value} -> typed`);
  } catch (err) {
    console.error(`typing failed: ${err.message}`);
  }
}

ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'hello' }));
  console.log('Wedge active. Scans will be typed into the focused window. Ctrl+C to quit.');
};

ws.onmessage = (e) => {
  let msg;
  try { msg = JSON.parse(e.data); } catch { return; }
  if (msg.type === 'hello') {
    console.log(`connected: ${msg.device} (Android ${msg.android})`);
  } else if (msg.type === 'barcode') {
    typeScan(msg.value);
  }
};

ws.onclose = () => {
  console.log('disconnected');
  process.exit(0);
};
