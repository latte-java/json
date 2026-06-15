# JSON Library Benchmarks

Comparative benchmark suite measuring `org.lattejava:json` against Jackson databind and Gson, modeled on the `latte-java/http` benchmark system (orchestrating shell script, JSON result files with system metadata, comparison script, README updater).

## Goals

- Produce repeatable, defensible throughput and allocation numbers for lattejava-json vs Jackson databind vs Gson on identical workloads, in both directions (serialize and deserialize).
- Mirror the http benchmarks workflow: one command runs the suite, results land as timestamped JSON in `benchmarks/results/`, a compare script diffs two runs, and an update script publishes the latest numbers into the root `README.md`.
- Verify correctness before measuring: every library must round-trip every fixture to semantically equal JSON before its numbers count.

## Non-goals

- **Polymorphism scenarios.** Gson core has no built-in polymorphic support (`RuntimeTypeAdapterFactory` lives in unreleased gson-extras), so there is no level playing field. Future work.
- **Other libraries** (dsl-json, fastjson2, moshi, jsoniter). The `--libraries` flag and per-library benchmark classes leave room to add them later.
- **String-input/output benchmark variants.** All benchmarks produce/consume `byte[]` (see Fairness below). A String-based matrix would double the surface for little insight.
- **JFR/sustained-load mode.** JMH's GC profiler covers allocation; an http-style `perf-test.sh` with JFR can come later if needed.

## Approach

A single Latte project at `benchmarks/` (repo root), holding:

- Shared model records annotated `@JSON` — all three libraries bind the same classes.
- Fixture payloads as checked-in `.json` resources.
- One JMH benchmark class per library (`LatteBenchmark`, `JacksonBenchmark`, `GsonBenchmark`), each with one `@Benchmark` method per scenario × direction.

JMH provides what separate server processes provided in http: every benchmark runs in its own forked JVM, so libraries never share JIT profiles. This single-project layout is the field standard (jackson-benchmarks, jvm-serializers).

### Layout

```
benchmarks/
  project.latte
  run-benchmarks.sh        # orchestrator (build → verify → JMH → results JSON)
  compare-results.sh       # diff two result files, normalized ratios
  update-readme.sh         # publish latest results into root README.md
  results/                 # gitignored, .gitkeep
  README.md                # prerequisites, usage, scenario docs (http style)
  src/main/java/org/lattejava/json/benchmarks/
    model/                 # @JSON-annotated records shared by all libraries
    LatteBenchmark.java
    JacksonBenchmark.java
    GsonBenchmark.java
    Verify.java            # round-trip correctness pre-flight (plain main)
    Payloads.java          # loads fixture resources into byte[]/objects
  src/main/resources/payloads/
    jwt.json  api.json  large.json  strings.json  numbers.json  deep.json
```

The project is a named JPMS module (`org.lattejava.json.benchmarks`) — `JSONProcessor` requires one (it emits its runtime helpers into `<module>.internal` and errors on the unnamed module). At runtime everything runs on the classpath (`java -cp`), where `module-info.class` is inert, so JMH and reflection-based binding are unaffected.

## Scenarios

Each scenario is a model shape plus a fixture payload, benchmarked in both directions. All payloads stay within lattejava-json's default parse caps (depth 16, 1000 members, 10000 elements).

| Scenario | Shape | ~Size | What it stresses |
|----------|-------|-------|------------------|
| `jwt` | Flat claims object: strings, longs, a string list | 300 B | The library's origin use case; per-call overhead |
| `api` | Typical API resource: nested objects, lists, enum, UUID, Instant | 1.5 KB | Mixed-type dispatch, nesting |
| `large` | Object holding a list of 1,000 small records | 100 KB | Sustained throughput, list handling |
| `strings` | Escape-heavy + multibyte/unicode strings | 10 KB | Escaping and UTF-8 paths |
| `numbers` | Long and BigDecimal heavy | 10 KB | Number parsing/formatting |
| `deep` | Nested to depth 14 | 2 KB | Recursion/dispatch overhead near the depth cap |

Matrix: 3 libraries × 6 scenarios × 2 directions = 36 benchmarks.

- **Serialize**: pre-built object graph → `byte[]`. Latte: `XJSON.toJSONBytes(value)`. Jackson: `ObjectMapper.writeValueAsBytes(value)`. Gson: `gson.toJson(value).getBytes(UTF_8)`.
- **Deserialize**: fixture `byte[]` → object graph. Latte: `XJSON.fromJSON(bytes)`. Jackson: `ObjectMapper.readValue(bytes, X.class)`. Gson: `gson.fromJson(new String(bytes, UTF_8), X.class)`.

## Fairness rules

- **`byte[]` is the boundary.** Real inputs and outputs are bytes (network, disk, JWT segments). Gson's API is String-based, so its benchmarks include the UTF-8 conversion — that is its genuine cost at this boundary, not a handicap, and the README documents it.
- **Each library configured the way a production user would.** Jackson: one shared `ObjectMapper` with `jackson-datatype-jsr310` registered, `WRITE_DATES_AS_TIMESTAMPS` disabled (for `Instant` as ISO-8601), and `setSerializationInclusion(NON_NULL)` (matching latte's `omitNulls` default and Gson's default null omission). Gson: one shared `Gson` with a registered `Instant` `TypeAdapter` (Gson has no native `java.time` support). Latte: the generated companions as-is. All configuration is visible in the benchmark classes.
- **Identical model classes.** All three libraries bind the same records. Jackson (2.12+) and Gson (2.10+) both support records natively. The `@JSON` annotation is SOURCE-retention, so it is invisible to the competitors at runtime.
- **Output equivalence is verified, not assumed.** `Verify` round-trips every fixture through every library and compares the results semantically (parse both outputs, compare trees — key order and number formatting may differ). The orchestrator runs it before any timing; a mismatch aborts that library's run loudly.

