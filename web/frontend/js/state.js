// Shared application state.

export const state = {
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

  // Playback state.
  playbackEvents: [],       // flattened list of {event, stageName, line, eventIdx}
  playbackPos: -1,          // current position (-1 = before first event)
  _playbackDecorations: [], // Monaco editor decoration IDs
};
