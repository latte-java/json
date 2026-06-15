#!/usr/bin/env bash

#
# Copyright (c) 2026 The Latte Project
# SPDX-License-Identifier: MIT
#

set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}"
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ALL_LIBRARIES="latte jackson gson"
ALL_SCENARIOS="api deep jwt large numbers strings"
ALL_MODES="serialize deserialize"
LIBRARIES="${ALL_LIBRARIES}"
SCENARIOS="${ALL_SCENARIOS}"
MODES="${ALL_MODES}"
LABEL=""
OUTPUT_DIR="${SCRIPT_DIR}/results"
QUICK=0
SKIP_INT=0

usage() {
  echo "Usage: $0 [OPTIONS]"
  echo ""
  echo "Options:"
  echo "  --libraries <list>   Comma-separated library list (default: all)"
  echo "                       Available: ${ALL_LIBRARIES// /, }"
  echo "  --scenarios <list>   Comma-separated scenario list (default: all)"
  echo "                       Available: ${ALL_SCENARIOS// /, }"
  echo "  --modes <list>       Comma-separated mode list (default: serialize,deserialize)"
  echo "  --label <name>       Label for the results file"
  echo "  --output <dir>       Output directory (default: benchmarks/results/)"
  echo "  --quick              1 fork, 3 warmup + 3 measurement iterations (smoke runs)"
  echo "  --skip-int           Skip 'latte int' at the repo root (reuse the last integration build)"
  echo "  -h, --help           Show this help"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --libraries) LIBRARIES="${2//,/ }"; shift 2 ;;
    --scenarios) SCENARIOS="${2//,/ }"; shift 2 ;;
    --modes)     MODES="${2//,/ }"; shift 2 ;;
    --label)     LABEL="$2"; shift 2 ;;
    --output)    OUTPUT_DIR="$2"; shift 2 ;;
    --quick)     QUICK=1; shift ;;
    --skip-int)  SKIP_INT=1; shift ;;
    -h|--help)   usage ;;
    *)           echo "Unknown option: $1"; usage ;;
  esac
done

check_command() {
  if ! command -v "$1" &>/dev/null; then
    echo "ERROR: '$1' is not installed or not in PATH."
    exit 1
  fi
}

check_command latte
check_command java
check_command jq

benchmark_class() {
  case "$1" in
    latte)   echo "ALatteBenchmark" ;;
    jackson) echo "JacksonBenchmark" ;;
    gson)    echo "GsonBenchmark" ;;
    *)       echo ""; return 1 ;;
  esac
}

SUITE_START="${SECONDS}"
echo "=== latte-java json Benchmark Suite (started $(date '+%H:%M:%S')) ==="
echo ""

OS="$(uname -s)"
ARCH="$(uname -m)"
CPU_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
if [[ "${OS}" == "Darwin" ]]; then
  CPU_MODEL="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  RAM_GB="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))"
  MACHINE_MODEL="$(system_profiler SPHardwareDataType 2>/dev/null | grep 'Model Name' | sed 's/.*: *//' || echo unknown)"
  OS_VERSION="$(sw_vers -productName 2>/dev/null || echo macOS) $(sw_vers -productVersion 2>/dev/null || echo unknown)"
else
  CPU_MODEL="$(lscpu 2>/dev/null | grep 'Model name' | sed 's/.*: *//' || echo unknown)"
  RAM_GB="$(( $(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0) / 1048576 ))"
  MACHINE_MODEL="$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || echo unknown)"
  if [[ -f /etc/os-release ]]; then
    OS_VERSION="$(. /etc/os-release && echo "${PRETTY_NAME}")"
  else
    OS_VERSION="Linux $(uname -r)"
  fi
fi
JAVA_VERSION="$(java -version 2>&1 | head -1 || echo unknown)"

