#!/bin/bash
# Collects Kotlin code coverage for the simulator library.
#
# Usage:
#   ./coverage.sh              # run all tests, produce LCOV + optional HTML
#   ./coverage.sh --html       # same, but require genhtml for HTML output
#
# Bazel 9's built-in `bazel coverage` pipeline doesn't produce LCOV data for
# kt_jvm_test targets.  Two independent bugs conspire:
#
#   1. The JaCoCo metadata file uses `external/…` paths that don't resolve
#      under the Bzlmod `_main/` runfiles root.
#
#   2. Even when jar resolution succeeds via classpath URL reflection,
#      JacocoLCOVFormatter silently filters out every source file because
#      `-paths-for-coverage.txt` contains workspace-relative paths
#      (e.g. `simulator/BitVector.kt`) while the class metadata reports
#      package-qualified paths (`fourward/simulator/BitVector.kt`).
#
# We work around both by:
#   • Building with --collect_code_coverage to get instrumented jars.
#   • Running each test binary directly to produce .exec probe data.
#   • Converting .exec → LCOV ourselves using JaCoCo's API (from the
#     deploy jar already in the Bazel cache) with a small inline helper
#     that bypasses the broken path filtering.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
REPORT_DIR="${REPO_ROOT}/coverage-report"
COVERAGE_WORKDIR=$(mktemp -d)
trap 'rm -rf "${COVERAGE_WORKDIR}"' EXIT

# //simulator:FooTest → simulator/FooTest
target_to_bin_rel() {
  local t="${1#//}"
  echo "${t/://}"
}

# ── Discover test targets ────────────────────────────────────────────────────

TARGETS=$(bazel query 'kind(kt_jvm_test, //...)' 2>/dev/null)

# ── Build with coverage instrumentation ──────────────────────────────────────

echo "Building with coverage instrumentation..."
# shellcheck disable=SC2086
bazel build --collect_code_coverage \
  --instrumentation_filter='//simulator[/:]' \
  ${TARGETS}

BIN_DIR="$(bazel info bazel-bin)"

# ── Locate the JaCoCo deploy jar and JDK ─────────────────────────────────────

# Pick the runfiles of any kt_jvm_test target to find the tools.
SAMPLE_RUNFILES=""
for target in ${TARGETS}; do
  SAMPLE_RUNFILES="${BIN_DIR}/$(target_to_bin_rel "${target}").runfiles"
  [[ -d "${SAMPLE_RUNFILES}" ]] && break
done

if [[ ! -d "${SAMPLE_RUNFILES}" ]]; then
  echo "Error: no runfiles directory found for any test target." >&2
  exit 1
fi

JACOCO_JAR="${SAMPLE_RUNFILES}/rules_java++toolchains+remote_java_tools/java_tools/JacocoCoverage_jarjar_deploy.jar"
JDK_HOME="${SAMPLE_RUNFILES}/rules_java++toolchains+remotejdk21_macos_aarch64"
JAVA="${JDK_HOME}/bin/java"
JAVAC="${JDK_HOME}/bin/javac"
JAR="${JDK_HOME}/bin/jar"

if [[ ! -f "${JACOCO_JAR}" ]]; then
  echo "Error: JaCoCo deploy jar not found at ${JACOCO_JAR}" >&2
  exit 1
fi
if [[ ! -x "${JAVA}" ]]; then
  echo "Error: JDK not found at ${JDK_HOME}" >&2
  exit 1
fi

# ── Compile the exec-to-LCOV converter ───────────────────────────────────────
#
# This is a tiny Java program that uses JaCoCo's API to convert .exec probe
# data into LCOV format. It uses the no-arg JacocoLCOVFormatter constructor
# which disables the broken source-path filtering.

