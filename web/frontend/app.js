// 4ward Playground — main application
// Vanilla JS, no build step. Monaco editor loaded from CDN.

'use strict';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

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

  // Control graph state.
  controlGraph: null,       // {controls: {name: {nodes, edges}}} from /api/control-graph
  activeGraphControl: null, // which control's graph is currently shown

  // Trace view state.
  traceView: 'tree',        // active trace view: 'tree', 'json', or 'proto'
  traceJson: null,           // cached JSON.stringify of trace (computed lazily)

  // Playback state.
  playbackEvents: [],       // flattened list of {event, stageName, line, eventIdx}
  playbackPos: -1,          // current position (-1 = before first event)
  _playbackDecorations: [], // Monaco editor decoration IDs
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

  sai_middleblock: '/examples/sai_middleblock.p4',
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
  state.playbackEvents = [];
  state.playbackPos = -1;
  state.controlGraph = null;
  state.activeGraphControl = null;
  document.getElementById('control-graph').classList.add('hidden');
  document.getElementById('graph-resize-handle').classList.add('hidden');

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

    // Render the control-flow graph (included in the compile response).
    if (data.control_graph) {
      state.controlGraph = data.control_graph;
      renderControlGraph();
    }
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
  const rawEl = document.getElementById('trace-raw');
  const toolbarEl = document.getElementById('trace-toolbar');

  if (!trace) {
    emptyEl.classList.remove('hidden');
    treeEl.classList.add('hidden');
    rawEl.classList.add('hidden');
    toolbarEl.classList.add('hidden');
    return;
  }

  emptyEl.classList.add('hidden');
  toolbarEl.classList.remove('hidden');
  _eventIdx = 0;
  treeEl.innerHTML = renderTraceNode(trace, true);

  initPlayback(trace);

  // Clear cached JSON (computed lazily in switchTraceView).
  state.traceJson = null;

  // Show the currently selected view.
  switchTraceView(state.traceView || 'tree');
}

function switchTraceView(view) {
  state.traceView = view;
  const treeEl = document.getElementById('trace-tree');
  const rawEl = document.getElementById('trace-raw');

  document.querySelectorAll('.trace-view-btn').forEach(btn =>
    btn.classList.toggle('active', btn.dataset.view === view)
  );

  const showTree = view === 'tree';
  treeEl.classList.toggle('hidden', !showTree);
  rawEl.classList.toggle('hidden', showTree);

  if (view === 'json') {
    if (!state.traceJson) {
      state.traceJson = JSON.stringify(state.lastTrace?.trace, null, 2) || '';
    }
    rawEl.textContent =
      '// proto-file: simulator/simulator.proto\n// proto-message: fourward.sim.v1.TraceTree\n\n'
      + state.traceJson;
  } else if (view === 'proto') {
    rawEl.textContent =
      '# proto-file: simulator/simulator.proto\n# proto-message: fourward.sim.v1.TraceTree\n\n'
      + (state.lastTrace?.trace_proto || '');
  }
}

function formatForkReason(reason) {
  return (reason || 'fork').toLowerCase().replace(/_/g, ' ');
}

// ---------------------------------------------------------------------------
// Animated trace playback
// ---------------------------------------------------------------------------

/**
 * Flatten trace events into a linear list for stepping through.
 * Returns: [{ event, stageName, line, eventIdx }]
 *
 * Walks events the same way renderTraceNode does so eventIdx values match
 * the data-event-idx attributes in the DOM. Pipeline stage ENTER/EXIT events
 * are consumed by renderTraceNode's grouping logic and never get a
 * data-event-idx, so stage ENTER entries have eventIdx: null — the pipeline
 * diagram provides visual feedback for those instead.
 */
