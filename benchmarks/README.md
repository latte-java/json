# Benchmarks

JMH benchmark suite comparing lattejava-json's generated `@JSON` companions against Jackson databind and
Gson on identical workloads, in both directions.

## Prerequisites

- **Java 25+** on the PATH (the library targets Java 25 bytecode)
- **Latte** build tool (`latte` on PATH)
- **jq** for JSON processing (`brew install jq` on macOS)

## Quick Start

```bash
# Full suite: publishes the current tree (latte int), verifies, runs all 36 benchmarks (~15-25 min)
./run-benchmarks.sh

# Smoke test: one scenario, 1 fork, reuse the last integration build
./run-benchmarks.sh --quick --scenarios jwt --skip-int
```

## Libraries Under Test

| Library | Configuration |
|---------|---------------|
| `latte` | The generated `*JSON` companions (`org.lattejava:json` is a **processor-path-only** dependency — the runtime helpers are generated into this module, so there is no runtime jar) |
| `jackson` | `jackson-databind` + `jackson-datatype-jsr310`, `WRITE_DATES_AS_TIMESTAMPS` disabled (ISO-8601 `Instant`), `setSerializationInclusion(NON_NULL)` |
| `gson` | Default `Gson` plus an ISO-8601 `Instant` `TypeAdapter` (Gson has no built-in `java.time` support) |

All three bind the same model records in `src/main/java/.../model/`. The exact configurations live in
`Libraries.java` and are shared by the benchmarks and the correctness pre-flight.

## Scenarios

| Scenario | Shape | ~Size | What it stresses |
|----------|-------|-------|------------------|
| `jwt` | Flat claims object: strings, longs, a string list | 215 B | The library's origin use case; per-call overhead |
| `api` | Typical API resource: nested objects, lists, enum, UUID, Instant | 1.5 KB | Mixed-type dispatch, nesting |
| `large` | Object holding a list of 1,000 small records | 110 KB | Sustained throughput, list handling |
| `strings` | Escape-heavy + multibyte/unicode strings | 10 KB | Escaping and UTF-8 paths |
| `numbers` | Long, double, and BigDecimal heavy | 13 KB | Number parsing/formatting |
| `deep` | Recursive record nested to depth 14 | 0.8 KB | Recursion/dispatch near the depth cap |

Each scenario runs in both directions (`serialize`: object → `byte[]`, `deserialize`: fixture `byte[]` →
object) for each library: 36 benchmarks total. Fixtures are checked in under
`src/main/resources/payloads/` and regenerated with `GenerateFixtures` when `Payloads` changes.

## Fairness

- **`byte[]` is the boundary.** Real inputs and outputs are bytes (network, disk, JWT segments). Gson's
  API is String-based, so its benchmarks include the UTF-8 conversion — that is its genuine cost at this
  boundary, not a handicap.
- **Gson's default HTML escaping is kept** (`<`, `>`, `&`, `=`, `'` become `<` etc.). That is the
  out-of-the-box behavior production users get; it inflates Gson's output size on the `strings` scenario.
- **Null omission matches everywhere**: latte `omitNulls` default, Jackson `NON_NULL`, Gson default.
- **Correctness is verified, not assumed.** `run-benchmarks.sh` runs `Verify` first: every library must
  deserialize each fixture to the expected object, round-trip its own output, and round-trip every other
  library's output (record equality — key order and number formatting are free to differ). A failing
  library is excluded from the run, loudly.

## Usage

```
./run-benchmarks.sh [OPTIONS]

Options:
  --libraries <list>   Comma-separated library list (default: all)
  --scenarios <list>   Comma-separated scenario list (default: all)
  --modes <list>       Comma-separated mode list (default: serialize,deserialize)
  --label <name>       Label for the results file
  --output <dir>       Output directory (default: benchmarks/results/)
  --quick              1 fork, 3 warmup + 3 measurement iterations (smoke runs)
  --skip-int           Skip 'latte int' at the repo root (reuse the last integration build)
  -h, --help           Show this help
```

## Results

Results are written to `results/YYYY-MM-DDTHH-MM-SSZ[-label].json`:

```json
{
  "version": 1,
  "timestamp": "...",
  "system": { "os": "...", "machineModel": "...", "cpuModel": "...", "javaVersion": "..." },
  "tools": { "jmhVersion": "1.37", "libraryVersions": { "latte": "...", "jackson": "...", "gson": "..." } },
  "results": [
    { "library": "latte", "scenario": "jwt", "mode": "deserialize",
      "metrics": { "ops_per_sec": 0, "ops_per_sec_error": 0, "alloc_bytes_per_op": 0 } }
  ]
}
```

Compare two runs (regression tracking):

```bash
./compare-results.sh results/<baseline>.json results/<comparison>.json
```

Publish the latest results into the root `README.md` (`## Performance` section):

```bash
./update-readme.sh [results-file]
```

## Methodology

- **JMH 1.37**, throughput mode (ops/s), 2 forks, 5 × 1 s warmup + 5 × 1 s measurement per benchmark.
  `--quick` drops to 1 fork, 3 + 3 for smoke runs.
- **`-prof gc`** supplies `gc.alloc.rate.norm` (bytes/op) — the allocation headline.
- Every benchmark runs in its own forked JVM, so libraries never share JIT profiles.
- Run on an idle machine. Laptop numbers wobble a few percent (thermal, background load); trust ratios
  between libraries and between runs over absolute values.
