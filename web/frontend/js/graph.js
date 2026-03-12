// Control-flow and parser graph: dagre layout, SVG rendering, highlighting, scrolling.

import { state } from './state.js';
import { escapeHtml } from './encoding.js';

// Track currently highlighted graph nodes to avoid full DOM scans on each step.
let _activeGraphNodes = [];

export function renderControlGraph() {
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

export function showControlGraph(controlName) {
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
  const COND_CHAR_W = 7;
  const PAD_W = 20; // horizontal padding inside node
  const LINE_H = 13; // line height for multiline condition labels

  for (const node of graph.nodes) {
    if (node.type === 'entry') {
      g.setNode(node.id, { label: node.name, width: SMALL_H, height: SMALL_H, type: node.type });
    } else if (node.type === 'exit') {
      const isTerminal = node.name === 'accept' || node.name === 'reject';
      const d = isTerminal ? 50 : SMALL_H;
      g.setNode(node.id, { label: node.name, width: d, height: d, type: node.type });
    } else if (node.type === 'condition') {
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
      svg += `<rect x="${x}" y="${y}" width="${node.width}" height="${node.height}" rx="12" class="graph-node-state" data-node="${nodeId}"/>`;
      svg += `<text x="${node.x}" y="${node.y}" class="graph-node-label">${escapeHtml(node.label)}</text>`;
    } else {
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

export function clearGraphHighlights() {
  for (const el of _activeGraphNodes) el.classList.remove('graph-active');
  _activeGraphNodes = [];
}

/** Highlight one or more nodes in the control graph by data-node ID. */
export function highlightGraphNode(...names) {
  clearGraphHighlights();
  let scrollTarget = null;
  for (const name of names) {
    if (!name) continue;
    const el = document.querySelector(`[data-node="${CSS.escape(name)}"]`);
    if (el) {
      el.classList.add('graph-active');
      _activeGraphNodes.push(el);
      if (!scrollTarget) scrollTarget = el;
    }
  }
  if (scrollTarget) scrollGraphToNode(scrollTarget);
}

/** Highlight a condition node by matching a source fragment against node labels. */
export function highlightGraphCondition(fragment) {
  clearGraphHighlights();
  if (!fragment) return;
  const nodes = document.querySelectorAll('.graph-node-condition');
  const norm = fragment.replace(/\b(hdr|meta|std_meta|local_metadata)\./g, '');
  let best = null, bestLen = -1;
  for (const node of nodes) {
    const label = node.dataset.node;
    if (label === norm) { best = node; break; }
    if ((norm.includes(label) || label.includes(norm)) && label.length > bestLen) {
      best = node;
      bestLen = label.length;
    }
  }
  if (best) {
    best.classList.add('graph-active');
    _activeGraphNodes.push(best);
    scrollGraphToNode(best);
  }
}
