#!/usr/bin/env python3
"""Runs a cram test with Bazel runfile paths as environment variables.

Usage: run_cram.py [KEY=VALUE ...] test.t

KEY=VALUE pairs are resolved to absolute paths and set as environment
variables before invoking cram. This bridges Bazel's $(rootpath ...)
expansion with cram's temporary working directory.

If FOURWARD is set, a '4ward' wrapper script is placed on PATH so that
cram tests can use '4ward' as a command — matching what a user would
actually type.
"""
import os
import stat
import sys
import tempfile

cram_args = []
for arg in sys.argv[1:]:
    if "=" in arg and not arg.endswith((".t", ".md")):
        key, _, value = arg.partition("=")
        os.environ[key] = os.path.abspath(value)
    else:
        cram_args.append(arg)

# Put '4ward' on PATH so cram tests read like real terminal sessions.
if "FOURWARD" in os.environ:
    bin_dir = tempfile.mkdtemp()
    wrapper = os.path.join(bin_dir, "4ward")
    with open(wrapper, "w") as f:
        f.write('#!/bin/sh\nexec "%s" "$@"\n' % os.environ["FOURWARD"])
    os.chmod(wrapper, os.stat(wrapper).st_mode | stat.S_IEXEC)
    os.environ["PATH"] = bin_dir + ":" + os.environ.get("PATH", "")

from cram import main

sys.exit(main(cram_args))
