// Packet dissection: decode raw output packet bytes into named header fields
// using deparser emit trace events and header type definitions from the IR.

import { state } from './state.js';
import { escapeHtml } from './encoding.js';

/**
 * Collect deparser_emit events from a trace tree, following the first
 * complete path (no fork branching).
 */
function collectEmitEvents(trace) {
  const emits = [];
  for (const event of trace.events || []) {
    if (event.deparser_emit) emits.push(event.deparser_emit);
  }
  // Follow the first fork branch if present.
  if (trace.fork_outcome?.branches?.length > 0) {
    emits.push(...collectEmitEvents(trace.fork_outcome.branches[0].subtree));
  }
  return emits;
}

/**
 * Format a field value as hex, with special formatting for common widths.
 * - 48-bit: MAC address (aa:bb:cc:dd:ee:ff)
 * - 32-bit: IPv4 dotted decimal (10.0.0.1)
 * - other: plain hex (0x0800)
 */
function formatFieldValue(bytes, bitwidth) {
  if (bytes.length === 0) return '0x0';
  if (bitwidth === 48 && bytes.length === 6) {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(':');
  }
  if (bitwidth === 32 && bytes.length === 4) {
    return Array.from(bytes).join('.');
  }
  const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  return '0x' + hex;
}

/**
 * Dissect a packet into decoded headers using deparser emit events.
 * Returns an array of {typeName, fields: [{name, bitwidth, value}]} objects,
 * plus a trailing `remainder` byte array for any unparsed bytes.
 */
export function dissectPacket(payload, trace) {
  if (!payload || !trace || !state.headerTypes) return null;

  const emits = collectEmitEvents(trace);
  if (emits.length === 0) return null;

  const headers = [];
  let offset = 0;

  for (const emit of emits) {
    const schema = state.headerTypes[emit.header_type];
    if (!schema || offset + emit.byte_length > payload.length) break;

    const headerBytes = payload.slice(offset, offset + emit.byte_length);
    const fields = [];
    let bitOffset = 0;

    for (const fieldDef of schema) {
      const fieldBits = fieldDef.bitwidth;
      if (fieldBits <= 0) continue;

      // Extract field bytes from the header's packed bits.
      const fieldBytes = extractBits(headerBytes, bitOffset, fieldBits);
      fields.push({
        name: fieldDef.name,
        bitwidth: fieldBits,
        value: formatFieldValue(fieldBytes, fieldBits),
      });
      bitOffset += fieldBits;
    }

    headers.push({ typeName: emit.header_type, fields });
    offset += emit.byte_length;
  }

  const remainder = offset < payload.length ? payload.slice(offset) : null;
  return { headers, remainder };
}

/**
 * Extract `bitCount` bits starting at `bitOffset` from a byte array,
 * returning a new Uint8Array of ceil(bitCount/8) bytes.
 */
function extractBits(bytes, bitOffset, bitCount) {
  const outLen = Math.ceil(bitCount / 8);
  const out = new Uint8Array(outLen);

  for (let i = 0; i < bitCount; i++) {
    const srcByteIdx = Math.floor((bitOffset + i) / 8);
    const srcBitIdx = 7 - ((bitOffset + i) % 8); // MSB-first
    const dstByteIdx = Math.floor(i / 8);
    const dstBitIdx = 7 - (i % 8);

    if (srcByteIdx < bytes.length && (bytes[srcByteIdx] >> srcBitIdx) & 1) {
      out[dstByteIdx] |= (1 << dstBitIdx);
    }
  }

  return out;
}

/**
 * Render a dissected packet as HTML. Shows each header as a collapsible
 * block with field names and values.
 */
export function renderDissectedPacket(dissection) {
  if (!dissection) return null;

  let html = '';
  for (const header of dissection.headers) {
    const fieldsHtml = header.fields.map(f =>
      `<div class="dissect-field"><span class="dissect-field-name">${escapeHtml(f.name)}</span> <span class="dissect-field-value">${escapeHtml(f.value)}</span></div>`
    ).join('');
    html += `<div class="dissect-header"><div class="dissect-header-name">${escapeHtml(header.typeName)}</div>${fieldsHtml}</div>`;
  }

  if (dissection.remainder && dissection.remainder.length > 0) {
    const hex = Array.from(dissection.remainder).map(b => b.toString(16).padStart(2, '0')).join(' ');
    html += `<div class="dissect-header"><div class="dissect-header-name">payload</div><div class="dissect-field"><span class="dissect-field-value">${hex}</span></div></div>`;
  }

  return html;
}
