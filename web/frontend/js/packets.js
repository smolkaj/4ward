// Packet injection: presets, send, result rendering.

import { state } from './state.js';
import { api } from './api.js';
import { base64ToUint8Array, formatHexDump } from './encoding.js';
import { log, switchTab, updateTabBadges } from './ui.js';
import { renderTraceTree } from './trace.js';

export const PACKET_PRESETS = {
  // Ethernet: dst=broadcast src=00:00:00:00:00:01
  ipv4: 'ff ff ff ff ff ff 00 00 00 00 00 01 08 00' +
        ' 45 00 00 1c 00 01 00 00 40 00 00 00 0a 00 00 01 0a 00 00 02' +
        ' de ad be ef',
  ipv6: 'ff ff ff ff ff ff 00 00 00 00 00 01 86 dd' +
        ' 60 00 00 00 00 04 3b 40' +
        ' 20 01 0d b8 00 00 00 00 00 00 00 00 00 00 00 01' +
        ' 20 01 0d b8 00 00 00 00 00 00 00 00 00 00 00 02' +
        ' de ad be ef',
  arp:  'ff ff ff ff ff ff 00 00 00 00 00 01 08 06' +
        ' 00 01 08 00 06 04 00 01 00 00 00 00 00 01 0a 00 00 01' +
        ' 00 00 00 00 00 00 0a 00 00 02',
  raw:  'ff ff ff ff ff ff 00 00 00 00 00 01 00 00 de ad be ef',
};

export function applyPreset(name) {
  const preset = PACKET_PRESETS[name];
  if (preset) {
    document.getElementById('pkt-payload').value = preset;
  }
}

export async function sendPacket() {
  const port = parseInt(document.getElementById('pkt-port').value, 10) || 0;
  const payloadHex = document.getElementById('pkt-payload').value.trim();

  if (!payloadHex) {
    log('Enter a packet payload in hex', 'error');
    return;
  }

  log('Sending packet…', 'info');

  try {
    const data = await api.sendPacket(port, payloadHex);
    state.lastTrace = data;

    renderPacketResults(data.output_packets);
    renderTraceTree(data.trace);

    const nOut = data.output_packets.length;
    log(`Packet processed: ${nOut} output${nOut !== 1 ? 's' : ''}`, 'success');
    updateTabBadges();

    // Auto-switch to trace tab
    switchTab('trace');
  } catch (e) {
    log(`Packet send failed: ${e.message}`, 'error');
  }
}

export function renderPacketResults(outputPackets) {
  const container = document.getElementById('packet-results');
  const div = document.getElementById('output-packets');

  if (!outputPackets || outputPackets.length === 0) {
    container.classList.remove('hidden');
    div.innerHTML = '<div class="output-drop">Packet dropped (no output)</div>';
    return;
  }

  container.classList.remove('hidden');
  div.innerHTML = outputPackets.map(pkt => {
    const bytes = pkt.payload ? base64ToUint8Array(pkt.payload) : new Uint8Array(0);
    const hex = formatHexDump(bytes);
    return `<div class="output-packet">
      <span class="output-port">Port ${pkt.egress_port}</span>
      <span class="output-bytes">(${bytes.length} bytes)</span>
      <div class="output-hex">${hex}</div>
    </div>`;
  }).join('');
}