LATTE_VERSION="$(sed -n 's/.*name: "json", version: "\([^"]*\)".*/\1/p' "${ROOT_DIR}/project.latte")"
JACKSON_VERSION="$(sed -n 's/.*jackson-databind:\([0-9][0-9.]*\)".*/\1/p' "${SCRIPT_DIR}/project.latte")"
GSON_VERSION="$(sed -n 's/.*gson:gson:\([0-9][0-9.]*\)".*/\1/p' "${SCRIPT_DIR}/project.latte")"
JMH_VERSION="$(sed -n 's/.*jmh-core:\([0-9][0-9.]*\)".*/\1/p' "${SCRIPT_DIR}/project.latte")"

echo "Machine:  ${MACHINE_MODEL}"
echo "OS:       ${OS_VERSION}"
echo "System:   ${OS} ${ARCH}, ${CPU_CORES} cores, ${RAM_GB}GB RAM"
echo "CPU:      ${CPU_MODEL}"
echo "Java:     ${JAVA_VERSION}"
echo "Versions: latte ${LATTE_VERSION}, jackson ${JACKSON_VERSION}, gson ${GSON_VERSION}, jmh ${JMH_VERSION}"
echo ""

if [[ "${SKIP_INT}" -eq 0 ]]; then
  echo "--- Publishing the current library tree (latte int at repo root) ---"
  (cd "${ROOT_DIR}" && latte int)
  echo ""
fi

echo "--- Building the benchmarks project ---"
(cd "${SCRIPT_DIR}" && latte clean app)
echo ""

CLASSPATH="${SCRIPT_DIR}/build/dist/lib/*"

echo "--- Verifying correctness (round-trip cross-matrix) ---"
LIBS_CSV="${LIBRARIES// /,}"
VERIFY_OUT="$(java -cp "${CLASSPATH}" org.lattejava.json.benchmarks.Verify "${LIBS_CSV}" 2>&1)" || true
echo "${VERIFY_OUT}"
PASSING="$(echo "${VERIFY_OUT}" | awk '/^PASS / {print $2}' | tr '\n' ' ')"
for lib in ${LIBRARIES}; do
  if ! echo " ${PASSING} " | grep -q " ${lib} "; then
    echo "WARNING: ${lib} failed verification; excluding it from this run."
  fi
done
LIBRARIES="$(echo "${PASSING}" | xargs || true)"
if [[ -z "${LIBRARIES}" ]]; then
  echo "ERROR: No library passed verification."
  exit 1
fi
echo ""

CLASSES=""
for lib in ${LIBRARIES}; do
  CLASSES="${CLASSES}$(benchmark_class "${lib}")|"
done
CLASSES="${CLASSES%|}"
SCENARIO_ALT="$(echo "${SCENARIOS}" | tr ' ' '|')"
MODE_ALT="$(echo "${MODES}" | tr ' ' '|')"
REGEX="^org\.lattejava\.json\.benchmarks\.(${CLASSES})\.(${SCENARIO_ALT})_(${MODE_ALT})$"

mkdir -p "${OUTPUT_DIR}"
TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DATE_LABEL="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
if [[ -n "${LABEL}" ]]; then
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}-${LABEL}.json"
else
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}.json"
fi
RAW_FILE="${RESULT_FILE%.json}-jmh-raw.json"

JMH_ARGS=(-prof gc -rf json -rff "${RAW_FILE}")
if [[ "${QUICK}" -eq 1 ]]; then
  JMH_ARGS+=(-f 1 -wi 3 -i 3)
fi

echo "--- Running JMH: ${REGEX} ---"
java -cp "${CLASSPATH}" org.openjdk.jmh.Main "${REGEX}" "${JMH_ARGS[@]}"
echo ""

RESULTS_JSON="$(jq '[.[] | (.benchmark | capture("\\.(?<class>ALatte|Jackson|Gson)Benchmark\\.(?<scenario>[a-z]+)_(?<mode>serialize|deserialize)$")) as $m | {
  library: ($m.class | ascii_downcase | if . == "alatte" then "latte" else . end),
  scenario: $m.scenario,
  mode: $m.mode,
  metrics: {
    ops_per_sec: .primaryMetric.score,
    ops_per_sec_error: .primaryMetric.scoreError,
    alloc_bytes_per_op: ((.secondaryMetrics["gc.alloc.rate.norm"] // .secondaryMetrics["·gc.alloc.rate.norm"]).score // null)
  }
}]' "${RAW_FILE}")"

jq -n \
  --argjson version 1 \
  --arg timestamp "${TIMESTAMP}" \
  --arg os "${OS}" \
  --arg arch "${ARCH}" \
  --arg osVersion "${OS_VERSION}" \
  --arg machineModel "${MACHINE_MODEL}" \
  --arg cpuModel "${CPU_MODEL}" \
  --argjson cpuCores "${CPU_CORES}" \
  --argjson ramGB "${RAM_GB}" \
  --arg javaVersion "${JAVA_VERSION}" \
  --arg jmhVersion "${JMH_VERSION}" \
  --arg latteVersion "${LATTE_VERSION}" \
  --arg jacksonVersion "${JACKSON_VERSION}" \
  --arg gsonVersion "${GSON_VERSION}" \
  --argjson results "${RESULTS_JSON}" \
  '{
    version: $version,
    timestamp: $timestamp,
    system: {
      os: $os, arch: $arch, osVersion: $osVersion, machineModel: $machineModel,
      cpuModel: $cpuModel, cpuCores: $cpuCores, ramGB: $ramGB, javaVersion: $javaVersion
    },
    tools: {
      jmhVersion: $jmhVersion,
      libraryVersions: { latte: $latteVersion, jackson: $jacksonVersion, gson: $gsonVersion }
    },
    results: $results
  }' > "${RESULT_FILE}"

rm -f "${RAW_FILE}"
echo "=== Results written to ${RESULT_FILE} ==="
echo ""

echo "=== Summary ==="
echo ""
printf "%-9s %-10s %-13s %16s %18s\n" "Library" "Scenario" "Mode" "Ops/sec" "Alloc B/op"
printf "%-9s %-10s %-13s %16s %18s\n" "---------" "----------" "-------------" "----------------" "------------------"
echo "${RESULTS_JSON}" | jq -r '.[] | [.library, .scenario, .mode, (.metrics.ops_per_sec | tostring), ((.metrics.alloc_bytes_per_op // 0) | tostring)] | @tsv' | \
  while IFS=$'\t' read -r lib scn mode ops alloc; do
    printf "%-9s %-10s %-13s %16.0f %18.1f\n" "${lib}" "${scn}" "${mode}" "${ops}" "${alloc}"
  done

SUITE_ELAPSED=$(( SECONDS - SUITE_START ))
echo ""
echo "=== Done ($(( SUITE_ELAPSED / 60 ))m $(( SUITE_ELAPSED % 60 ))s) ==="