function flattenTraceEvents(trace) {
  const result = [];
  let currentStage = null;
  let eventIdx = 0;

  function addEvent(event) {
    const line = event.source_info?.line || 0;
    result.push({ event, stageName: currentStage, line, eventIdx });
    eventIdx++;
  }

  function walkNode(node) {
    const events = node.events || [];
    let i = 0;
    while (i < events.length) {
      const event = events[i];
      if (event.pipeline_stage && event.pipeline_stage.direction === 'ENTER') {
        // Grouped stage — ENTER is consumed by the renderer (not rendered via
        // renderTraceEvent), so it has no data-event-idx in the DOM. But we
        // still want it as a steppable event — the pipeline diagram provides
        // the visual feedback.
        const stageName = event.pipeline_stage.stage_name;
        currentStage = stageName;
        result.push({ event, stageName, line: 0, eventIdx: null });
        i++;
        while (i < events.length) {
          if (events[i].pipeline_stage && events[i].pipeline_stage.direction === 'EXIT'
              && events[i].pipeline_stage.stage_name === stageName) {
            currentStage = null;
            i++; // EXIT consumed
            break;
          }
          addEvent(events[i]);
          i++;
        }
      } else {
        addEvent(event);
        i++;
      }
    }
  }

  walkNode(trace);

  // Add the packet outcome (drop/output) as the final steppable event.
  if (trace.packet_outcome) {
    result.push({
      event: { packet_outcome: trace.packet_outcome },
      stageName: null, line: 0, eventIdx: null,
    });
  }

  return result;
}

function initPlayback(trace) {
  state.playbackEvents = flattenTraceEvents(trace);
  state.playbackPos = -1;
  updatePlaybackUI();
}

function ensureTraceVisible() {
  switchTab('trace');
  switchTraceView('tree');
}

function stepForward() {
  if (state.playbackPos < state.playbackEvents.length - 1) {
    ensureTraceVisible();
    state.playbackPos++;
    applyPlaybackState();
  }
}

function stepBack() {
  if (state.playbackPos >= 0) {
    ensureTraceVisible();
    state.playbackPos--;
    applyPlaybackState();
  }
}

function resetPlayback() {
  state.playbackPos = -1;
  applyPlaybackState();
  // Restore the full pipeline diagram.
  if (state.lastTrace?.trace) {
    updatePipelineDiagram(state.lastTrace.trace);
  }
}