CONVERTER_SRC="${COVERAGE_WORKDIR}/ExecToLcov.java"
cat > "${CONVERTER_SRC}" << 'JAVA'
import com.google.testing.coverage.JacocoLCOVFormatter;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Converts JaCoCo .exec probe data into LCOV format.
 *
 * Usage: ExecToLcov <output.dat> <exec1> [exec2 ...] -- <jar1> [jar2 ...]
 *
 * Reads .exec files, analyzes .class.uninstrumented entries from the jars,
 * and writes LCOV to the output file.
 */
public class ExecToLcov {
    private static final String UNINSTRUMENTED_SUFFIX = ".class.uninstrumented";

    public static void main(String[] args) throws Exception {
        String outputPath = args[0];
        List<String> execFiles = new ArrayList<>();
        List<String> jarFiles = new ArrayList<>();
        boolean seenSep = false;
        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) { seenSep = true; continue; }
            if (seenSep) jarFiles.add(args[i]); else execFiles.add(args[i]);
        }

        // Load execution data.
        ExecFileLoader loader = new ExecFileLoader();
        for (String execFile : execFiles) {
            try (var fis = new FileInputStream(execFile)) {
                loader.load(fis);
            }
        }

        // Analyze uninstrumented classes — single pass feeds both the
        // coverage analyzer and the branch-detail analyzer.
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        com.google.testing.coverage.BranchDetailAnalyzer branchAnalyzer =
            new com.google.testing.coverage.BranchDetailAnalyzer(
                loader.getExecutionDataStore());

        Set<String> seen = new HashSet<>();
        for (String jarPath : jarFiles) {
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(UNINSTRUMENTED_SUFFIX) && seen.add(name)) {
                        byte[] bytes = jar.getInputStream(entry).readAllBytes();
                        analyzer.analyzeAll(new ByteArrayInputStream(bytes), name);
                        branchAnalyzer.analyzeAll(new ByteArrayInputStream(bytes), name);
                    }
                }
            }
        }

        IBundleCoverage bundle = builder.getBundle("coverage");
        Map<String, com.google.testing.coverage.BranchCoverageDetail> branchDetails =
            branchAnalyzer.getBranchDetails();

        // Write LCOV using the no-arg constructor (no path filtering).
        JacocoLCOVFormatter formatter = new JacocoLCOVFormatter();
        try (PrintWriter pw = new PrintWriter(
                java.nio.file.Files.newBufferedWriter(
                    java.nio.file.Path.of(outputPath),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            org.jacoco.report.IReportVisitor visitor =
                formatter.createVisitor(pw, branchDetails);
            visitor.visitInfo(
                loader.getSessionInfoStore().getInfos(),
                loader.getExecutionDataStore().getContents());
            visitor.visitBundle(bundle,
                new org.jacoco.report.ISourceFileLocator() {
                    public Reader getSourceFile(String pkg, String name) { return null; }
                    public int getTabWidth() { return 4; }
                });
            visitor.visitEnd();
        }
    }
}
JAVA

echo "Compiling coverage converter..."
"${JAVAC}" -cp "${JACOCO_JAR}" \
  -d "${COVERAGE_WORKDIR}" \
  "${CONVERTER_SRC}"

# ── Run each test and collect per-test .exec ─────────────────────────────────

EXEC_FILES=()
for target in ${TARGETS}; do
  bin_rel="$(target_to_bin_rel "${target}")"
  bin_path="${BIN_DIR}/${bin_rel}"

  if [[ ! -x "${bin_path}" ]]; then
    echo "SKIP  ${target} (binary not found)"
    continue
  fi

  test_coverage_dir="${COVERAGE_WORKDIR}/${bin_rel}"
  mkdir -p "${test_coverage_dir}"

  echo -n "RUN   ${target} ... "
  JAVA_COVERAGE_FILE="${test_coverage_dir}/jvcov.dat" \
  COVERAGE_DIR="${test_coverage_dir}" \
  COVERAGE=1 \
    "${bin_path}" >/dev/null 2>&1 && echo "ok" || echo "FAIL (tests failed)"

  # The JacocoCoverageRunner writes jvcov<random>.exec (raw probe data).
  for ex in "${test_coverage_dir}"/jvcov*.exec; do
    [[ -s "${ex}" ]] && EXEC_FILES+=("${ex}")
  done
