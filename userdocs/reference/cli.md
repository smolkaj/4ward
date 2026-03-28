---
description: "4ward CLI reference: all commands, flags, output formats, and environment variables for compile, sim, and run."
---

# CLI Reference

## Commands

### `4ward compile`

Compiles a P4 program to a pipeline config (text-format protobuf).

```
Usage: 4ward compile [options] <program.p4>

Options:
  -o <path>   Output file (default: <program>.txtpb)
  -I <dir>    Add include directory for P4 headers
```

Example:

```sh
4ward compile -I include/ my_program.p4 -o pipeline.txtpb
```

### `4ward sim`

Runs an STF test against a pre-compiled pipeline.

```
Usage: 4ward sim [--format=human|textproto|json] [--drop-port=N] <pipeline.txtpb> <test.stf>

Options:
  --format=human|textproto|json   Trace output format (default: human)
  --drop-port=N                   Override the drop port (default: derived from port width)
```

### `4ward run`

Compiles a P4 program and runs an STF test in one step.

```
Usage: 4ward run [--format=human|textproto|json] [--drop-port=N] [-I <dir>] <program.p4> <test.stf>

Options:
  --format=human|textproto|json   Trace output format (default: human)
  --drop-port=N                   Override the drop port (default: derived from port width)
  -I <dir>                        Add include directory for P4 headers
```

Use `-` as the test argument to read STF from stdin:

```sh
echo 'packet 0 DEADBEEF' | 4ward run program.p4 -
```

## Output formats

`--format` controls how trace trees are printed. See
[Trace Trees: Output formats](../concepts/traces.md#output-formats) for
examples.

| Format | Description |
|--------|-------------|
| `human` | Indented text (default). One line per event. |
| `textproto` | Protocol buffer text format. Includes `proto-file` / `proto-message` header. |
| `json` | JSON serialization of the proto. Includes `proto-file` / `proto-message` header. |

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success (all expects matched) |
| 1 | Test failure (output mismatch) |
| 2 | Usage error (bad arguments, missing files) |
| 3 | Internal error (pipeline load failure) |

## Drop port

The drop port is the egress port that means "drop this packet."
`mark_to_drop()` sets `egress_spec` to this value.

By default, the drop port is derived from the P4 program's port width:
`2^N - 1` (e.g., 511 for 9-bit ports). Use `--drop-port` to override.