function applyPlaybackState() {
  const pos = state.playbackPos;
  const events = state.playbackEvents;

  // Clear trace tree highlights (diagram is updated below via renderDiagram).
  document.querySelectorAll('.playback-highlight').forEach(
    el => el.classList.remove('playback-highlight')
  );

  if (pos < 0) {
    // Before first event — clear everything.
    clearPlaybackEditorHighlight();
    updatePlaybackUI();
    return;
  }

  const current = events[pos];

  // Highlight the current step in the trace tree. Regular events have a
  // data-event-idx; stage headers use data-stage; the outcome uses data-outcome.
  const selector = current.eventIdx != null
    ? `[data-event-idx="${current.eventIdx}"]`
    : current.event.pipeline_stage
      ? `.trace-stage[data-stage="${current.stageName}"]`
      : current.event.packet_outcome
        ? '[data-outcome]'
        : null;
  if (selector) {
    const el = document.querySelector(selector);
    if (el) {
      // Expand any collapsed ancestors so the element is visible.
      let parent = el.parentElement;
      while (parent) {
        if (parent.classList.contains('trace-stage-body')) {
          const toggle = parent.previousElementSibling;
          if (toggle && toggle.classList.contains('collapsed')) {
            toggle.classList.remove('collapsed');
          }
        }
        parent = parent.parentElement;
      }
      el.classList.add('playback-highlight');
      el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }

  // Switch graph tab to match the current pipeline stage.
  if (current.stageName && current.stageName !== state.activeGraphControl
      && state.controlGraph && state.controlGraph[current.stageName]) {
    showControlGraph(current.stageName);
  }

  // Highlight the active node in the control graph.
  if (current.event.parser_transition) {
    const pt = current.event.parser_transition;
    highlightGraphNode(pt.from_state, pt.to_state);
  } else if (current.event.table_lookup) {
    highlightGraphNode(current.event.table_lookup.table_name);
  } else if (current.event.action_execution) {
    // Highlight the table that triggered this action by searching backward.
    let found = false;
    for (let i = pos - 1; i >= 0; i--) {
      if (events[i].event.table_lookup) {
        highlightGraphNode(events[i].event.table_lookup.table_name);
        found = true;
        break;
      }
    }
    if (!found) highlightGraphNode(null);
  } else if (current.event.branch) {
    const frag = current.event.source_info?.source_fragment || '';
    highlightGraphCondition(frag);
  } else {
    highlightGraphNode(null);
  }

  // Highlight source line in editor with a visible decoration.
  // For action executions, find the action definition rather than the call site.
  let line = current.line;
  if (current.event.action_execution && state.editor) {
    const actionName = current.event.action_execution.action_name;
    if (actionName && actionName !== 'NoAction') {
      const model = state.editor.getModel();
      if (model) {
        // The action name from the trace may be qualified (e.g. "ctrl.set_vrf")
        // or renamed by the midend (e.g. "set_nexthop_id_0" for a duplicate).
        // Try progressively looser matches to find the definition in P4 source.
        const shortName = actionName.includes('.') ? actionName.split('.').pop() : actionName;
        const baseName = shortName.replace(/_\d+$/, ''); // strip midend rename suffix
        const escaped = baseName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const match = model.findMatches(`\\baction\\s+${escaped}\\s*\\(`, false, true, true, null, false);
        if (match.length > 0) line = match[0].range.startLineNumber;
      }
    }
  }
  if (line && state.editor && window.monaco) {
    state.editor.revealLineInCenter(line);
    state._playbackDecorations = state.editor.deltaDecorations(
      state._playbackDecorations,
      [{
        range: new window.monaco.Range(line, 1, line, 1),
        options: {
          isWholeLine: true,
          className: 'playback-line-highlight',
          glyphMarginClassName: 'playback-glyph',
        },
      }]
    );
  } else {
    clearPlaybackEditorHighlight();
  }

  updatePlaybackUI();
}

function clearPlaybackEditorHighlight() {
  if (state.editor && state._playbackDecorations) {
    state._playbackDecorations = state.editor.deltaDecorations(
      state._playbackDecorations, []
    );
  }
}

function updatePlaybackUI() {
  const pos = state.playbackPos;
  const events = state.playbackEvents;

  document.getElementById('btn-step-back').disabled = pos < 0;
  document.getElementById('btn-step-forward').disabled = pos >= events.length - 1;
  document.getElementById('btn-reset').disabled = pos < 0;

  const posText = document.getElementById('playback-position');
  posText.textContent = events.length > 0
    ? `${Math.max(0, pos + 1)} / ${events.length}`
    : '';
}

function renderTraceNode(node, isRoot) {
  const cls = isRoot ? 'trace-node root' : 'trace-node';
  let html = `<div class="${cls}">`;

  // Group events by pipeline stage for collapsible sections.
  const events = node.events || [];
  let i = 0;

  // Render top-level events before the first stage (e.g., packet_ingress).
  while (i < events.length && !(events[i].pipeline_stage && events[i].pipeline_stage.direction === 'ENTER')) {
    html += renderTraceEvent(events[i]);
    i++;
  }

  // Render stages in an indented container.
  let hasStages = false;
  let stagesHtml = '';
  while (i < events.length) {
    const event = events[i];
    if (event.pipeline_stage && event.pipeline_stage.direction === 'ENTER') {
      hasStages = true;
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
      stagesHtml += renderStageGroup(stageName, event.pipeline_stage.stage_kind, stageEvents);
    } else {
      stagesHtml += renderTraceEvent(events[i]);
      i++;
    }
  }
  if (hasStages) {
    html += `<div class="trace-stages">${stagesHtml}</div>`;
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
  if (events.length === 0) {
    return `<div class="trace-stage" data-stage="${name}">
      <div class="trace-collapse empty">
        <span class="stage-name">${name}</span>
      </div>
    </div>`;
  }

  return `<div class="trace-stage" data-stage="${name}">
    <div class="trace-collapse" onclick="this.classList.toggle('collapsed')">
      <span class="stage-name">${name}</span>
    </div>
    <div class="trace-stage-body">
      ${renderStageEvents(events)}
    </div>
  </div>`;
}

/** Returns true if this event is an effect of a preceding action (not a top-level event). */
function isActionEffect(event) {
  return event.mark_to_drop || event.extern_call || event.clone || event.clone_session_lookup;
}

/**
 * Render stage events, grouping action effects as collapsible children
 * of their preceding action_execution.
 */
function renderStageEvents(events) {
  let html = '';
  let i = 0;
  while (i < events.length) {
    if (events[i].action_execution) {
      const actionEvent = events[i];
      i++;
      const effects = [];
      while (i < events.length && isActionEffect(events[i])) {
        effects.push(events[i]);
        i++;
      }
      html += renderActionWithEffects(actionEvent, effects);
    } else {
      html += renderTraceEvent(events[i]);
      i++;
    }
  }
  return html;
}

function renderActionWithEffects(event, effects) {
  const ae = event.action_execution;
  const idx = _eventIdx++;
  const line = event.source_info?.line || 0;
  const attr = `data-event-idx="${idx}"${line ? ` data-line="${line}"` : ''}`;
  const params = Object.entries(ae.params || {}).map(([k, v]) =>
    `${k}=${decodeParamValue(v)}`
  ).join(', ');
  const paramsStr = params ? `(${params})` : '()';

  let detail = '';
  if (effects.length > 0) {
    const effectsHtml = effects.map(e => renderTraceEvent(e)).join('');
    detail = `<div class="trace-entry-detail" onclick="event.stopPropagation(); this.classList.toggle('expanded')">${effectsHtml}</div>`;
  }

  return `<div ${attr} class="trace-event action">Executed ${ae.action_name}${paramsStr}${detail}</div>`;
}

// Global counter for stamping data-event-idx on rendered trace events.
let _eventIdx = 0;

function renderTraceEvent(event) {
  const idx = _eventIdx++;
  const line = event.source_info?.line || 0;
  const attr = `data-event-idx="${idx}"${line ? ` data-line="${line}"` : ''}`;
  let text = '';

  if (event.packet_ingress) {
    return `<div data-event-idx="${idx}" class="trace-event ingress">Received packet on port ${event.packet_ingress.ingress_port}</div>`;
  }
  if (event.pipeline_stage) {
    const s = event.pipeline_stage;
    const dir = s.direction === 'ENTER' ? '→' : '←';
    const cls = s.direction === 'ENTER' ? 'stage-enter' : 'stage-exit';
    return `<div data-event-idx="${idx}" class="trace-event ${cls}">${dir} ${s.stage_name}</div>`;
  }
  if (event.parser_transition) {
    const pt = event.parser_transition;
    const condition = pt.select_value
      ? `${pt.select_expression ? escapeHtml(pt.select_expression) + ' = ' : ''}${escapeHtml(pt.select_value)}`
      : '';
    text = condition
      ? `Parsed ${pt.from_state} → ${pt.to_state} on ${condition}`
      : `Parsed ${pt.from_state} → ${pt.to_state}`;
  } else if (event.table_lookup) {
    const tl = event.table_lookup;
    const cls = tl.hit ? 'table-hit' : 'table-miss';
    const result = tl.hit ? 'hit' : 'miss';
    const detail = tl.hit && tl.matched_entry ? formatMatchedEntry(tl) : '';
    return `<div ${attr} class="trace-event ${cls}">Applied ${tl.table_name}: ${result} → ${tl.action_name}${detail}</div>`;
  } else if (event.action_execution) {
    // Handled by renderActionWithEffects when inside a stage; fallback for stray events.
    const ae = event.action_execution;
    const params = Object.entries(ae.params || {}).map(([k, v]) =>
      `${k}=${decodeParamValue(v)}`
    ).join(', ');
    text = `Executed ${ae.action_name}${params ? `(${params})` : '()'}`;
  } else if (event.branch) {
    const result = event.branch.taken ? 'true' : 'false';
    const frag = event.source_info?.source_fragment || '';
    text = frag ? `Branched on ${escapeHtml(frag)} → ${result}` : `Branched → ${result}`;
  } else if (event.extern_call) {
    const ec = event.extern_call;
    text = `Called ${ec.extern_instance_name}.${ec.method}()`;
  } else if (event.mark_to_drop) {
    text = 'Marked to drop';
  } else if (event.clone) {
    text = `Cloned to session ${event.clone.session_id}`;
  } else if (event.clone_session_lookup) {
    const csl = event.clone_session_lookup;
    text = csl.session_found
      ? `Resolved clone session ${csl.session_id} → port ${csl.egress_port}`
      : `Clone session ${csl.session_id} not found (dropped)`;
    const cls = csl.session_found ? 'clone-session-hit' : 'clone-session-miss';
    return `<div ${attr} class="trace-event ${cls}">${text}</div>`;
  } else {
    return '';
  }

  const cls = event.parser_transition ? 'parser'
    : event.action_execution ? 'action'
    : event.branch ? 'branch'
    : event.extern_call ? 'extern'
    : event.mark_to_drop ? 'mark-to-drop'
    : event.clone ? 'clone' : '';
  return `<div ${attr} class="trace-event ${cls}">${text}</div>`;
}

/** Format a matched table entry as a collapsible detail block. */
function formatMatchedEntry(tl) {
  const entry = tl.matched_entry;
  if (!entry) return '';

  // Resolve names from p4info.
  const table = state.p4info?.tables?.find(t => t.preamble.id === entry.table_id);
  const action = entry.action?.action;
  const actionInfo = action ? state.p4info?.actions?.find(a => a.preamble.id === action.action_id) : null;

  const parts = [];

  // Match fields.
  for (const m of entry.match || []) {
    const mfInfo = table?.match_fields?.find(f => f.id === m.field_id);
    const name = mfInfo?.name || `field_${m.field_id}`;
    if (m.exact) {
      parts.push(`${name} = ${base64ToHex(m.exact.value)}`);
    } else if (m.lpm) {
      parts.push(`${name} = ${base64ToHex(m.lpm.value)}/${m.lpm.prefix_len}`);
    } else if (m.ternary) {
      parts.push(`${name} = ${base64ToHex(m.ternary.value)} & ${base64ToHex(m.ternary.mask)}`);
    } else if (m.optional) {
      parts.push(`${name} = ${base64ToHex(m.optional.value)}`);
    }
  }

  // Action params.
  if (action?.params?.length) {
    const paramStrs = action.params.map(p => {
      const pInfo = actionInfo?.params?.find(pp => pp.id === p.param_id);
      return `${pInfo?.name || `param_${p.param_id}`} = ${decodeParamValue(p.value)}`;
    });
    parts.push(...paramStrs);
  }

  if (entry.priority) {
    parts.push(`priority = ${entry.priority}`);
  }

  if (parts.length === 0) return '';
  const detail = parts.map(p => escapeHtml(p)).join('\n');
  return `<div class="trace-entry-detail" onclick="event.stopPropagation(); this.classList.toggle('expanded')">${detail}</div>`;
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
    return `<div class="trace-outcome output" data-outcome>→ output port ${o.egress_port} (${bytes.length} bytes)</div>`;
  }
  if (outcome.drop) {
    const reason = formatDropReason(outcome.drop.reason);
    return `<div class="trace-outcome drop" data-outcome>✕ drop (reason: ${reason})</div>`;
  }
  return '';
}

function renderFork(fork) {
  const reason = formatForkReason(fork.reason);
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
// Control-flow graph visualization
// ---------------------------------------------------------------------------

function renderControlGraph() {
  const container = document.getElementById('control-graph');
  const handle = document.getElementById('graph-resize-handle');
  const controls = state.controlGraph;
  if (!controls) {
    container.classList.add('hidden');
    handle.classList.add('hidden');
    return;
  }

  // Only show graphs that have meaningful nodes (skip trivial entry→exit).
  const interesting = Object.keys(controls).filter(name =>
    controls[name].nodes.some(n => n.type === 'table' || n.type === 'condition' || n.type === 'state')
  );
  if (interesting.length === 0) {
    container.classList.add('hidden');
    handle.classList.add('hidden');
    return;
  }

  container.classList.remove('hidden');
  handle.classList.remove('hidden');

  // Only show tabs when there are multiple interesting controls.
  const tabsEl = container.querySelector('.control-graph-tabs');
  if (interesting.length > 1) {
    tabsEl.innerHTML = interesting.map(name =>
      `<button class="control-graph-tab" data-control="${escapeHtml(name)}">${escapeHtml(name)}</button>`
    ).join('');
    tabsEl.querySelectorAll('.control-graph-tab').forEach(btn => {
      btn.addEventListener('click', () => showControlGraph(btn.dataset.control));
    });
  } else {
    tabsEl.innerHTML = '';
  }

  showControlGraph(interesting[0]);
}

function showControlGraph(controlName) {
  state.activeGraphControl = controlName;
  const controls = state.controlGraph;
  const graph = controls[controlName];
  if (!graph) return;

  // Update tab active state.
  document.querySelectorAll('.control-graph-tab').forEach(btn =>
    btn.classList.toggle('active', btn.dataset.control === controlName)
  );

  layoutAndRenderGraph(graph);
}

function layoutAndRenderGraph(graph) {
  if (typeof dagre === 'undefined') return;

  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: 20, ranksep: 40, marginx: 16, marginy: 8 });
  g.setDefaultEdgeLabel(() => ({}));

  const NODE_H = 32;
  const SMALL_H = 20;
  const CHAR_W = 7; // approximate width per character in 11px monospace
  const COND_CHAR_W = 7; // same size as table labels
  const PAD_W = 20; // horizontal padding inside node
  const LINE_H = 13; // line height for multiline condition labels

  for (const node of graph.nodes) {
    if (node.type === 'entry') {
      g.setNode(node.id, { label: node.name, width: SMALL_H, height: SMALL_H, type: node.type });
    } else if (node.type === 'exit') {
      // Accept/reject get a labeled circle; control-graph "exit" stays small.
      const isTerminal = node.name === 'accept' || node.name === 'reject';
      const d = isTerminal ? 50 : SMALL_H;
      g.setNode(node.id, { label: node.name, width: d, height: d, type: node.type });
    } else if (node.type === 'condition') {
      // Split condition text on operators for multiline display.
      const lines = node.name.split(/(?= &&| \|\|)/).map(s => s.trim());
      const maxLineLen = Math.max(...lines.map(l => l.length));
      const w = maxLineLen * COND_CHAR_W + PAD_W;
      const h = Math.max(NODE_H, lines.length * LINE_H + 12);
      g.setNode(node.id, { label: node.name, lines, width: Math.max(80, w), height: h, type: node.type });
    } else {
      const textW = node.name.length * CHAR_W + PAD_W;
      g.setNode(node.id, { label: node.name, width: Math.max(80, textW), height: NODE_H, type: node.type });
    }
  }

  for (const edge of graph.edges) {
    const edgeOpts = { label: edge.label || '' };
    if (edge.label) {
      edgeOpts.width = edge.label.length * CHAR_W + 8;
      edgeOpts.height = 14;
    }
    g.setEdge(edge.from, edge.to, edgeOpts);
  }

  dagre.layout(g);

  // Compute actual bounding box from all nodes and edge points.
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  g.nodes().forEach(id => {
    const n = g.node(id);
    minX = Math.min(minX, n.x - n.width / 2);
    minY = Math.min(minY, n.y - n.height / 2);
    maxX = Math.max(maxX, n.x + n.width / 2);
    maxY = Math.max(maxY, n.y + n.height / 2);
  });
  g.edges().forEach(e => {
    const edgeData = g.edge(e);
    const pts = edgeData.points || [];
    for (const p of pts) {
      minX = Math.min(minX, p.x);
      minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x);
      maxY = Math.max(maxY, p.y);
    }
    // Account for edge label text extending beyond the edge points.
    if (edgeData.label && pts.length > 0) {
      const mid = pts[Math.floor(pts.length / 2)];
      const labelW = edgeData.label.length * CHAR_W + 8;
      maxX = Math.max(maxX, mid.x + labelW);
    }
  });
  const pad = 16;
  const svgW = maxX - minX + pad * 2;
  const svgH = maxY - minY + pad * 2;

  const svgEl = document.getElementById('control-graph-svg');
  svgEl.setAttribute('viewBox', `${minX - pad} ${minY - pad} ${svgW} ${svgH}`);
  svgEl.setAttribute('width', svgW);
  svgEl.setAttribute('height', svgH);
  svgEl.style.minWidth = svgW + 'px';

  let svg = '';

  // Clip a point to the perimeter of a circle centered at (cx, cy) with radius r.
  // Returns the intersection of the line from prev→pt with the circle.
  function clipToCircle(pt, prev, cx, cy, r) {
    const dx = pt.x - prev.x, dy = pt.y - prev.y;
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len === 0) return pt;
    return { x: cx - (dx / len) * r, y: cy - (dy / len) * r };
  }

  // Render edges (before nodes so nodes draw on top).
  g.edges().forEach(e => {
    const edgeData = g.edge(e);
    const points = [...(edgeData.points || [])];
    if (points.length < 2) return;

    // Clip edge endpoints to circle perimeters for entry/exit nodes.
    const srcNode = g.node(e.v), tgtNode = g.node(e.w);
    if (srcNode && (srcNode.type === 'entry' || srcNode.type === 'exit')) {
      const r = srcNode.height / 2;
      points[0] = clipToCircle(points[0], points[1], srcNode.x, srcNode.y, r);
    }
    if (tgtNode && (tgtNode.type === 'entry' || tgtNode.type === 'exit')) {
      const r = tgtNode.height / 2;
      const last = points.length - 1;
      points[last] = clipToCircle(points[last], points[last - 1], tgtNode.x, tgtNode.y, r);
    }

    const pathD = points.map((p, i) =>
      i === 0 ? `M${p.x},${p.y}` : `L${p.x},${p.y}`
    ).join(' ');

    const isFalse = edgeData.label === 'F' || edgeData.label === 'miss' || edgeData.label === 'false';
    const edgeClass = isFalse ? 'graph-edge graph-edge-false' : 'graph-edge';
    svg += `<path d="${pathD}" class="${edgeClass}" marker-end="url(#arrowhead)"/>`;

    // Edge label — centered along the edge.
    if (edgeData.label) {
      const mid = points[Math.floor(points.length / 2)];
      svg += `<text x="${mid.x}" y="${mid.y - 6}" text-anchor="middle" class="graph-edge-label">${escapeHtml(edgeData.label)}</text>`;
    }
  });

  // Render nodes.
  g.nodes().forEach(nodeId => {
    const node = g.node(nodeId);
    const x = node.x - node.width / 2;
    const y = node.y - node.height / 2;

    if (node.type === 'entry') {
      const cx = node.x, cy = node.y, r = node.height / 2;
      svg += `<circle cx="${cx}" cy="${cy}" r="${r}" class="graph-node-entry" data-node="${nodeId}"/>`;
    } else if (node.type === 'exit') {
      const cx = node.x, cy = node.y, r = node.height / 2;
      const cls = node.label === 'reject' ? 'graph-node-reject' : node.label === 'accept' ? 'graph-node-accept' : 'graph-node-exit';
      svg += `<circle cx="${cx}" cy="${cy}" r="${r}" class="${cls}" data-node="${nodeId}"/>`;
      if (node.label === 'accept' || node.label === 'reject') {
        svg += `<text x="${cx}" y="${cy}" class="graph-node-label graph-terminal-label">${escapeHtml(node.label)}</text>`;
      }
    } else if (node.type === 'condition') {
      // Rounded rectangle with dashed border for conditions.
      svg += `<rect x="${x}" y="${y}" width="${node.width}" height="${node.height}" rx="4" class="graph-node-condition" data-node="${nodeId}"/>`;
      if (node.lines && node.lines.length > 1) {
        const startY = node.y - (node.lines.length - 1) * LINE_H / 2;
        const tspans = node.lines.map((line, i) =>
          `<tspan x="${node.x}" dy="${i === 0 ? 0 : LINE_H}">${escapeHtml(line)}</tspan>`
        ).join('');
        svg += `<text x="${node.x}" y="${startY}" class="graph-node-label condition-label">${tspans}</text>`;
      } else {
        svg += `<text x="${node.x}" y="${node.y}" class="graph-node-label condition-label">${escapeHtml(node.label)}</text>`;
      }
    } else if (node.type === 'state') {
      // Rounded rectangle for parser states.
      svg += `<rect x="${x}" y="${y}" width="${node.width}" height="${node.height}" rx="12" class="graph-node-state" data-node="${nodeId}"/>`;
      svg += `<text x="${node.x}" y="${node.y}" class="graph-node-label">${escapeHtml(node.label)}</text>`;
    } else {
      // Rectangle for tables.
      svg += `<rect x="${x}" y="${y}" width="${node.width}" height="${node.height}" rx="4" class="graph-node-table" data-node="${nodeId}"/>`;
      svg += `<text x="${node.x}" y="${node.y}" class="graph-node-label">${escapeHtml(node.label)}</text>`;
    }
  });

  // Arrow marker definition.
  svg = `<defs><marker id="arrowhead" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--text-muted)"/></marker></defs>` + svg;

  svgEl.innerHTML = svg;
}

