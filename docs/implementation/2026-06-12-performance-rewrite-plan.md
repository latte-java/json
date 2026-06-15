# Performance Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/design/2026-06-12-performance-rewrite-design.md` — streaming `JSONWriter` serializer (Cycle 1) and byte-level `JSONParser` with int-field dispatch (Cycle 2) — so lattejava-json beats Jackson and Gson in every benchmark cell.

**Architecture:** Replace `JSONBuilder`/`JSONArrayBuilder` with one growable-buffer `JSONWriter` (ThreadLocal recycling ≤ 1 MB); companions generate `write(JSONWriter, T)` and nested types share the writer. Then rewrite `JSONParser` to walk `byte[]` directly with generated `field(byte[],off,len)` key matching (int ordinals, zero key allocation), escape-free string slicing, in-scan long accumulation, and lazy error-path reconstruction.

**Tech Stack:** Pure Java 25, JTE codegen templates, TestNG. No new dependencies.

**Invariants enforced after EVERY task:** `latte test` green (282+); helper sources in `src/main/java/org/lattejava/json/` byte-identical (modulo package line) to `src/main/resources/org/lattejava/json/internal/` (drift test); wire format unchanged (`GenerateFixtures` produces zero git diff); error messages (including `$...` paths) unchanged.

---

## Cycle 1 — streaming serializer

