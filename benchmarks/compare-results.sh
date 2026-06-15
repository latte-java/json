#!/usr/bin/env bash

#
# Copyright (c) 2026 The Latte Project
# SPDX-License-Identifier: MIT
#

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <baseline.json> <comparison.json>"
  echo "Prints comparison/baseline ratios per (library, scenario, mode)."
  exit 1
fi

BASELINE="$1"
COMPARISON="$2"

echo "Baseline:   ${BASELINE} ($(jq -r '.timestamp' "${BASELINE}"))"
echo "Comparison: ${COMPARISON} ($(jq -r '.timestamp' "${COMPARISON}"))"
echo ""
printf "%-9s %-10s %-13s %12s %12s %8s %10s %10s %8s\n" \
  "Library" "Scenario" "Mode" "Ops/s A" "Ops/s B" "Ratio" "Alloc A" "Alloc B" "Ratio"
printf '%.0s-' {1..100}; echo ""

jq -n -r --slurpfile a "${BASELINE}" --slurpfile b "${COMPARISON}" '
  ($a[0].results | map({key: "\(.library)/\(.scenario)/\(.mode)", value: .metrics}) | from_entries) as $base |
  $b[0].results[] |
  . as $r |
  ($base["\($r.library)/\($r.scenario)/\($r.mode)"] // null) as $bm |
  select($bm != null) |
  [$r.library, $r.scenario, $r.mode,
   ($bm.ops_per_sec), ($r.metrics.ops_per_sec), ($r.metrics.ops_per_sec / $bm.ops_per_sec),
   ($bm.alloc_bytes_per_op // 0), ($r.metrics.alloc_bytes_per_op // 0),
   (if ($bm.alloc_bytes_per_op // 0) > 0 then (($r.metrics.alloc_bytes_per_op // 0) / $bm.alloc_bytes_per_op) else 0 end)]
  | @tsv' | \
  while IFS=$'\t' read -r lib scn mode opsa opsb ratio alla allb allr; do
    printf "%-9s %-10s %-13s %12.0f %12.0f %7.2fx %10.1f %10.1f %7.2fx\n" \
      "${lib}" "${scn}" "${mode}" "${opsa}" "${opsb}" "${ratio}" "${alla}" "${allb}" "${allr}"
  done

echo ""
echo "Ops ratio > 1.00 means the comparison run is faster; alloc ratio < 1.00 means it allocates less."
