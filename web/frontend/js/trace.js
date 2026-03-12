// Trace playback orchestration and view switching.
// HTML generation lives in trace-render.js.

import { state } from './state.js';
import { switchTab } from './ui.js';
import { getMonaco } from './editor.js';
import { highlightGraphNode, highlightGraphCondition, showControlGraph } from './graph.js';
import { renderTraceNode, forEachStage } from './trace-render.js';

const TRACE_VIEW = { TREE: 'tree', JSON: 'json', PROTO: 'proto' };

// Memoized JSON.stringify of trace (recomputed when lastTrace changes).
let _traceJsonCache = { trace: null, json: '' };

function getTraceJson() {
  const trace = state.lastTrace?.trace;
  if (_traceJsonCache.trace !== trace) {
    _traceJsonCache = { trace, json: JSON.stringify(trace, null, 2) || '' };
  }
  return _traceJsonCache.json;
}

// Cached playback UI elements (resolved lazily).
let _playbackUI = null;

function getPlaybackUI() {
  if (!_playbackUI) {
    _playbackUI = {
      stepBack: document.getElementById('btn-step-back'),
      stepForward: document.getElementById('btn-step-forward'),
      reset: document.getElementById('btn-reset'),
      position: document.getElementById('playback-position'),
    };
  }
  return _playbackUI;
}

// Currently highlighted playback element (avoids full DOM scan on each step).
let _playbackHighlight = null;

// ---------------------------------------------------------------------------
// Trace tree entry point
// ---------------------------------------------------------------------------

export function renderTraceTree(trace) {
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

  treeEl.innerHTML = renderTraceNode(trace, true);
  initPlayback(trace);
  switchTraceView(state.traceView || TRACE_VIEW.TREE);
}

// ---------------------------------------------------------------------------
// View switching (Tree / JSON / Proto)
// ---------------------------------------------------------------------------

export function switchTraceView(view) {
  state.traceView = view;
  const treeEl = document.getElementById('trace-tree');
  const rawEl = document.getElementById('trace-raw');

  document.querySelectorAll('.trace-view-btn').forEach(btn =>
    btn.classList.toggle('active', btn.dataset.view === view)
  );

  const showTree = view === TRACE_VIEW.TREE;
  treeEl.classList.toggle('hidden', !showTree);
  rawEl.classList.toggle('hidden', showTree);

  if (view === TRACE_VIEW.JSON) {
    rawEl.textContent =
      '// proto-file: simulator/simulator.proto\n// proto-message: fourward.sim.v1.TraceTree\n\n'
      + getTraceJson();
  } else if (view === TRACE_VIEW.PROTO) {
    rawEl.textContent =
      '# proto-file: simulator/simulator.proto\n# proto-message: fourward.sim.v1.TraceTree\n\n'
      + (state.lastTrace?.trace_proto || '');
  }
}

// ---------------------------------------------------------------------------
// Playback: flatten, step, apply
// ---------------------------------------------------------------------------