/** Scroll the graph panel so that a highlighted SVG element is fully visible with padding. */
function scrollGraphToNode(el) {
  if (!el) return;
  const outer = document.getElementById('control-graph');
  const inner = document.querySelector('.control-graph-container');
  if (!outer || !inner) return;
  const svg = el.closest('svg');
  if (!svg) return;
  // Get the element's bounding box in screen coordinates.
  const bbox = el.getBBox();
  const ctm = el.getScreenCTM();
  if (!ctm) return;
  const pad = 20;
  const tl = svg.createSVGPoint();
  tl.x = bbox.x; tl.y = bbox.y;
  const br = svg.createSVGPoint();
  br.x = bbox.x + bbox.width; br.y = bbox.y + bbox.height;
  const screenTL = tl.matrixTransform(ctm);
  const screenBR = br.matrixTransform(ctm);
  // Horizontal: inner container scrolls.
  const ir = inner.getBoundingClientRect();
  if (screenTL.x - pad < ir.left) {
    inner.scrollLeft -= ir.left - screenTL.x + pad;
  } else if (screenBR.x + pad > ir.right) {
    inner.scrollLeft += screenBR.x + pad - ir.right;
  }
  // Vertical: outer container scrolls.
  const or_ = outer.getBoundingClientRect();
  if (screenTL.y - pad < or_.top) {
    outer.scrollTop -= or_.top - screenTL.y + pad;
  } else if (screenBR.y + pad > or_.bottom) {
    outer.scrollTop += screenBR.y + pad - or_.bottom;
  }
}

