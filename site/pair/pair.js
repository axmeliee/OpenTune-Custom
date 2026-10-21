'use strict';
// Fragment stays on the device; no analytics, storage, network fetch or third-party scripts.
const payload = location.hash.slice(1);
history.replaceState(null, '', location.pathname);
if (/^[A-Za-z0-9_-]{40,512}$/.test(payload)) {
  const open = document.getElementById('open');
  open.href = `opentune://pair/#${payload}`;
  open.hidden = false;
} else {
  document.getElementById('status').textContent = 'Scan a fresh QR code from OpenTune → Settings → Account → Sign in with your phone.';
}