function flattenTraceEvents(trace) {
  const result = [];
  let currentStage = null;
  let eventIdx = 0;

  function addEvent(event) {
    const line = event.source_info?.line || 0;
    result.push({ event, stageName: currentStage, line, eventIdx });
    eventIdx++;
  }

  forEachStage(
    trace.events || [], 0,
    (stageName, innerEvents, enterEvent) => {
      currentStage = stageName;
      result.push({ event: enterEvent, stageName, line: 0, eventIdx: null });
      for (const event of innerEvents) addEvent(event);
      currentStage = null;
    },
    (event) => addEvent(event),
  );

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

export function ensureTraceVisible() {
  switchTab('trace');
  switchTraceView(TRACE_VIEW.TREE);
}

export function stepForward() {
  if (state.playbackPos < state.playbackEvents.length - 1) {
    ensureTraceVisible();
    state.playbackPos++;
    applyPlaybackState();
  }
}

export function stepBack() {
  if (state.playbackPos >= 0) {
    ensureTraceVisible();
    state.playbackPos--;
    applyPlaybackState();
  }
}

export function resetPlayback() {
  state.playbackPos = -1;
  applyPlaybackState();
}

export function applyPlaybackState() {
  const pos = state.playbackPos;
  const events = state.playbackEvents;

  if (_playbackHighlight) {
    _playbackHighlight.classList.remove('playback-highlight');
    _playbackHighlight = null;
  }

  if (pos < 0) {
    clearPlaybackEditorHighlight();
    updatePlaybackUI();
    return;
  }

  const current = events[pos];
  highlightTraceStep(current);
  syncGraphHighlight(current, pos, events);
  syncEditorLine(current);
  updatePlaybackUI();
}

// ---------------------------------------------------------------------------
// applyPlaybackState sub-routines
// ---------------------------------------------------------------------------

/** Highlight the current step in the trace tree DOM and scroll it into view. */
function highlightTraceStep(current) {
  const selector = current.eventIdx != null
    ? `[data-event-idx="${current.eventIdx}"]`
    : current.event.pipeline_stage
      ? `.trace-stage[data-stage="${current.stageName}"]`
      : current.event.packet_outcome
        ? '[data-outcome]'
        : null;
  if (!selector) return;
  const el = document.querySelector(selector);
  if (!el) return;

  // Uncollapse any parent stages so the element is visible.
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
  _playbackHighlight = el;
  el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

/** Switch graph tab and highlight the relevant control-flow node. */
function syncGraphHighlight(current, pos, events) {
  if (current.stageName && current.stageName !== state.activeGraphControl
      && state.controlGraph && state.controlGraph[current.stageName]) {
    showControlGraph(current.stageName);
  }

  if (current.event.parser_transition) {
    const pt = current.event.parser_transition;
    highlightGraphNode(pt.from_state, pt.to_state);
  } else if (current.event.table_lookup) {
    highlightGraphNode(current.event.table_lookup.table_name);
  } else if (current.event.action_execution) {
    // Highlight the table that triggered this action.
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
}

/** Highlight the corresponding source line in the Monaco editor. */
function syncEditorLine(current) {
  let line = current.line;

  // For action executions, try to find the action definition in source.
  if (current.event.action_execution && state.editor) {
    const actionName = current.event.action_execution.action_name;
    if (actionName && actionName !== 'NoAction') {
      const model = state.editor.getModel();
      if (model) {
        const shortName = actionName.includes('.') ? actionName.split('.').pop() : actionName;
        const baseName = shortName.replace(/_\d+$/, '');
        const escaped = baseName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const match = model.findMatches(`\\baction\\s+${escaped}\\s*\\(`, false, true, true, null, false);
        if (match.length > 0) line = match[0].range.startLineNumber;
      }
    }
  }

  const monaco = getMonaco();
  if (line && state.editor && monaco) {
    state.editor.revealLineInCenter(line);
    state._playbackDecorations = state.editor.deltaDecorations(
      state._playbackDecorations,
      [{
        range: new monaco.Range(line, 1, line, 1),
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
}

// ---------------------------------------------------------------------------
// Editor highlight cleanup + playback UI
// ---------------------------------------------------------------------------

export function clearPlaybackEditorHighlight() {
  if (state.editor && state._playbackDecorations) {
    state._playbackDecorations = state.editor.deltaDecorations(
      state._playbackDecorations, []
    );
  }
}

function updatePlaybackUI() {
  const pos = state.playbackPos;
  const events = state.playbackEvents;
  const ui = getPlaybackUI();

  ui.stepBack.disabled = pos < 0;
  ui.stepForward.disabled = pos >= events.length - 1;
  ui.reset.disabled = pos < 0;
  ui.position.textContent = events.length > 0
    ? `${Math.max(0, pos + 1)} / ${events.length}`
    : '';
}

export function jumpToLine(line) {
  if (!state.editor || !line) return;
  state.editor.revealLineInCenter(line);
  state.editor.setPosition({ lineNumber: line, column: 1 });
  state.editor.focus();
}