function clearGraphHighlights() {
  document.querySelectorAll('.graph-node-table, .graph-node-condition, .graph-node-state, .graph-node-accept, .graph-node-reject').forEach(el => {
    el.classList.remove('graph-active');
  });
}

/** Highlight one or more nodes in the control graph by data-node ID. */
function highlightGraphNode(...names) {
  clearGraphHighlights();
  let scrollTarget = null;
  for (const name of names) {
    if (!name) continue;
    const el = document.querySelector(`[data-node="${CSS.escape(name)}"]`);
    if (el) {
      el.classList.add('graph-active');
      if (!scrollTarget) scrollTarget = el;
    }
  }
  if (scrollTarget) scrollGraphToNode(scrollTarget);
}

/** Highlight a condition node by matching a source fragment against node labels. */
function highlightGraphCondition(fragment) {
  clearGraphHighlights();
  if (!fragment) return;
  const nodes = document.querySelectorAll('.graph-node-condition');
  // The graph extractor uses only innermost field names (hdr.ipv4 → ipv4,
  // meta.x → x), but the trace source_fragment keeps the full P4 source text.
  // Normalize by stripping common struct prefixes so they match.
  const norm = fragment.replace(/\b(hdr|meta|std_meta|local_metadata)\./g, '');
  let best = null, bestLen = -1;
  for (const node of nodes) {
    const label = node.dataset.node;
    if (label === norm) { best = node; break; } // exact match wins
    if ((norm.includes(label) || label.includes(norm)) && label.length > bestLen) {
      best = node;
      bestLen = label.length;
    }
  }
  if (best) {
    best.classList.add('graph-active');
    scrollGraphToNode(best);
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

function initGraphResize() {
  const handle = document.getElementById('graph-resize-handle');
  const graph = document.getElementById('control-graph');
  let startY, startHeight;

  handle.addEventListener('mousedown', (e) => {
    startY = e.clientY;
    startHeight = graph.getBoundingClientRect().height;
    handle.classList.add('active');
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';

    const onMouseMove = (e) => {
      const delta = startY - e.clientY;
      const newHeight = Math.max(60, startHeight + delta);
      graph.style.maxHeight = newHeight + 'px';
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

  // Playback controls
  document.getElementById('btn-step-back').addEventListener('click', stepBack);
  document.getElementById('btn-step-forward').addEventListener('click', stepForward);
  document.getElementById('btn-reset').addEventListener('click', resetPlayback);

  // Trace view toggle (Tree / JSON / Proto)
  document.querySelectorAll('.trace-view-btn').forEach(btn => {
    btn.addEventListener('click', () => switchTraceView(btn.dataset.view));
  });

  // Example selector (inline strings or file paths for larger examples).
  document.getElementById('example-select').addEventListener('change', async (e) => {
    const example = EXAMPLES[e.target.value];
    if (!example || !state.editor) return;
    state.loadingExample = true;
    if (example.startsWith('/')) {
      try {
        const resp = await fetch(example);
        state.editor.setValue(await resp.text());
      } catch (_) { /* ignore fetch errors */ }
    } else {
      state.editor.setValue(example);
    }
    state.loadingExample = false;
  });

  // Graph fullscreen toggle (browser Fullscreen API).
  const graphEl = document.getElementById('control-graph');
  document.getElementById('btn-graph-fullscreen').addEventListener('click', () => {
    if (document.fullscreenElement === graphEl) {
      document.exitFullscreen();
    } else {
      graphEl.requestFullscreen();
    }
  });

  // Resize
  initResize();
  initGraphResize();

  // Click trace events to navigate playback to that step.
  document.getElementById('trace-tree').addEventListener('click', (e) => {
    const el = e.target.closest('.trace-event[data-event-idx]');
    if (!el || !state.playbackEvents.length) return;
    const clickedIdx = parseInt(el.dataset.eventIdx, 10);
    const pos = state.playbackEvents.findIndex(pe => pe.eventIdx === clickedIdx);
    if (pos >= 0) {
      state.playbackPos = pos;
      applyPlaybackState();
    }
  });

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

  // Don't restore "pipeline loaded" on page refresh — the frontend has lost
  // all client-side state (graph, entries, trace), so claiming the pipeline is
  // loaded would be misleading. The user can re-compile to start fresh.
});
