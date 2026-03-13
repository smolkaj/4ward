# WORKAROUND: protoc-gen-doc doesn't declare FEATURE_SUPPORTS_EDITIONS, so protoc
# rejects its output for edition 2024 protos. This wrapper patches the response.
# Remove when upstream adds support: https://github.com/pseudomuto/protoc-gen-doc/issues/541

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
