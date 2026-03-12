// Monaco editor: P4 language definition, initialization, error highlighting.

import { state } from './state.js';
import { EXAMPLES } from './examples.js';

let _monaco = null;

/** Access the Monaco API instance (available after initEditor resolves). */
export function getMonaco() { return _monaco; }

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

/**
 * Initialize Monaco editor. Returns a promise that resolves with the editor instance.
 * The compileCallback is bound to Ctrl/Cmd+Enter.
 */
export function initEditor(compileCallback) {
  return new Promise((resolve) => {
    require.config({
      paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min/vs' },
    });
    require(['vs/editor/editor.main'], function (monaco) {
      _monaco = monaco;
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
      const exampleSelect = document.getElementById('example-select');
      exampleSelect.value = 'basic_table';
      editor.onDidChangeModelContent(() => {
        if (!state.loadingExample) exampleSelect.value = '';
      });

      // Ctrl/Cmd+Enter = Compile & Load
      editor.addAction({
        id: 'compile-and-load',
        label: 'Compile & Load',
        keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
        run: () => compileCallback(),
      });

      state.editor = editor;
      resolve(editor);
    });
  });
}

/** Parse p4c error messages and highlight lines in the editor. */
export function highlightCompileErrors(errorText) {
  if (!state.editor || !_monaco) return;
  const monaco = _monaco;

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

export function clearEditorDecorations() {
  if (!state.editor || !_monaco) return;
  _monaco.editor.setModelMarkers(state.editor.getModel(), 'p4c', []);
}