done

if [[ ${#EXEC_FILES[@]} -eq 0 ]]; then
  echo "Error: no coverage data produced." >&2
  exit 1
fi

# ── Identify instrumented jars ───────────────────────────────────────────────

# Only the simulator library jars contain .class.uninstrumented entries.
INSTRUMENTED_JARS=()
for jar in "${BIN_DIR}"/simulator/*.jar; do
  if "${JAR}" tf "${jar}" 2>/dev/null | grep -q '\.class\.uninstrumented$'; then
    INSTRUMENTED_JARS+=("${jar}")
  fi
done

if [[ ${#INSTRUMENTED_JARS[@]} -eq 0 ]]; then
  echo "Error: no instrumented jars found." >&2
  exit 1
fi

# ── Convert .exec → LCOV ────────────────────────────────────────────────────

MERGED="${COVERAGE_WORKDIR}/merged.lcov"

echo ""
echo "Converting .exec → LCOV (${#EXEC_FILES[@]} exec files, ${#INSTRUMENTED_JARS[@]} instrumented jars)..."
"${JAVA}" -cp "${COVERAGE_WORKDIR}:${JACOCO_JAR}" ExecToLcov \
  "${MERGED}" \
  "${EXEC_FILES[@]}" \
  -- \
  "${INSTRUMENTED_JARS[@]}"

# ── Post-process LCOV ────────────────────────────────────────────────────────
#
# 1. JaCoCo reports source files as package/Class.kt (e.g.
#    fourward/simulator/BitVector.kt).  Rewrite SF: lines to use the
#    workspace-relative path (simulator/BitVector.kt) so tools and editors
#    can find the actual source files.
#
# 2. JacocoLCOVFormatter omits LH:/LF: summary lines.  Add them so tools
#    like genhtml can display totals.

FIXED="${COVERAGE_WORKDIR}/fixed.lcov"
awk '
  /^SF:/ {
    sub(/^SF:fourward\//, "SF:")
    lh = 0; lf = 0
    print; next
  }
  /^DA:/ {
    lf++
    split($0, a, ",")
    if (a[2]+0 > 0) lh++
    print; next
  }
  /^end_of_record/ {
    printf "LH:%d\nLF:%d\n", lh, lf
    total_lh += lh; total_lf += lf
    print; next
  }
  { print }
  END { printf "%d %d\n", total_lh, total_lf > "/dev/stderr" }
' "${MERGED}" > "${FIXED}" 2>"${COVERAGE_WORKDIR}/summary.txt"
mv "${FIXED}" "${MERGED}"

read -r total_lh total_lf < "${COVERAGE_WORKDIR}/summary.txt"

echo "LCOV report: ${MERGED}"
if [[ ${total_lf} -gt 0 ]]; then
  pct=$(( 100 * total_lh / total_lf ))
  echo "Line coverage: ${total_lh}/${total_lf} (${pct}%)"
fi

# ── Persist LCOV ─────────────────────────────────────────────────────────────

PERSISTENT="${REPO_ROOT}/coverage.lcov"
cp "${MERGED}" "${PERSISTENT}"
echo "Saved:        ${PERSISTENT}"

# ── HTML report (optional) ───────────────────────────────────────────────────

if command -v genhtml >/dev/null 2>&1; then
  rm -rf "${REPORT_DIR}"
  genhtml "${PERSISTENT}" --output-directory "${REPORT_DIR}" --quiet
  echo "HTML report:  ${REPORT_DIR}/index.html"
elif [[ "${1:-}" == "--html" ]]; then
  echo "Error: genhtml not found (install lcov: brew install lcov)" >&2
  exit 1
fi
