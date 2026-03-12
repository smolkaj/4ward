"""Wrapper around protoc-gen-doc that declares editions support.

protoc-gen-doc handles editions protos correctly but doesn't declare support in
its CodeGeneratorResponse, causing protoc to reject the output. This wrapper
patches the response to set:
  - supported_features = 3 (FEATURE_PROTO3_OPTIONAL | FEATURE_SUPPORTS_EDITIONS)
  - minimum_edition = EDITION_PROTO2 (998)
  - maximum_edition = EDITION_2024 (1001)

Remove this wrapper when protoc-gen-doc adds native editions support:
https://github.com/pseudomuto/protoc-gen-doc/issues/541
"""

import os
import subprocess
import sys

# Protobuf wire-format bytes to append to CodeGeneratorResponse:
#   Field 2 (supported_features), varint 3:  0x10 0x03
#   Field 3 (minimum_edition), varint 998:   0x18 0xE6 0x07
#   Field 4 (maximum_edition), varint 1001:  0x20 0xE9 0x07
_EDITIONS_PATCH = b"\x10\x03\x18\xe6\x07\x20\xe9\x07"


def main():
    plugin = os.environ["PROTOC_GEN_DOC_REAL"]
    proc = subprocess.run(
        [plugin],
        input=sys.stdin.buffer.read(),
        capture_output=True,
    )
    sys.stderr.buffer.write(proc.stderr)
    if proc.returncode != 0:
        sys.exit(proc.returncode)
    sys.stdout.buffer.write(proc.stdout + _EDITIONS_PATCH)


if __name__ == "__main__":
    main()
