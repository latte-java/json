# Performance Rewrite: Streaming Serializer + Byte-Level Parser

Goal: lattejava-json beats Jackson databind 2.19.0 and Gson 2.14.0 in **every** benchmark cell — all six
scenarios, both directions, throughput *and* bytes/op — per `benchmarks/` (baseline:
`benchmarks/results/2026-06-12T22-29-48Z-initial.json`).

## Baseline and root causes

Baseline (M4 MacBook Air, Java 25): Jackson wins all 12 throughput cells; latte allocates up to 10× Jackson
on serialize (`large`: 2.24 MB/op for a 110 KB payload) and is the slowest deserializer in most scenarios.

The causes are structural, confirmed by reading the runtime helpers and generated code:

**Serialize (`JSONBuilder`/`JSONArrayBuilder`)**
1. Every nested object/list serializes into its **own** `ByteArrayOutputStream`, decodes to a `String`
   (`build()`), and the parent **re-encodes** it (`writeRaw` → `getBytes`). The whole payload is UTF-8
   encoded/decoded once per nesting level — `BatchJSON.itemsToJSON` does `ItemJSON.toJSON(e)` per element.
2. `ByteArrayOutputStream` writes are `synchronized`, byte-at-a-time for structure chars.
3. Per-value garbage: `writeRaw(s.substring(...))` + `getBytes` per run, `Long.toString`,
   `BigDecimal.valueOf(double).toPlainString()`, `String.format("\\u%04x", …)`.

**Deserialize (`JSONParser`)**
4. `parse(byte[])` decodes the **entire input** to a `String` up front.
5. `parseString()` builds a `StringBuilder` char-by-char for *every* string — including every object key —
   then `toString()` copies again. No fast path for escape-free strings.
6. The diagnostic path stack does `path.push(key)`/`pop` per value and allocates `"[" + index + "]"` per
   array element (1,000 concats per `large` parse).
7. `parseNumber()` returns a boxed `Number` and dispatches by `instanceof`; every parser carries a fresh
   `ArrayDeque`.

## Approach

Two cycles, same shape as the existing architecture (compile-time codegen + zero-dependency emitted
helpers). Public companion API (`toJSON`, `toJSONBytes`, `fromJSON(String|byte[])`) and the **wire format
are unchanged**; everything below the companions is module-internal and regenerated on every consumer
compile, so the observer/builder interfaces are free to change shape.

### Cycle 1 — streaming serializer (`JSONWriter`)

Replace `JSONBuilder`/`JSONArrayBuilder` with a single `JSONWriter` that owns one growable `byte[]`:

- Companions generate `static void write(JSONWriter w, T value)`; nested companions write **into the same
  writer** (`w.key("billing"); AddressJSON.write(w, v.billing())`). List/Map member helpers become loops
  that write elements directly. Zero intermediate strings, zero re-encoding.
- `toJSON`/`toJSONBytes` acquire a writer, call `write`, and return `new String(buf, 0, n, UTF_8)` /
  `Arrays.copyOf` — the only unavoidable allocation.
- **Buffer recycling:** one `ThreadLocal` recycled buffer, retained up to 1 MB (a larger parse allocates
  fresh and is not retained). Companions are static so per-call buffers would dominate alloc/op; recycling
  makes alloc/op ≈ the output copy. This matches what Jackson's `BufferRecycler` does; the 1 MB retention
  cap bounds per-thread footprint and covers the `large` scenario.
- Writing primitives: longs/ints via a backwards digit loop into the buffer (no `Long.toString`); strings
  via a scan loop — ASCII runs copied directly, non-ASCII encoded inline, escapes emitted from a hex table
  (no `String.format`, no `substring`). `boolean`/`null` via preencoded byte constants.
- `BigDecimal.toPlainString()`/`BigInteger.toString()`/`Double` → `BigDecimal.valueOf(d).toPlainString()`
  are **kept** (wire-format compatibility); their small allocations ride along.
- Comma placement via a depth-indexed "first member" bit set (`long` bits + spill array), replacing the
  `first` flag per builder.
- `@JSONCatchAll`/dynamic-map `any()` writing ports to recursive `JSONWriter` methods (no sub-builders).
- `JSONBuilder`/`JSONArrayBuilder` are deleted (helpers are emitted per consumer module; nothing external
  references them). `JSONWriter` matches the existing `/JSON.*/` helper-copy glob in `project.latte`.

### Cycle 2 — byte-level parser with compile-time key dispatch

Rewrite `JSONParser` to walk the input `byte[]` directly (the `String` overload does one `getBytes` and
delegates), and change typed-observer dispatch from String keys to generated int field ids:

- **Key matching without allocation.** `JSONObserver` gains
  `int field(byte[] src, int offset, int length)`; generated observers match keys with a length switch +
  byte comparisons against `private static final byte[]` key constants and return a field ordinal, `-1`
  for unknown. Value callbacks take the ordinal (`string(int field, String value)`, `integer(int, long)`,
  …). This is strictly stronger than Jackson's interned-symbol table: the key set is known at compile
  time, so a repeated key costs a few byte compares and zero allocation.
