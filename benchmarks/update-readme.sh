#!/usr/bin/env bash

#
# Copyright (c) 2026 The Latte Project
# SPDX-License-Identifier: MIT
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
README="${ROOT_DIR}/README.md"

RESULT_FILE="${1:-}"
if [[ -z "${RESULT_FILE}" ]]; then
  RESULT_FILE="$(ls -t "${SCRIPT_DIR}"/results/*.json 2>/dev/null | head -1 || true)"
fi
if [[ -z "${RESULT_FILE}" || ! -f "${RESULT_FILE}" ]]; then
  echo "ERROR: No results file found. Run ./run-benchmarks.sh first or pass a file."
  exit 1
fi

echo "Using ${RESULT_FILE}"

SECTION="$(mktemp)"
{
  echo "## Performance"
  echo ""
  echo "Benchmarked with JMH against Jackson databind and Gson; full methodology in [\`benchmarks/\`](benchmarks/)."
  echo ""
  jq -r '"- " + .timestamp + " · " + .system.machineModel + " (" + .system.cpuModel + ") · " + .system.javaVersion' "${RESULT_FILE}"
  jq -r '"- Versions: latte " + .tools.libraryVersions.latte + ", jackson " + .tools.libraryVersions.jackson + ", gson " + .tools.libraryVersions.gson + ", JMH " + .tools.jmhVersion' "${RESULT_FILE}"
  for mode in serialize deserialize; do
    echo ""
    echo "### ${mode} (ops/sec; alloc bytes/op in parentheses — higher ops and lower alloc are better)"
    echo ""
    echo "| Scenario | latte | jackson | gson |"
    echo "|----------|-------|---------|------|"
    for scenario in $(jq -r '[.results[].scenario] | unique | .[]' "${RESULT_FILE}"); do
      row="| ${scenario} |"
      for lib in latte jackson gson; do
        cell="$(jq -r --arg l "${lib}" --arg s "${scenario}" --arg m "${mode}" '
          [.results[] | select(.library == $l and .scenario == $s and .mode == $m)] |
          if length == 0 then "—"
          else .[0].metrics as $x |
            ($x.ops_per_sec | floor | tostring |
              if (length > 6) then .[0:length-6] + "," + .[length-6:length-3] + "," + .[length-3:]
              elif (length > 3) then .[0:length-3] + "," + .[length-3:]
              else . end) + " (" + (($x.alloc_bytes_per_op // 0) | floor | tostring) + " B/op)"
          end' "${RESULT_FILE}")"
        row="${row} ${cell} |"
      done
      echo "${row}"
    done
  done
  echo ""
} > "${SECTION}"

if grep -q '^## Performance$' "${README}"; then
  awk -v section="${SECTION}" '
    BEGIN { while ((getline line < section) > 0) repl = repl line "\n" }
    /^## Performance$/ { in_perf = 1; printf "%s", repl; next }
    in_perf && /^## / { in_perf = 0 }
    !in_perf { print }
  ' "${README}" > "${README}.tmp"
  mv "${README}.tmp" "${README}"
else
  { echo ""; cat "${SECTION}"; } >> "${README}"
fi
rm -f "${SECTION}"

echo "Updated ${README}"
