# Cutting a Release

Versions use the **yyyymmdd.patch** format (e.g. `20260328.0`).

## Prerequisites

All `git_override` blocks in `MODULE.bazel` that are **not** `dev_dependency`
must be resolved before a release. Dev-only overrides (`behavioral_model`,
`bazel_clang_tidy`) are invisible to BCR consumers and don't block releases.

## Steps

### 1. Create a release branch

```sh
git checkout -b release-yyyymmdd
```

Retain the branch permanently — it serves as a release archive and makes
patch releases easy.

### 2. Update the version in MODULE.bazel

```starlark
module(
    name = "fourward",
    version = "yyyymmdd.0",
    ...
)
```

### 3. Create a GitHub release

- **Tag**: `yyyymmdd.0`
- **Title**: `4ward yyyymmdd.0 (month year)`
- Attach the source `.tar.gz` archive to the release to ensure archive
  checksum stability for the BCR. GitHub's auto-generated archives are
  [not guaranteed to be stable](https://github.com/orgs/community/discussions/45830),
  so download the auto-generated archive and re-upload it as an explicit
  release asset.

### 4. Submit to the Bazel Central Registry

Submit a PR to [`bazelbuild/bazel-central-registry`](https://github.com/bazelbuild/bazel-central-registry)
following the [contribution guidelines](https://github.com/bazelbuild/bazel-central-registry/blob/main/docs/README.md).
Previous submissions live under
[`modules/fourward/`](https://github.com/bazelbuild/bazel-central-registry/tree/main/modules/fourward).
