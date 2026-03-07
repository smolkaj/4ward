#!/usr/bin/env python3
"""Runs a cram test with Bazel runfile paths as environment variables.

Usage: run_cram.py [KEY=VALUE ...] test.t

KEY=VALUE pairs are resolved to absolute paths and set as environment
variables before invoking cram. This bridges Bazel's $(rootpath ...)
expansion with cram's temporary working directory.
"""
import os
import sys

cram_args = []
for arg in sys.argv[1:]:
    if "=" in arg and not arg.endswith(".t"):
        key, _, value = arg.partition("=")
        os.environ[key] = os.path.abspath(value)
    else:
        cram_args.append(arg)

from cram import main

sys.exit(main(cram_args))