- **Unknown keys**: per-observer policy (`SKIP` lenient default, `CAPTURE` for `@JSONCatchAll`/`Any*`
  observers, `REJECT` for `strict`). `SKIP` uses the existing scan-ahead value skipper without
  materializing the key or value; `CAPTURE` materializes the key `String` and uses the existing
  String-keyed callbacks (which remain on the interface as the capture path for `AnyObjectObserver` /
  `AnyArrayObserver`); `REJECT` slices the key only to build the error message.
- **Strings**: scan for the closing quote; if no escape and no non-ASCII byte → `new String(src, start,
  len, ISO_8859_1)`-equivalent fast path via `UTF_8` (single exact-size allocation); otherwise decode into
  a reused per-parser `char[]` scratch and build one `String`. Keys hit this fast path ~always.
- **Numbers**: integers accumulate into a `long` during the digit scan (≤ 18 digits, matching the current
  `Long`/`BigInteger` cutoff) — no boxing, no substring; decimals/`BigInteger` build from the reused char
  scratch (`new BigDecimal(char[], int, int)` has identical semantics to the `String` constructor).
- **Diagnostic paths preserved lazily.** No path stack during parsing. When an error is thrown, a slow-path
  walker re-scans the input from the start to the failure position and reconstructs the identical
  `$...` path string. Error messages — which existing tests assert — do not change.
- `maxNestingDepth` semantics unchanged. Polymorphic scan-ahead (`scanForDiscriminator`) ports to bytes
  unchanged in behavior.

### What this buys, per baseline cell

- `large`/`api` serialize: removes the per-level encode/decode multiplication (the 10–20× alloc factor)
  → expect >5× alloc reduction and 2–4× throughput.
- `jwt` both directions: removes per-call `ArrayDeque`, whole-payload decode, and per-key/string
  `StringBuilder`s — the gap to Jackson (2.3×) is mostly this fixed overhead.
- `strings`: ASCII-run copying + inline escape emission on write; escape-free fast slice on read.
- `numbers`: digit-scan longs, scratch-built decimals.
- `large` deserialize: no key allocs (6,000/parse today), no per-element path concat, no upfront decode.

## Constraints

- **Wire format byte-identical.** Regenerating `benchmarks/src/main/resources/payloads/` after the rewrite
  must produce zero diff (this is a required regression check each cycle).
- **All 282 tests stay green** (`latte test`), including exact error-message assertions.
- **Helper drift guard**: canonical sources in `src/main/java/org/lattejava/json/` and the emitted
  templates in `src/main/resources/org/lattejava/json/internal/` must stay byte-identical modulo the
  package line; the existing drift test is updated for added/removed helpers.
- **Helper-copy globs**: new helper names must match the `project.latte` copy patterns
  (`/JSON.*/` covers `JSONWriter`); removed helpers come out of `HelperEmitter.HELPERS` and the resources.
- **Thread-safety story unchanged**: companions remain static and safe; `JSONWriter`'s recycled buffer is
  `ThreadLocal`; `JSONParser` stays one-instance-per-parse.
- **No public API or annotation changes.** `@JSON`/`@JSONField`/… untouched; processor validation untouched.

## Non-goals

- Changing number formatting (`toPlainString`, `Long`/`BigInteger` 18-digit cutoff) or `Instant` handling.
- SIMD/Vector API, `Unsafe`, or `MethodHandle` tricks — plain Java only.
- Beating Jackson on its own String-input path (`fromJSON(String)` stays correct but the benchmark
  boundary is `byte[]`).
- Map-mode (`Any*`) performance parity — correctness preserved, optimization only where it falls out.

## Verification per cycle

1. `latte test` — 282 green.
2. `cd benchmarks && java ... GenerateFixtures` → `git diff --exit-code src/main/resources/payloads/`.
3. `./run-benchmarks.sh --quick --skip-int` is NOT sufficient for go/no-go; use a full
   `./run-benchmarks.sh --label cycleN` and `./compare-results.sh` against the baseline.
4. Verify cross-matrix must stay 3× PASS.

## Risks

- The observer API reshape touches every JTE observer template (`observerBody`, `arrayObserver`,
  `mapObserver`, `defaultArm`, `polymorphic`) and the `Any*` capture path; the processor test fixtures
  exercise all of them, which is the safety net.
- Buffer recycling retains up to 1 MB per thread that serializes; documented, and a fresh-buffer fallback
  keeps giant payloads correct.
- If a cell still loses after both cycles (most likely `jwt` deserialize vs Jackson), the follow-up lever
  is per-companion specialization (e.g., presized observers, skipping the generic dispatch for flat
  records) — explicitly deferred until the measurements say it's needed.
