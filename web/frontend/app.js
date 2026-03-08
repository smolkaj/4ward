// 4ward Playground — main application
// Vanilla JS, no build step. Monaco editor loaded from CDN.

'use strict';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const state = {
  p4info: null,         // loaded P4Info (JSON)
  entries: [],          // installed table entries (for display)
  cloneSessions: [],    // installed clone sessions
  lastTrace: null,      // last ProcessPacketWithTraceTree response
  editor: null,         // Monaco editor instance
  loadingExample: false, // guard for example loading
};

// ---------------------------------------------------------------------------
// API client
// ---------------------------------------------------------------------------

const api = {
  async compileAndLoad(source) {
    return post('/api/compile-and-load', { source });
  },

  async write(writeRequest) {
    return post('/api/write', writeRequest);
  },

  async read(tableId = 0) {
    const resp = await fetch(`/api/read?table_id=${tableId}`);
    return resp.json();
  },

  async sendPacket(ingressPort, payloadHex) {
    return post('/api/packet', { ingress_port: ingressPort, payload_hex: payloadHex });
  },

  async getPipeline() {
    const resp = await fetch('/api/pipeline');
    return resp.json();
  },
};

async function post(url, body) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await resp.json();
  if (!resp.ok || data.error) {
    throw new Error(data.error || `HTTP ${resp.status}`);
  }
  return data;
}

// ---------------------------------------------------------------------------
// Example P4 programs
// ---------------------------------------------------------------------------

const EXAMPLES = {
  basic_table: `// basic_table.p4 — simplest table-based v1model program.
//
// Looks up the Ethernet type in an exact-match table.
// If it matches, forward to the specified port; otherwise, drop.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t standard_metadata) {

    action drop() { mark_to_drop(standard_metadata); }
    action forward(bit<9> port) { standard_metadata.egress_spec = port; }

    table port_table {
        key = { hdr.ethernet.etherType : exact; }
        actions = { forward; drop; NoAction; }
        default_action = drop();
    }

    apply { port_table.apply(); }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,

  passthrough: `// passthrough.p4 — forward every packet to port 1.
//
// No tables, no parsing — the simplest possible v1model program.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

struct headers_t { ethernet_t ethernet; }
struct metadata_t {}

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {
    apply { smeta.egress_spec = 1; }
}

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) { apply {} }

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply { pkt.emit(hdr.ethernet); }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,

  mirror: `// mirror.p4 — IPv4 router with port mirroring.
//
// Parses Ethernet + IPv4, routes via LPM on the destination IP,
// and clones every forwarded packet to a mirror port for monitoring.
// The trace tree forks at the clone point, showing both paths.
//
// To try it:
//   1. Compile & Load
//   2. Add a route: ipv4_lpm with dstAddr=10.0.0.0/8, action=forward, port=1
//   3. Send an IPv4 packet (use the IPv4 preset)
//   4. Check the Trace tab — the clone fork shows both paths!
//
// The clone branch drops until a clone session is configured (via the
// P4Runtime PRE API). The original packet routes normally. Cloned
// copies get their source MAC rewritten to identify them as mirrors.

#include <core.p4>
#include <v1model.p4>

header ethernet_t {
    bit<48> dstAddr;
    bit<48> srcAddr;
    bit<16> etherType;
}

header ipv4_t {
    bit<4>  version;
    bit<4>  ihl;
    bit<8>  diffserv;
    bit<16> totalLen;
    bit<16> identification;
    bit<3>  flags;
    bit<13> fragOffset;
    bit<8>  ttl;
    bit<8>  protocol;
    bit<16> hdrChecksum;
    bit<32> srcAddr;
    bit<32> dstAddr;
}

struct headers_t {
    ethernet_t ethernet;
    ipv4_t     ipv4;
}

struct metadata_t {}

// --- Parser: Ethernet → IPv4 ---

parser MyParser(packet_in pkt, out headers_t hdr,
                inout metadata_t meta, inout standard_metadata_t smeta) {
    state start {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.etherType) {
            0x0800: parse_ipv4;
            default: accept;
        }
    }
    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition accept;
    }
}

control MyVerifyChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }
control MyComputeChecksum(inout headers_t hdr, inout metadata_t meta) { apply {} }

// --- Ingress: LPM routing + clone for mirroring ---

control MyIngress(inout headers_t hdr, inout metadata_t meta,
                  inout standard_metadata_t smeta) {

    action drop() { mark_to_drop(smeta); }

    action forward(bit<9> port) {
        smeta.egress_spec = port;
        hdr.ipv4.ttl = hdr.ipv4.ttl - 1;
    }

    table ipv4_lpm {
        key = { hdr.ipv4.dstAddr : lpm; }
        actions = { forward; drop; }
        default_action = drop();
    }

    apply {
        if (hdr.ipv4.isValid()) {
            ipv4_lpm.apply();
            // Mirror all forwarded traffic (clone to session 100).
            clone(CloneType.I2E, 32w100);
        }
    }
}

// --- Egress: tag mirrored copies ---

control MyEgress(inout headers_t hdr, inout metadata_t meta,
                 inout standard_metadata_t smeta) {

    action tag_mirror() {
        // Mark mirrored copies with a distinctive source MAC.
        hdr.ethernet.srcAddr = 48w0xDEAD00000000;
    }

    // instance_type: 0 = original, 1 = ingress clone
    table classify {
        key = { smeta.instance_type : exact; }
        actions = { NoAction; tag_mirror; }
        const entries = {
            0 : NoAction();   // original — pass through
            1 : tag_mirror(); // clone — tag it
        }
    }

    apply { classify.apply(); }
}

control MyDeparser(packet_out pkt, in headers_t hdr) {
    apply {
        pkt.emit(hdr.ethernet);
        pkt.emit(hdr.ipv4);
    }
}

V1Switch(MyParser(), MyVerifyChecksum(), MyIngress(),
         MyEgress(), MyComputeChecksum(), MyDeparser()) main;
`,
};

