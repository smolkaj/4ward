---
description: "Bazel rule reference for fourward_pipeline — compile P4 programs with p4c-4ward from a Bazel build."
---

# Bazel Rule Reference

<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Bazel rule for compiling P4 programs using p4c-4ward.

Usage:

    load("@fourward//bazel:fourward_pipeline.bzl", "fourward_pipeline")

    fourward_pipeline(
        name = "sai_middleblock",
        src = "//sai_p4/instantiations/google:middleblock.p4",
        out = "sai_middleblock.binpb",
        out_format = "p4runtime",
    )

Output formats (`out_format`):
  - `"native"` (default): `fourward.ir.PipelineConfig`
  - `"p4runtime"`: `p4.v1.ForwardingPipelineConfig` (for `SetForwardingPipelineConfig`)

File extension of `out` determines serialization: `.txtpb` for text proto,
`.binpb` for binary proto.

<a id="fourward_pipeline"></a>

## fourward_pipeline

<pre>
load("@fourward//bazel:fourward_pipeline.bzl", "fourward_pipeline")

fourward_pipeline(<a href="#fourward_pipeline-name">name</a>, <a href="#fourward_pipeline-src">src</a>, <a href="#fourward_pipeline-out">out</a>, <a href="#fourward_pipeline-defines">defines</a>, <a href="#fourward_pipeline-extra_srcs">extra_srcs</a>, <a href="#fourward_pipeline-includes">includes</a>, <a href="#fourward_pipeline-out_format">out_format</a>)
</pre>

Compiles a P4 source file using p4c-4ward.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="fourward_pipeline-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="fourward_pipeline-src"></a>src |  P4 source file.   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |
| <a id="fourward_pipeline-out"></a>out |  Output file. Extension determines serialization: `.txtpb` for text proto, `.binpb` for binary proto. The proto message type is determined by `out_format`.   | <a href="https://bazel.build/concepts/labels">Label</a>; <a href="https://bazel.build/reference/be/common-definitions#configurable-attributes">nonconfigurable</a> | required |  |
| <a id="fourward_pipeline-defines"></a>defines |  Preprocessor defines passed to p4c via -D.   | List of strings | optional |  `[]`  |
| <a id="fourward_pipeline-extra_srcs"></a>extra_srcs |  Additional file dependencies (e.g. filegroups) that don't need -I flags. Useful when the P4 source uses relative #include paths.   | <a href="https://bazel.build/concepts/labels">List of labels</a> | optional |  `[]`  |
| <a id="fourward_pipeline-includes"></a>includes |  P4 files whose directories are added to the include path via -I. Useful for shadowing standard library files (e.g. a modified v1model.p4).   | <a href="https://bazel.build/concepts/labels">List of labels</a> | optional |  `[]`  |
| <a id="fourward_pipeline-out_format"></a>out_format |  Output proto message type. `"native"` (default): `fourward.ir.PipelineConfig`. `"p4runtime"`: `p4.v1.ForwardingPipelineConfig` (suitable for `SetForwardingPipelineConfig`).   | String | optional |  `"native"`  |