### Task 1: `JSONWriter` helper + unit tests (TDD)

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONWriter.java` (+ mirror in `src/main/resources/org/lattejava/json/internal/`)
- Create: `src/test/java/org/lattejava/json/tests/JSONWriterTest.java`
- Modify: `src/main/java/org/lattejava/json/processor/HelperEmitter.java` (add `JSONWriter` to `HELPERS`)

- [ ] Write `JSONWriterTest` first, porting every behavioral assertion from `JSONBuilderTest`/`JSONArrayBuilderTest` (escaping table incl. `\u` < 0x20, omitNulls on/off, key ordering, empty object/array) to the new API; add: nested-writer sharing (one buffer), long/int digit writing edge cases (`Long.MIN_VALUE`, 0, negatives), non-ASCII (2/3/4-byte UTF-8, surrogate pairs), recycling (two sequential serializations reuse the buffer; > 1 MB output is not retained), `toString()` vs `toBytes()` equality.
- [ ] Implement `JSONWriter`:
  - Growable `byte[]`; acquire/release via a `ThreadLocal` holding one buffer (retain only if `buf.length <= 1 MB`).
  - API: `static JSONWriter acquire(boolean omitNulls)`, `String toString()`/`byte[] toBytes()` (both release), structural `beginObject()/endObject()/beginObjectMember(String key)/beginArrayMember(String key)/endArray()/elementSeparator()` driven by a depth-indexed first-bit (`long` mask + spill `boolean[]`), members `string/integer(long)/integer(Number)/bigInteger/decimal(BigDecimal|Double|Float)/bool/nullValue` with the same null/omitNulls semantics as `JSONBuilder`, raw element variants for array contexts, and `any(String,Object)`/`anyElement(Object)` (recursive, replacing the builder `any` paths).
  - Long digits written backwards into a 20-byte scratch; escapes via a static hex table; ASCII runs copied byte-by-byte from `charAt` (no substring/getBytes); non-ASCII encoded inline (incl. surrogate pairs → 4-byte sequences).
  - Wire-format identical: `BigDecimal.toPlainString()`, `BigDecimal.valueOf(double).toPlainString()`, `BigInteger.toString()`, `value.toString()` for other `Number`s.
- [ ] Mirror into resources, add to `HELPERS`, update the drift test's helper list if it enumerates names. `latte test` green. Commit.

### Task 2: Generated serialization writes through `JSONWriter`

**Files:**
- Modify: `src/main/jte/companion.jte` (replace `builder(T)`/`build()` with `write(JSONWriter, T)` + `toJSON`/`toJSONBytes` acquiring the writer)
- Modify: `src/main/jte/memberCall.jte`, `src/main/jte/arrayAppend.jte`, `src/main/jte/mapObserver.jte` (the `*ToJSON` map/list member helpers become `write*` loops on the shared writer), `src/main/jte/polymorphic.jte` (dispatcher writes discriminator then delegates `write`)
- Modify: `src/main/java/org/lattejava/json/jte/*.java` views if template params change
- Delete: `src/main/java/org/lattejava/json/JSONBuilder.java`, `JSONArrayBuilder.java`, their resource mirrors, their entries in `HELPERS`, and `JSONBuilderTest`/`JSONArrayBuilderTest` (coverage lives in `JSONWriterTest`)

- [ ] Template changes keep emission order and null handling identical; nested/list/map members call `XJSON.write(w, v)` inside structural begin/end calls instead of `toJSON()` string splicing.
- [ ] `latte test` green (processor fixture tests recompile all generated shapes). Commit.

### Task 3: Cycle 1 verification + benchmark

- [ ] Root: `latte test` → 282+ green.
- [ ] `latte int`; `cd benchmarks && latte clean app`; regenerate fixtures → `git diff --exit-code benchmarks/src/main/resources/payloads/` (wire format unchanged); `Verify` → 3× PASS.
- [ ] Full run: `./run-benchmarks.sh --label cycle1` (idle machine); `./compare-results.sh results/*initial.json results/*cycle1.json`.
- [ ] Expected: all 6 serialize cells ≥ Jackson throughput, alloc within ~1.5× of output size. If a serialize cell still loses, profile that cell (`-prof gc`, read generated code) and fix before proceeding. Commit results summary note in the commit message.

## Cycle 2 — byte-level parser

### Task 4: Observer interface reshape

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONObserver.java` — add `int field(byte[] src, int offset, int length)` (default `-1`), `UnknownPolicy unknownPolicy()` (default `SKIP`; values `SKIP`/`CAPTURE`/`REJECT`), int-id value callbacks (`string(int,String)`, `integer(int,long)`, `bigInteger(int,BigInteger)`, `decimal(int,BigDecimal)`, `bool(int,boolean)`, `nullValue(int)`, `beginObject(int)`, `object(int,Object)`, `beginArray(int)`, `array(int,Object)`); keep String-keyed methods as the CAPTURE path (default no-ops calling nothing).
- Modify: `AnyObjectObserver` (`unknownPolicy()` → CAPTURE; String path unchanged), `SkipObserver` (SKIP; field → -1), mirrors in resources.
- [ ] `latte test` after interface lands with templates still emitting the String path (transitional default methods keep old generated code compiling is NOT possible once the parser switches — so Tasks 4–6 land as one commit series with tests run at the series end; keep commits granular but expect green only at Task 6).

### Task 5: `JSONParser` byte rewrite

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONParser.java` (+ mirror)
- [ ] Walk `byte[] src` directly; `parse(String)` does one `getBytes(UTF_8)`. Structure bytes compared as bytes; whitespace skip on bytes.
- [ ] Keys: scan to closing quote; no-escape → `target.field(src, start, len)`; ordinal ≥ 0 → typed int-id dispatch; `-1` → policy: SKIP = existing scan-ahead skipper (ported to bytes), CAPTURE = materialize key String + String-keyed dispatch, REJECT = error with sliced key. Escaped keys: decode then a generated `fieldOf(String)` fallback (add to interface, default `-1`).
- [ ] Strings: quote scan; escape-free → `new String(src, start, len, UTF_8)`; else decode into reused `char[]` scratch (surrogate handling identical to current code).
- [ ] Numbers: digit scan accumulating `long` with the 18-digit cutoff (sign handled; >18 digits → `BigInteger` from slice; `.`/`e` → `BigDecimal` from reused char scratch). Same validation errors (leading zeros, bare `-`, etc.).
- [ ] Errors: record failure position; reconstruct the `$...` path by re-scanning from 0 with a small dedicated walker (slow path only). Every message string identical to today's.
- [ ] Polymorphic discriminator scan ported to bytes; `parseObjectIntoSkippingKey` ported (skip compares key slice to discriminator bytes).

### Task 6: Generated observers emit `field()` + int dispatch

**Files:**
- Modify: `src/main/jte/observerBody.jte` (emit `private static final byte[] KEY_<name>` constants, `field()` length-switch + byte compares, `fieldOf(String)` fallback, int-ordinal switches in all value callbacks; `strict` → `unknownPolicy() = REJECT`; catch-all → CAPTURE with existing String-path capture), `defaultArm.jte`, `arrayObserver.jte`/`mapObserver.jte` (element paths keep current shape — array callbacks are keyless), `polymorphic.jte`.
- [ ] `latte test` green — this closes the Task 4–6 series. Fixture-based processor tests recompile every shape (records, nested, polymorphic, catch-all, dynamic map, beans, @JSONConstructor). Update parser/observer unit tests (`JSONParser*Test`, `AnyObjectObserverTest`, `SkipObserverTest`) to the new interface where they hand-roll observers; error-message assertions must pass UNCHANGED. Commit series.

### Task 7: Cycle 2 verification + full benchmark + publish

- [ ] `latte test` green; fixtures zero-diff; `Verify` 3× PASS.
- [ ] Full run: `./run-benchmarks.sh --label cycle2`; compare vs `initial` and `cycle1`.
- [ ] **Goal gate:** latte wins all 12 cells on ops/sec AND alloc B/op vs both Jackson and Gson. Any losing cell: profile (generated code + `-prof gc` + JFR if needed), apply the deferred lever (per-companion specialization) for that cell, re-run. Iterate until green or a cell is demonstrably JVM-bound (document if so).
- [ ] `./update-readme.sh`; commit results + README; update `docs/design/2026-06-12-performance-rewrite-design.md` status notes if levers beyond the design were needed.

## Self-review notes

- Tasks 4–6 are one atomic series (interface + parser + templates must move together); the plan calls that out instead of pretending each lands green alone.
- Drift guard and helper-glob constraints are restated in Tasks 1–2 where files are added/removed.
- All error-message/wire-format invariants have explicit verification commands.