// ---------------------------------------------------------------------------
// P4 language definition for Monaco
// ---------------------------------------------------------------------------

function registerP4Language(monaco) {
  monaco.languages.register({ id: 'p4' });

  monaco.languages.setMonarchTokensProvider('p4', {
    keywords: [
      'action', 'apply', 'bit', 'bool', 'const', 'control', 'default',
      'else', 'enum', 'error', 'extern', 'false', 'header', 'header_union',
      'if', 'in', 'inout', 'int', 'match_kind', 'out', 'package', 'parser',
      'return', 'select', 'state', 'struct', 'switch', 'table', 'transition',
      'true', 'tuple', 'type', 'typedef', 'varbit', 'void',
    ],
    builtins: [
      'exact', 'ternary', 'lpm', 'range', 'optional',
      'packet_in', 'packet_out', 'mark_to_drop', 'NoAction',
      'standard_metadata_t', 'V1Switch',
    ],
    operators: [
      '=', '>', '<', '!', '~', '?', ':',
      '==', '<=', '>=', '!=', '&&', '||',
      '+', '-', '*', '/', '&', '|', '^', '%',
      '<<', '>>', '++', '--',
    ],
    symbols: /[=><!~?:&|+\-*/^%]+/,

    tokenizer: {
      root: [
        [/#include\b/, 'keyword.directive'],
        [/#\w+/, 'keyword.directive'],
        [/[a-zA-Z_]\w*/, {
          cases: {
            '@keywords': 'keyword',
            '@builtins': 'type.identifier',
            '@default': 'identifier',
          },
        }],
        { include: '@whitespace' },
        [/[{}()[\]]/, '@brackets'],
        [/@symbols/, {
          cases: {
            '@operators': 'operator',
            '@default': '',
          },
        }],
        [/\d+[wWsS]\d+/, 'number'],  // sized literals: 8w0, 16s5
        [/0[xX][0-9a-fA-F]+/, 'number.hex'],
        [/\d+/, 'number'],
        [/"([^"\\]|\\.)*$/, 'string.invalid'],
        [/"/, 'string', '@string'],
      ],
      whitespace: [
        [/[ \t\r\n]+/, 'white'],
        [/\/\*/, 'comment', '@comment'],
        [/\/\/.*$/, 'comment'],
      ],
      comment: [
        [/[^/*]+/, 'comment'],
        [/\*\//, 'comment', '@pop'],
        [/[/*]/, 'comment'],
      ],
      string: [
        [/[^\\"]+/, 'string'],
        [/\\./, 'string.escape'],
        [/"/, 'string', '@pop'],
      ],
    },
  });

  // Dark theme tuned for P4
  monaco.editor.defineTheme('4ward-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: 'c586c0' },
      { token: 'keyword.directive', foreground: '569cd6' },
      { token: 'type.identifier', foreground: '4ec9b0' },
      { token: 'comment', foreground: '6a9955' },
      { token: 'number', foreground: 'b5cea8' },
      { token: 'number.hex', foreground: 'b5cea8' },
      { token: 'string', foreground: 'ce9178' },
      { token: 'operator', foreground: 'd4d4d4' },
    ],
    colors: {
      'editor.background': '#0d1117',
      'editor.foreground': '#e6edf3',
      'editor.lineHighlightBackground': '#161b2240',
      'editorLineNumber.foreground': '#6e7681',
      'editorLineNumber.activeForeground': '#e6edf3',
      'editor.selectionBackground': '#264f7840',
      'editorCursor.foreground': '#58a6ff',
    },
  });
}

// ---------------------------------------------------------------------------
// Monaco editor initialization
// ---------------------------------------------------------------------------

function initEditor() {
  return new Promise((resolve) => {
    require.config({
      paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs' },
    });
    require(['vs/editor/editor.main'], function (monaco) {
      window.monaco = monaco;
      registerP4Language(monaco);

      const editor = monaco.editor.create(document.getElementById('editor-container'), {
        value: EXAMPLES.basic_table,
        language: 'p4',
        theme: '4ward-dark',
        minimap: { enabled: false },
        fontSize: 13,
        fontFamily: "var(--font-mono), 'Cascadia Code', 'JetBrains Mono', Consolas, monospace",
        lineNumbers: 'on',
        scrollBeyondLastLine: false,
        automaticLayout: true,
        tabSize: 4,
        renderLineHighlight: 'line',
        padding: { top: 8, bottom: 8 },
      });

      // Show initial example in dropdown; reset when user edits.
      document.getElementById('example-select').value = 'basic_table';
      editor.onDidChangeModelContent(() => {
        if (!state.loadingExample) {
          document.getElementById('example-select').value = '';
        }
      });

      // Ctrl/Cmd+Enter = Compile & Load
      editor.addAction({
        id: 'compile-and-load',
        label: 'Compile & Load',
        keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
        run: () => compileAndLoad(),
      });

      state.editor = editor;
      resolve(editor);
    });
  });
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

async function compileAndLoad() {
  const source = state.editor.getValue();
  setStatus('loading', 'Compiling…');
  log('Compiling P4 program…', 'info');
  const btn = document.getElementById('btn-compile');
  btn.disabled = true;
  btn.textContent = 'Compiling…';
  clearEditorDecorations();

  try {
    const data = await api.compileAndLoad(source);
    state.p4info = data.p4info;
    state.entries = [];
    state.cloneSessions = [];
    renderCloneSessionsList();
    const nTables = (data.p4info.tables || []).length;
    const nActions = (data.p4info.actions || []).length;
    setStatus('loaded', `${nTables} table${nTables !== 1 ? 's' : ''}, ${nActions} action${nActions !== 1 ? 's' : ''}`);
    log('Pipeline loaded successfully', 'success');
    renderTablesPanel();
    renderEntriesList();
    updateButtonStates();
    updateTabBadges();

    // Auto-read entries (pipeline may have static entries from const entries).
    try {
      const readData = await api.read();
      if (readData.entities && readData.entities.length > 0) {
        for (const entity of readData.entities) {
          const te = entity.table_entry;
          if (!te) continue;
          const table = data.p4info.tables.find(t => t.preamble.id === te.table_id);
          const action = te.action?.action;
          const actionInfo = action ? data.p4info.actions.find(a => a.preamble.id === action.action_id) : null;
          state.entries.push({
            tableName: table?.preamble.name || `table_${te.table_id}`,
            actionName: actionInfo?.preamble.name || 'unknown',
            matchFields: (te.match || []).map(m => {
              const mfInfo = table?.match_fields?.find(f => f.id === m.field_id);
              const val = m.exact?.value || m.lpm?.value || m.ternary?.value || m.optional?.value || '';
              return { name: mfInfo?.name || `field_${m.field_id}`, value: val ? base64ToHex(val) : '' };
            }),
            params: (action?.params || []).map(p => {
              const pInfo = actionInfo?.params?.find(pp => pp.id === p.param_id);
              return { name: pInfo?.name || `param_${p.param_id}`, value: p.value ? decodeParamValue(p.value) : '' };
            }),
            raw: te,
            isStatic: true,
          });
        }
        if (state.entries.length > 0) {
          renderEntriesList();
          updateTabBadges();
          log(`Pipeline loaded with ${state.entries.length} static entr${state.entries.length !== 1 ? 'ies' : 'y'}`, 'success');
        }
      }
    } catch (_) { /* static entry read is best-effort */ }
  } catch (e) {
    setStatus('error', 'Compilation failed');
    log(e.message, 'error');
    highlightCompileErrors(e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = 'Compile &amp; Load';
  }
}

async function addTableEntry() {
  const p4info = state.p4info;
  if (!p4info) return;

  const tableSelect = document.getElementById('entry-table');
  const actionSelect = document.getElementById('entry-action');
  const table = p4info.tables[tableSelect.selectedIndex];
  const actionRef = table.action_refs[actionSelect.selectedIndex];
  const action = p4info.actions.find(a => a.preamble.id === actionRef.id);

  try {
    // Build match fields
    const matchFields = [];
    for (const mf of table.match_fields || []) {
      const input = document.getElementById(`match-${mf.id}`);
      if (!input || !input.value.trim()) continue;
      const fieldMatch = { field_id: mf.id };
      const value = encodeValue(input.value.trim(), mf.bitwidth);

      switch (mf.match_type) {
        case 'EXACT':
          fieldMatch.exact = { value };
          break;
        case 'LPM': {
          const parts = input.value.trim().split('/');
          fieldMatch.lpm = {
            value: encodeValue(parts[0], mf.bitwidth),
            prefix_len: parseInt(parts[1] || mf.bitwidth, 10),
          };
          break;
        }
        case 'TERNARY': {
          const parts = input.value.trim().split('&&&');
          fieldMatch.ternary = {
            value: encodeValue(parts[0].trim(), mf.bitwidth),
            mask: parts[1] ? encodeValue(parts[1].trim(), mf.bitwidth) : allOnes(mf.bitwidth),
          };
          break;
        }
        case 'OPTIONAL':
          fieldMatch.optional = { value };
          break;
        default:
          fieldMatch.exact = { value };
      }
      matchFields.push(fieldMatch);
    }

    // Build action params
    const params = [];
    for (const param of action.params || []) {
      const input = document.getElementById(`param-${param.id}`);
      if (!input) continue;
      params.push({
        param_id: param.id,
        value: encodeValue(input.value.trim(), param.bitwidth),
      });
    }

    // Priority for ternary/range tables
    const priorityInput = document.getElementById('entry-priority');
    const needsPriority = (table.match_fields || []).some(
      mf => mf.match_type === 'TERNARY' || mf.match_type === 'RANGE'
    );

    const tableEntry = {
      table_id: table.preamble.id,
      match: matchFields,
      action: {
        action: {
          action_id: action.preamble.id,
          params,
        },
      },
    };

    if (needsPriority) {
      tableEntry.priority = parseInt(priorityInput.value, 10) || 1;
    }

    const writeRequest = {
      device_id: '1',
      updates: [{
        type: 'INSERT',
        entity: { table_entry: tableEntry },
      }],
    };

    await api.write(writeRequest);

    // Track the entry for display
    state.entries.push({
      tableName: table.preamble.name,
      actionName: action.preamble.name,
      matchFields: matchFields.map((mf, i) => ({
        name: table.match_fields[i]?.name || `field_${mf.field_id}`,
        value: document.getElementById(`match-${table.match_fields[i]?.id}`)?.value || '',
      })),
      params: params.map((p, i) => ({
        name: action.params?.[i]?.name || `param_${p.param_id}`,
        value: document.getElementById(`param-${action.params?.[i]?.id}`)?.value || '',
      })),
      raw: tableEntry,
    });

    renderEntriesList();
    updateTabBadges();
    log(`Entry added to ${table.preamble.name}`, 'success');

    // Clear input fields for next entry
    for (const mf of table.match_fields || []) {
      const input = document.getElementById(`match-${mf.id}`);
      if (input) input.value = '';
    }
    for (const param of action.params || []) {
      const input = document.getElementById(`param-${param.id}`);
      if (input) input.value = '';
    }
  } catch (e) {
    log(`Write failed: ${e.message}`, 'error');
  }
}

async function deleteTableEntry(index) {
  const entry = state.entries[index];
  if (!entry) return;

  try {
    // Build a minimal delete key: table_id + match fields
    const deleteEntry = {
      table_id: entry.raw.table_id,
      match: entry.raw.match,
    };
    if (entry.raw.priority) deleteEntry.priority = entry.raw.priority;

    await api.write({
      device_id: '1',
      updates: [{
        type: 'DELETE',
        entity: { table_entry: deleteEntry },
      }],
    });

    state.entries.splice(index, 1);
    renderEntriesList();
    updateTabBadges();
    log(`Entry deleted from ${entry.tableName}`, 'success');
  } catch (e) {
    log(`Delete failed: ${e.message}`, 'error');
  }
}

// ---------------------------------------------------------------------------
// Clone sessions
// ---------------------------------------------------------------------------

async function addCloneSession() {
  const sessionId = parseInt(document.getElementById('clone-session-id').value, 10);
  const egressPort = parseInt(document.getElementById('clone-egress-port').value, 10);

  if (isNaN(sessionId) || isNaN(egressPort)) {
    log('Enter valid session ID and egress port', 'error');
    return;
  }

  try {
    await api.write({
      device_id: '1',
      updates: [{
        type: 'INSERT',
        entity: {
          packet_replication_engine_entry: {
            clone_session_entry: {
              session_id: sessionId,
              replicas: [{ egress_port: egressPort }],
            },
          },
        },
      }],
    });

    state.cloneSessions.push({ sessionId, egressPort });
    renderCloneSessionsList();
    log(`Clone session ${sessionId} → port ${egressPort}`, 'success');
  } catch (e) {
    log(`Clone session failed: ${e.message}`, 'error');
  }
}

async function deleteCloneSession(index) {
  const session = state.cloneSessions[index];
  if (!session) return;

  try {
    await api.write({
      device_id: '1',
      updates: [{
        type: 'DELETE',
        entity: {
          packet_replication_engine_entry: {
            clone_session_entry: {
              session_id: session.sessionId,
            },
          },
        },
      }],
    });

    state.cloneSessions.splice(index, 1);
    renderCloneSessionsList();
    log(`Clone session ${session.sessionId} deleted`, 'success');
  } catch (e) {
    log(`Delete failed: ${e.message}`, 'error');
  }
}

function renderCloneSessionsList() {
  const list = document.getElementById('clone-sessions-list');
  if (state.cloneSessions.length === 0) {
    list.innerHTML = '';
    return;
  }

  list.innerHTML = state.cloneSessions.map((s, i) =>
    `<div class="entry-card">
      <div class="entry-table">Session ${s.sessionId}</div>
      <div class="entry-action">${'\u2192'} port ${s.egressPort}</div>
      <button class="btn btn-danger btn-delete" onclick="deleteCloneSession(${i})">&#x2715;</button>
    </div>`
  ).join('');
}

async function sendPacket() {
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

// ---------------------------------------------------------------------------
// Rendering: Tables panel
// ---------------------------------------------------------------------------

function renderTablesPanel() {
  const p4info = state.p4info;
  document.getElementById('tables-empty').classList.toggle('hidden', !!p4info);
  document.getElementById('tables-loaded').classList.toggle('hidden', !p4info);

  if (!p4info) return;

  const tables = p4info.tables || [];
  const tableSelect = document.getElementById('entry-table');
  tableSelect.innerHTML = tables.map(t =>
    `<option value="${t.preamble.id}">${t.preamble.name}</option>`
  ).join('');

  tableSelect.onchange = () => renderTableFields();
  renderTableFields();
}

function renderTableFields() {
  const p4info = state.p4info;
  if (!p4info) return;

  const tableSelect = document.getElementById('entry-table');
  const table = p4info.tables[tableSelect.selectedIndex];
  if (!table) return;

  // Match fields
  const matchDiv = document.getElementById('match-fields');
  matchDiv.innerHTML = (table.match_fields || []).map(mf => {
    const label = `${mf.name} (${mf.match_type.toLowerCase()}, ${mf.bitwidth}b)`;
    const placeholder = matchPlaceholder(mf);
    return `
      <div class="form-row">
        <label for="match-${mf.id}">${label}</label>
        <input id="match-${mf.id}" class="input-full mono" placeholder="${placeholder}">
      </div>`;
  }).join('');

  // Action select
  const actionSelect = document.getElementById('entry-action');
  const actionRefs = table.action_refs || [];
  actionSelect.innerHTML = actionRefs.map(ref => {
    const action = p4info.actions.find(a => a.preamble.id === ref.id);
    return `<option value="${ref.id}">${action?.preamble.name || ref.id}</option>`;
  }).join('');

  actionSelect.onchange = () => renderActionParams();
  renderActionParams();

  // Show/hide priority
  const needsPriority = (table.match_fields || []).some(
    mf => mf.match_type === 'TERNARY' || mf.match_type === 'RANGE'
  );
  document.getElementById('priority-row').style.display = needsPriority ? '' : 'none';
}

function renderActionParams() {
  const p4info = state.p4info;
  if (!p4info) return;

  const actionSelect = document.getElementById('entry-action');
  const actionId = parseInt(actionSelect.value, 10);
  const action = p4info.actions.find(a => a.preamble.id === actionId);

  const paramsDiv = document.getElementById('action-params');
  if (!action || !action.params || action.params.length === 0) {
    paramsDiv.innerHTML = '';
    return;
  }

  paramsDiv.innerHTML = action.params.map(p =>
    `<div class="form-row">
      <label for="param-${p.id}">${p.name} (${p.bitwidth}b)</label>
      <input id="param-${p.id}" class="input-full mono" placeholder="0">
    </div>`
  ).join('');
}

function matchPlaceholder(mf) {
  switch (mf.match_type) {
    case 'EXACT': return `e.g. ${mf.bitwidth <= 16 ? '0x0800' : '10.0.0.1'}`;
    case 'LPM': return `e.g. 10.0.0.0/24`;
    case 'TERNARY': return `value &&& mask`;
    case 'OPTIONAL': return `exact value or leave empty`;
    case 'RANGE': return `low..high`;
    default: return '';
  }
}

// ---------------------------------------------------------------------------
// Rendering: Entries list
// ---------------------------------------------------------------------------

function renderEntriesList() {
  const list = document.getElementById('entries-list');
  if (state.entries.length === 0) {
    list.innerHTML = '<p class="empty-hint">No entries installed.</p>';
    return;
  }

  list.innerHTML = state.entries.map((entry, i) => {
    const staticCls = entry.isStatic ? ' static' : '';
    const staticBadge = entry.isStatic ? '<span class="entry-static-badge">const</span>' : '';
    const deleteBtn = entry.isStatic ? '' : `<button class="btn btn-danger btn-delete" onclick="deleteTableEntry(${i})">&#x2715;</button>`;
    return `<div class="entry-card${staticCls}">
      ${staticBadge}
      <div class="entry-table">${entry.tableName}</div>
      <div class="entry-match">${entry.matchFields.map(m => `${m.name}=${m.value}`).join(', ')}</div>
      <div class="entry-action">${'\u2192'} ${entry.actionName}(${entry.params.map(p => `${p.name}=${p.value}`).join(', ')})</div>
      ${deleteBtn}
    </div>`;
  }).join('');
}

// ---------------------------------------------------------------------------
// Rendering: Packet results
// ---------------------------------------------------------------------------

function renderPacketResults(outputPackets) {
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

/** Format bytes as a hex dump with offset markers every 16 bytes. */
function formatHexDump(bytes) {
  if (bytes.length <= 16) {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(' ');
  }
  const lines = [];
  for (let off = 0; off < bytes.length; off += 16) {
    const chunk = bytes.slice(off, off + 16);
    const hex = Array.from(chunk).map(b => b.toString(16).padStart(2, '0')).join(' ');
    const offset = off.toString(16).padStart(4, '0');
    lines.push(`<span class="hex-offset">${offset}</span>  ${hex}`);
  }
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// Rendering: Trace tree
// ---------------------------------------------------------------------------

function renderTraceTree(trace) {
  const emptyEl = document.getElementById('trace-empty');
  const treeEl = document.getElementById('trace-tree');

  if (!trace) {
    emptyEl.classList.remove('hidden');
    treeEl.classList.add('hidden');
    return;
  }

  emptyEl.classList.add('hidden');
  treeEl.classList.remove('hidden');
  treeEl.innerHTML = renderTraceNode(trace, true);
}

function renderTraceNode(node, isRoot) {
  const cls = isRoot ? 'trace-node root' : 'trace-node';
  let html = `<div class="${cls}">`;

  // Group events by pipeline stage for collapsible sections.
  const events = node.events || [];
  let i = 0;
  while (i < events.length) {
    const event = events[i];
    if (event.pipeline_stage && event.pipeline_stage.direction === 'ENTER') {
      // Collect events until the matching EXIT.
      const stageName = event.pipeline_stage.stage_name;
      const stageEvents = [];
      i++;
      while (i < events.length) {
        if (events[i].pipeline_stage && events[i].pipeline_stage.direction === 'EXIT'
            && events[i].pipeline_stage.stage_name === stageName) {
          i++;
          break;
        }
        stageEvents.push(events[i]);
        i++;
      }
      html += renderStageGroup(stageName, event.pipeline_stage.stage_kind, stageEvents);
    } else {
      html += renderTraceEvent(event);
      i++;
    }
  }

  // Outcome
  if (node.packet_outcome) {
    html += renderPacketOutcome(node.packet_outcome);
  } else if (node.fork_outcome) {
    html += renderFork(node.fork_outcome);
  }

  html += '</div>';
  return html;
}

function renderStageGroup(name, kind, events) {
  const summary = summarizeStageEvents(events);
  const summarySpan = summary ? `<span class="stage-summary">${summary}</span>` : '';

  if (events.length === 0) {
    return `<div class="trace-stage">
      <div class="trace-collapse empty">
        <span class="stage-name">${name}</span> ${summarySpan}
      </div>
    </div>`;
  }

  return `<div class="trace-stage">
    <div class="trace-collapse" onclick="this.classList.toggle('collapsed')">
      <span class="stage-name">${name}</span> ${summarySpan}
    </div>
    <div class="trace-stage-body">
      ${events.map(e => renderTraceEvent(e)).join('')}
    </div>
  </div>`;
}

function summarizeStageEvents(events) {
  const parts = [];
  for (const e of events) {
    if (e.table_lookup) {
      const tl = e.table_lookup;
      parts.push(`${tl.table_name}: ${tl.hit ? 'hit' : 'miss'}`);
    } else if (e.parser_transition && e.parser_transition.to_state === 'accept') {
      parts.push('accept');
    } else if (e.parser_transition && e.parser_transition.to_state === 'reject') {
      parts.push('reject');
    } else if (e.mark_to_drop) {
      parts.push('drop');
    }
  }
  return parts.join(', ');
}

function renderTraceEvent(event) {
  const src = formatSourceInfo(event.source_info);
  if (event.packet_ingress) {
    return `<div class="trace-event ingress">▸ Packet ingress port ${event.packet_ingress.ingress_port}</div>`;
  }
  if (event.pipeline_stage) {
    const s = event.pipeline_stage;
    const dir = s.direction === 'ENTER' ? '→' : '←';
    const cls = s.direction === 'ENTER' ? 'stage-enter' : 'stage-exit';
    return `<div class="trace-event ${cls}">${dir} ${s.stage_name}</div>`;
  }
  if (event.parser_transition) {
    const pt = event.parser_transition;
    return `<div class="trace-event parser">parse: ${pt.from_state} → ${pt.to_state}${src}</div>`;
  }
  if (event.table_lookup) {
    const tl = event.table_lookup;
    const cls = tl.hit ? 'table-hit' : 'table-miss';
    const result = tl.hit ? 'hit' : 'miss';
    return `<div class="trace-event ${cls}">table ${tl.table_name}: ${result} → ${tl.action_name}${src}</div>`;
  }
  if (event.action_execution) {
    const ae = event.action_execution;
    const params = Object.entries(ae.params || {}).map(([k, v]) =>
      `${k}=${decodeParamValue(v)}`
    ).join(', ');
    const paramsStr = params ? `(${params})` : '';
    return `<div class="trace-event action">action ${ae.action_name}${paramsStr}${src}</div>`;
  }
  if (event.branch) {
    const b = event.branch;
    const dir = b.taken ? 'then' : 'else';
    const frag = event.source_info?.source_fragment || '';
    const condStr = frag ? `: <code>${escapeHtml(frag)}</code>` : '';
    return `<div class="trace-event branch">branch ${dir}${condStr}${src}</div>`;
  }
  if (event.extern_call) {
    const ec = event.extern_call;
    return `<div class="trace-event extern">extern ${ec.extern_instance_name}.${ec.method}()${src}</div>`;
  }
  if (event.mark_to_drop) {
    return `<div class="trace-event mark-to-drop">mark_to_drop()${src}</div>`;
  }
  if (event.clone) {
    return `<div class="trace-event clone">clone session ${event.clone.session_id}${src}</div>`;
  }
  if (event.clone_session_lookup) {
    const csl = event.clone_session_lookup;
    if (csl.session_found) {
      return `<div class="trace-event clone-session-hit">clone session ${csl.session_id} → port ${csl.egress_port}</div>`;
    }
    return `<div class="trace-event clone-session-miss">clone session ${csl.session_id}: not configured (clone dropped)</div>`;
  }
  return '';
}

function formatSourceInfo(si) {
  if (!si) return '';
  const fragment = si.source_fragment || '';
  const line = si.line || 0;
  if (!fragment && !line) return '';
  const linePrefix = line ? `L${line}` : '';
  const text = fragment
    ? (linePrefix ? `${linePrefix}: ${escapeHtml(fragment)}` : escapeHtml(fragment))
    : linePrefix;
  return ` <span class="trace-source" onclick="jumpToLine(${line})" title="Jump to line ${line} in editor">${text}</span>`;
}

function jumpToLine(line) {
  if (!state.editor || !line) return;
  state.editor.revealLineInCenter(line);
  state.editor.setPosition({ lineNumber: line, column: 1 });
  state.editor.focus();
}

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function renderPacketOutcome(outcome) {
  if (outcome.output) {
    const o = outcome.output;
    const bytes = o.payload ? base64ToUint8Array(o.payload) : new Uint8Array(0);
    return `<div class="trace-outcome output">→ output port ${o.egress_port} (${bytes.length} bytes)</div>`;
  }
  if (outcome.drop) {
    const reason = formatDropReason(outcome.drop.reason);
    return `<div class="trace-outcome drop">✕ drop (reason: ${reason})</div>`;
  }
  return '';
}

function renderFork(fork) {
  const reason = fork.reason?.toLowerCase().replace(/_/g, ' ') || 'fork';
  let html = `<div class="trace-fork-label">⑂ fork (${reason})</div>`;
  for (const branch of fork.branches || []) {
    html += `<div class="trace-branch-label">branch: ${branch.label}</div>`;
    html += renderTraceNode(branch.subtree, false);
  }
  return html;
}

function formatDropReason(reason) {
  switch (reason) {
    case 'MARK_TO_DROP': return 'mark_to_drop';
    case 'PARSER_REJECT': return 'parser reject';
    case 'PIPELINE_EXECUTION_LIMIT_REACHED': return 'execution limit';
    default: return reason || 'unknown';
  }
}

// ---------------------------------------------------------------------------
// Compilation error highlighting
// ---------------------------------------------------------------------------

/** Parse p4c error messages and highlight lines in the editor. */
function highlightCompileErrors(errorText) {
  if (!state.editor) return;
  const monaco = window.monaco;
  if (!monaco) return;

  // p4c error formats:
  //   "file.p4(line):message"            — most common
  //   "file.p4(line): error: message"    — explicit severity
  //   "file.p4:line:col: error: message" — GCC-style
  const markers = [];
  const lines = errorText.split('\n');
  for (const line of lines) {
    let lineNum, message, severity;

    // Format: file.p4(LINE):message or file.p4(LINE): error: message
    let m = line.match(/\.p4\((\d+)\):\s*(?:(error|warning):\s*)?(.*)/);
    if (!m) {
      // Format: file.p4:LINE:COL: error: message
      m = line.match(/\.p4:(\d+):\d+:\s*(?:(error|warning):\s*)?(.*)/);
    }
    if (m) {
      lineNum = parseInt(m[1], 10);
      severity = m[2] === 'warning'
        ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error;
      message = m[3] || line;
      markers.push({
        severity,
        startLineNumber: lineNum,
        startColumn: 1,
        endLineNumber: lineNum,
        endColumn: 1000,
        message,
      });
    }
  }

  if (markers.length > 0) {
    monaco.editor.setModelMarkers(state.editor.getModel(), 'p4c', markers);
    state.editor.revealLineInCenter(markers[0].startLineNumber);
  }
}

function clearEditorDecorations() {
  if (!state.editor || !window.monaco) return;
  window.monaco.editor.setModelMarkers(state.editor.getModel(), 'p4c', []);
}

// ---------------------------------------------------------------------------
// Value encoding helpers
// ---------------------------------------------------------------------------

/**
 * Encode a user-supplied value into a base64 string (protobuf bytes wire format in JSON).
 * Supports decimal, hex (0x...), and dotted notation (10.0.0.1, AA:BB:CC:DD:EE:FF).
 */
function encodeValue(input, bitwidth) {
  const byteLen = Math.ceil(bitwidth / 8);
  let bytes;

  if (input.includes('.')) {
    // Dotted decimal (IPv4-style)
    const parts = input.split('.').map(Number);
    bytes = new Uint8Array(parts);
  } else if (input.includes(':')) {
    // Colon-separated hex (MAC-style)
    const parts = input.split(':').map(s => parseInt(s, 16));
    bytes = new Uint8Array(parts);
  } else {
    // Numeric: decimal or hex (BigInt handles 0x prefix natively)
    let n = BigInt(input);
    bytes = new Uint8Array(byteLen);
    for (let i = byteLen - 1; i >= 0; i--) {
      bytes[i] = Number(n & 0xFFn);
      n >>= 8n;
    }
  }

  // Pad to expected byte length
  if (bytes.length < byteLen) {
    const padded = new Uint8Array(byteLen);
    padded.set(bytes, byteLen - bytes.length);
    bytes = padded;
  }

  return uint8ArrayToBase64(bytes);
}

function allOnes(bitwidth) {
  const byteLen = Math.ceil(bitwidth / 8);
  const bytes = new Uint8Array(byteLen).fill(0xFF);
  // Mask off excess bits in the most significant byte
  const excessBits = byteLen * 8 - bitwidth;
  if (excessBits > 0) {
    bytes[0] = (0xFF >> excessBits);
  }
  return uint8ArrayToBase64(bytes);
}

function uint8ArrayToBase64(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function base64ToUint8Array(b64) {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function base64ToHex(b64) {
  if (!b64) return '';
  const bytes = base64ToUint8Array(b64);
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(' ');
}

function decodeParamValue(b64) {
  if (!b64) return '0';
  const bytes = base64ToUint8Array(b64);
  let n = 0n;
  for (const b of bytes) n = (n << 8n) | BigInt(b);
  return n.toString();
}

// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------

function setStatus(statusClass, text) {
  const dot = document.getElementById('status-indicator');
  const textEl = document.getElementById('status-text');
  dot.className = 'status-dot ' + statusClass;
  textEl.textContent = text;
}

function log(message, level = '') {
  const bar = document.getElementById('log-bar');
  const text = document.getElementById('log-text');
  bar.className = level;
  text.textContent = message;
}

function updateButtonStates() {
  const hasPipeline = !!state.p4info;
  document.getElementById('btn-add-entry').disabled = !hasPipeline;
  document.getElementById('btn-add-clone').disabled = !hasPipeline;
  document.getElementById('btn-send-packet').disabled = !hasPipeline;
}

function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t =>
    t.classList.toggle('active', t.dataset.tab === name)
  );
  document.querySelectorAll('.tab-content').forEach(c =>
    c.classList.toggle('active', c.id === `tab-${name}`)
  );
}

function updateTabBadges() {
  const tablesTab = document.querySelector('.tab[data-tab="tables"]');
  const packetsTab = document.querySelector('.tab[data-tab="packets"]');
  const n = state.entries.length;
  tablesTab.textContent = n > 0 ? `Tables (${n})` : 'Tables';
  const lastOutputs = state.lastTrace?.output_packets?.length || 0;
  packetsTab.textContent = lastOutputs > 0 ? `Packets (${lastOutputs})` : 'Packets';
}

// ---------------------------------------------------------------------------
// Resize handle
// ---------------------------------------------------------------------------

function initResize() {
  const handle = document.getElementById('resize-handle');
  const editorPane = document.getElementById('editor-pane');
  const rightPane = document.getElementById('right-pane');
  let startX, startWidth;

  handle.addEventListener('mousedown', (e) => {
    startX = e.clientX;
    startWidth = editorPane.getBoundingClientRect().width;
    handle.classList.add('active');
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const onMouseMove = (e) => {
      const delta = e.clientX - startX;
      const newWidth = Math.max(200, Math.min(startWidth + delta, window.innerWidth - 200));
      editorPane.style.flex = `0 0 ${newWidth}px`;
      rightPane.style.flex = '1';
    };

    const onMouseUp = () => {
      handle.classList.remove('active');
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  });
}

// ---------------------------------------------------------------------------
// Packet presets
// ---------------------------------------------------------------------------

const PACKET_PRESETS = {
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

function applyPreset(name) {
  const preset = PACKET_PRESETS[name];
  if (preset) {
    document.getElementById('pkt-payload').value = preset;
  }
}

// ---------------------------------------------------------------------------
// Event bindings & init
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
  // Tabs
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  // Buttons
  document.getElementById('btn-compile').addEventListener('click', compileAndLoad);
  document.getElementById('btn-add-entry').addEventListener('click', addTableEntry);
  document.getElementById('btn-add-clone').addEventListener('click', addCloneSession);
  document.getElementById('btn-send-packet').addEventListener('click', sendPacket);

  // Example selector
  document.getElementById('example-select').addEventListener('change', (e) => {
    const example = EXAMPLES[e.target.value];
    if (example && state.editor) {
      state.loadingExample = true;
      state.editor.setValue(example);
      state.loadingExample = false;
    }
  });

  // Resize
  initResize();

  // Ctrl/Cmd+Enter on payload sends packet
  document.getElementById('pkt-payload').addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      sendPacket();
    }
  });

  // Initialize Monaco editor
  const isMac = navigator.platform.includes('Mac');
  const mod = isMac ? '⌘' : 'Ctrl';
  initEditor().then(() => {
    log(`Editor ready — ${mod}+Enter to compile & load`, 'info');
  });

  // Check if pipeline is already loaded (e.g. page refresh)
  api.getPipeline().then(data => {
    if (data.loaded) {
      state.p4info = data.p4info;
      setStatus('loaded', 'Pipeline loaded');
      renderTablesPanel();
    }
  }).catch(() => {});
});
