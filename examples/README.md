# examples

Ready-to-run P4 programs and tests for learning 4ward. Every file here
is buildable and covered by CI. [`tutorial.t`](tutorial.t) is also a
[cram](https://bitheap.org/cram/) script that walks through the `4ward`
CLI end-to-end.

Try it:
```sh
bazel run //cli:4ward -- run examples/passthrough.p4 <(echo "packet 0 deadbeef")
```
