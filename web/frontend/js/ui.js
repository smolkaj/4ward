// Shared UI primitives: status, logging, tabs, badges, resize handles.

import { state } from './state.js';

export function setStatus(statusClass, text) {
  const dot = document.getElementById('status-indicator');
  const textEl = document.getElementById('status-text');
  dot.className = 'status-dot ' + statusClass;
  textEl.textContent = text;
}

export function log(message, level = '') {
  const bar = document.getElementById('log-bar');
  const text = document.getElementById('log-text');
  bar.className = level;
  text.textContent = message;
}

export function updateButtonStates() {
  const hasPipeline = !!state.p4info;
  document.getElementById('btn-add-entry').disabled = !hasPipeline;
  document.getElementById('btn-add-clone').disabled = !hasPipeline;
  document.getElementById('btn-send-packet').disabled = !hasPipeline;
}

export function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t =>
    t.classList.toggle('active', t.dataset.tab === name)
  );
  document.querySelectorAll('.tab-content').forEach(c =>
    c.classList.toggle('active', c.id === `tab-${name}`)
  );
}

export function updateTabBadges() {
  const tablesTab = document.querySelector('.tab[data-tab="tables"]');
  const packetsTab = document.querySelector('.tab[data-tab="packets"]');
  const n = state.entries.length;
  tablesTab.textContent = n > 0 ? `Tables (${n})` : 'Tables';
  const lastOutputs = (state.lastTrace?.possible_outcomes || []).flat().length;
  packetsTab.textContent = lastOutputs > 0 ? `Packets (${lastOutputs})` : 'Packets';
}

/** Wire up a drag-resize handle. onStart receives the mousedown event and
 *  returns a drag callback invoked on each mousemove. */
function initDragHandle(handleId, cursor, onStart) {
  const handle = document.getElementById(handleId);
  handle.addEventListener('mousedown', (e) => {
    const onDrag = onStart(e);
    handle.classList.add('active');
    document.body.style.cursor = cursor;
    document.body.style.userSelect = 'none';
    const onMouseMove = (ev) => onDrag(ev);
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

export function initResize() {
  const editorPane = document.getElementById('editor-pane');
  const rightPane = document.getElementById('right-pane');
  initDragHandle('resize-handle', 'col-resize', (e) => {
    const startX = e.clientX;
    const startWidth = editorPane.getBoundingClientRect().width;
    return (e) => {
      const newWidth = Math.max(200, Math.min(startWidth + e.clientX - startX, window.innerWidth - 200));
      editorPane.style.flex = `0 0 ${newWidth}px`;
      rightPane.style.flex = '1';
    };
  });
}

export function initGraphResize() {
  const graph = document.getElementById('control-graph');
  initDragHandle('graph-resize-handle', 'row-resize', (e) => {
    const startY = e.clientY;
    const startHeight = graph.getBoundingClientRect().height;
    return (e) => {
      graph.style.maxHeight = Math.max(60, startHeight + startY - e.clientY) + 'px';
    };
  });
}