## JMH methodology

- **JMH 1.37**, wired through Latte's annotation-processor support (`compile-processors` group), same mechanism the benchmark project uses for `org.lattejava:json` itself.
- **Mode: throughput (ops/s)** — the headline, mirroring http's RPS. JMH also reports the error margin.
- **GC profiler (`-prof gc`)** — `gc.alloc.rate.norm` (bytes/op) is the allocation headline, mirroring `alloc_bytes_per_req` in http's perf-test.
- **Defaults: 2 forks, 5 × 1 s warmup, 5 × 1 s measurement** per benchmark (~36 × 2 × 10 s ≈ 12 minutes full suite). `--quick` drops to 1 fork, 3 + 3 iterations for smoke runs.
- Benchmarks return the produced value (JMH blackholes returns); `@State(Scope.Benchmark)` holds fixtures and pre-built object graphs.
- The script invokes `org.openjdk.jmh.Main` with a regex built from `--libraries`/`--scenarios`/`--modes`, plus `-rf json` for machine-readable raw output.

## Build wiring (`project.latte`)

- `compile-processors` group: `org.lattejava:json:0.3.0-{integration}` and `org.openjdk.jmh:jmh-generator-annprocess:1.37`. `run-benchmarks.sh` runs `latte int` at the repo root first (skippable with `--skip-int`), so the suite always measures the current tree.
- `compile` group: `org.openjdk.jmh:jmh-core:1.37`, `com.fasterxml.jackson.core:jackson-databind:2.19.0`, `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.0`, `com.google.code.gson:gson:2.14.0`. Versions are pinned in `project.latte` and recorded in every result file.
- `java.settings.compilerArguments = "-s src/generated/java"` for processor output, matching the `app` project's convention.
- **Zero runtime dependency on `org.lattejava:json`**: the processor emits the runtime helpers into the benchmark module itself, so the dependency is processor-path only. The benchmark project's dependency list itself demonstrates the zero-dependency story.
- An `app`-style target assembles `build/dist` with the JMH-runnable classpath, mirroring the http competitor projects.

## Orchestration and results

`run-benchmarks.sh`, structured like http's:

```
./run-benchmarks.sh [--libraries latte,jackson,gson] [--scenarios list]
                    [--modes serialize,deserialize] [--label name]
                    [--output dir] [--quick]
```

Flow: prerequisites check (`latte`, `java`, `jq`) → `latte clean app` → run `Verify` → run JMH with `-rf json` → wrap JMH's raw JSON into the http-style envelope with `jq` → print a summary table.

Result file (`results/YYYY-MM-DDTHH-MM-SSZ[-label].json`):

```json
{
  "version": 1,
  "timestamp": "...",
  "system": { "os", "arch", "osVersion", "machineModel", "cpuModel", "cpuCores", "ramGB", "javaVersion" },
  "tools": { "jmhVersion": "1.37", "libraryVersions": { "latte": "0.3.0", "jackson": "...", "gson": "..." } },
  "results": [
    {
      "library": "latte",
      "scenario": "jwt",
      "mode": "deserialize",
      "metrics": { "ops_per_sec": 0, "ops_per_sec_error": 0, "alloc_bytes_per_op": 0 }
    }
  ]
}
```

`compare-results.sh A.json B.json` prints per-(library, scenario, mode) ratios, like http's. `update-readme.sh` rewrites the `## Performance` section of the root `README.md` from the newest result file: one table per direction, scenarios as rows, libraries as columns, ops/s with alloc/op beneath.

## Error handling

- `set -euo pipefail` throughout; the script aborts a library's run (and says so) if `Verify` fails or JMH exits non-zero, but continues with the remaining libraries — same spirit as http's per-server skip-and-continue.
- `Verify` failures print the first differing path in the compared trees, not just "mismatch".

## Testing

The suite's own correctness is covered by `Verify` (run on every benchmark invocation, not just in CI). No TestNG suite in the benchmarks project — it is a measurement harness, and `Verify` plus the JMH error margins are the meaningful checks. The root project's `latte test` is untouched.

## Risks / notes

- The root `README.md` still documents the old `byte[] ⇄ Map` surface; `update-readme.sh` only touches the `## Performance` section, so updating the rest of the README is separate work.
- JMH numbers on a laptop (thermal throttling, background load) wobble a few percent; the README will carry the same "run on an idle machine, compare ratios not absolutes" guidance the http README implies. `--label` plus `compare-results.sh` is the regression-tracking workflow.
- Gson's `Instant` adapter is hand-written; it must match the ISO-8601 wire format the other two produce or `Verify` will rightly fail.

## Future work

- Polymorphism scenario (latte vs Jackson `@JsonTypeInfo`; Gson excluded or via gson-extras copy).
- Additional libraries (dsl-json, fastjson2, moshi).
- Sustained-load JFR mode (`perf-test.sh` analog) for GC-pause and heap-peak metrics.
