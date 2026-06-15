# Performance Reconciliation onto main's Plan-Based Generator

> **Outcome (2026-06-14):** Implemented on `features/json-perf` in four gated phases (A: JSONWriter;
> B: streaming serialize through JSONPlan; C: byte parser + int-field dispatch; D: measure). Every phase
> held the invariants: **byte-identical wire format** (fixtures regen → zero diff), **Verify 3× PASS**, and
> the full suite green (311 tests = main's 323 − 31 deleted builder tests + 19 JSONWriter; the deleted
> `JSONBuilder`/`JSONArrayBuilder` are gone). nested-collections (main's feature) preserved and extended
> (its serialize now streams; its deserialize rides the byte parser unchanged). **Vs main's pre-reconciliation
> generator the win is 1.8×–4.7× throughput in every cell and allocation cut to 2–56%** (results:
> `benchmarks/results/2026-06-14T15-16-51Z-main-baseline.json` vs `…T14-58-06Z-reconciled.json`). Vs the
> competitors latte wins all 24 allocation metrics and all 12 throughput cells against Gson; against Jackson
> it wins throughput in 9/12 (deep/jwt/api/numbers deserialize are robust 1.2–2× wins), with the three close
> cells (strings serialize/deserialize, large serialize) inside thermal noise on the fanless M4 (JMH runs
> latte last, in the hottest phase). The strings-deserialize cell vs Jackson remains the known holdout
> (escape/unicode torture fixture; Jackson's vectorized UTF-8 decode) — future work, as in the original
> rewrite.

Re-apply the performance rewrite (streaming `JSONWriter` serializer + byte-level `JSONParser` with
compile-time int-field dispatch) on top of `main`'s new nested-collections / `JSONPlan` generator, **keeping
main's feature intact**. The original rewrite (`voidmain/load-tests`) and main's nested-collections feature
were independent rewrites of the same generator and cannot be rebased mechanically; this is a deliberate
re-implementation against main's architecture.

## Branches / starting point

- `features/json-benchmarks` (off `main`, held local) = main + the JMH benchmark suite. Verified: builds,
  `Verify` green, fixtures byte-identical under main's generator, main's 342 tests pass.
- `features/json-perf` (this branch, off `features/json-benchmarks`) = base for the reconciliation. It has
  main's library **and** the benchmark suite, so every phase is verifiable against the real benchmarks.
- The original perf code stays on `voidmain/load-tests` as the reference to port from.

## What main has now (the base we build on)

- **Serialize** is string-splicing: `companion.builder()` uses `JSONBuilder`; collection members route through
  `JSONPlan.write(node, value, omitNulls)`, which walks the plan building **raw-JSON Strings** via
  `JSONBuilder`/`JSONArrayBuilder` and splices them with `.raw()`/`.array()`/`.object()`. Nested `@JSON`
  objects serialize via `ObjectLeaf.writer()` = the companion's `toJSON()` (String). This re-encodes every
  nesting level — the exact per-level allocation the rewrite eliminated, now generalized to deep nesting (the
  `large` scenario's allocation problem).
- **Deserialize** uses `observerBody.jte` = a **String-key switch** (the same structure the rewrite started
  from), with collection arms returning runtime plan observers: `beginArray(key) → JSONPlanArrayObserver.of(plan)`,
  map `beginObject(key) → new JSONPlanMapObserver<>(plan)`, dynamic-map → `AnyObjectObserver`, nested `@JSON`
  → the nested companion. `JSONPlan` is a sealed `Node` tree (`ListNode`/`MapNode`/`SetNode`/`ObjectLeaf`/
  `ScalarLeaf`) built once as a `static` field per collection member.
- `JSONArrayObserver` / `JSONObserver` interfaces are **identical** to what the byte parser already drives.
  Verified: `JSONPlanArrayObserver implements JSONArrayObserver` with exactly the callbacks the parser calls.

## Feasibility (verified, not assumed)

- **Deserialize byte parser drops in unchanged against the plan observers.** The byte `JSONParser` drives
  array observers through `string/integer/bigInteger/decimal/bool/nullValue/beginObject/beginArray/object/
  array/finish` — byte-identical to main's `JSONArrayObserver`. So main's deep-nesting deserialize keeps
  working under the byte parser with no changes to the plan observers.
- **main's nested-collections feature is preserved and strictly extended.** The original rewrite did *not*
  support deep nesting (it kept the old flat per-collection observers); this reconciliation keeps main's plan
  observers for deserialize and adds streaming for their serialize. Net: nested collections **plus** the perf
  wins.

## The two wins and how they map

### Win 1 — Serialize: `JSONBuilder` string-splicing → streaming `JSONWriter`

The only genuinely new work vs. the original rewrite, because main's serialize is plan-driven and recursive.

- Port `JSONWriter` (the streaming, recycled-buffer serializer) from `voidmain/load-tests` verbatim, plus its
  direct `Instant`/`UUID`/`double` writers and the SWAR-free string fast paths.
- Companion `builder()` → `write(JSONWriter w, T value)`: top-level object, scalars, nested `@JSON` via
  `XJSON.write(w, v)`, epoch/format members — all as in the original rewrite's `companion.jte`.
- **Rework `JSONPlan` serialize to stream into `JSONWriter`** (the new piece):
  - `ObjectLeaf.writer` changes from `Function<T,String>` (`toJSON`) to `BiConsumer<JSONWriter,T>`
    (the companion's `write(JSONWriter, value)`).
  - `ScalarLeaf.append`/`write` retarget from `JSONArrayBuilder`/`JSONBuilder` to `JSONWriter` element/member
    methods.
  - `JSONPlan.write(node, value, omitNulls): String` → `JSONPlan.writeInto(JSONWriter w, Node, value)` that
    recurses with `w.beginArray()/endArray()`, `w.beginObject()/endObject()`, `w.key()` — no intermediate
    Strings at any level.
  - Companion serialize calls `JSONPlan.writeInto(w, c$plan, value.read())` inside `w.key(wireKey)` instead of
    `b.array(key, JSONPlan.write(...))`.
- Plan-builder templates (`plan.jte`, `planLeaf.jte`, `planKeyWriter.jte`) update to emit the new lambda
  signatures. `planKeyReader.jte` (deserialize) is untouched.
- `dynamicMap.jte` / `@JSONCatchAll` `any` writing: `JSONBuilder.any` → `JSONWriter.any` (as in the rewrite).
- After all serialize paths use `JSONWriter`, **delete `JSONBuilder`/`JSONArrayBuilder`** and drop them from
  `HelperEmitter.HELPERS` (nothing references them once `JSONPlan` streams).

### Win 2 — Deserialize: String-key switch → byte parser + compile-time int-field dispatch

This re-applies the rewrite's transformation to main's `observerBody.jte`, which is the *same switch shape*
the rewrite already transformed once.

- Drop in the byte-level `JSONParser` (replaces main's char parser): walks `byte[]` directly, SWAR string
  scan, ThreadLocal scratch, in-scan `long` accumulation, lazy `$...` error-path reconstruction. Public API
  and **every error message** unchanged (main's error tests + the rewrite's both assert these).
- `JSONObserver` gains the int-keyed default methods + `field(byte[],off,len)` / `fieldOf(String)` /
  `dispatchUnknown()`; `SkipObserver.dispatchUnknown()` → `false`. (Verbatim from the rewrite.)
- `observerBody.jte`: add the `KEY_*` byte constants, `field()` length-switch, `fieldOf()`, `keyName()`,
  `dispatchUnknown()`, and int-keyed value callbacks — **keeping main's plan arms** in the int form:
  `beginArray(field) → JSONPlanArrayObserver.of(c$plan)`, map `beginObject(field) → new JSONPlanMapObserver<>(c$plan)`,
  dynamic-map → `AnyObjectObserver`, nested `@JSON` → companion. Add `defaultArmInt.jte` (the int analog of
  `defaultArm.jte`, using `keyName(field)` for catch-all/strict messages). The String-key callbacks remain as
  the CAPTURE path for `@JSONCatchAll`/`AnyObjectObserver`/strict.

## Wire-format & behavior invariants (gates, every phase)

- Wire format **byte-identical**: `GenerateFixtures` → `git diff --exit-code benchmarks/src/main/resources/payloads/`
  must be empty. (The rewrite already proved JSONWriter is byte-identical to JSONBuilder output; this extends
  that to the plan-driven nested case.)
- **All of main's tests pass** (`latte test`, currently 342 — includes nested-collections, DeepCollections,
  UnknownKeyPolicy, bean/map/policy codegen, and exact error-message assertions).
- Benchmark `Verify` cross-matrix stays 3× PASS (latte/jackson/gson round-trip parity).
- `ScaffoldingIndentationTest` updated for the regenerated companion shape (as in the rewrite).

## Phased plan (for writing-plans)

- **Phase A — `JSONWriter` helper.** Port `JSONWriter.java` + `JSONWriterTest` from the rewrite; add to
  `HELPERS`. Gate: `latte test --test=JSONWriterTest` + full suite green (builders still present, unused).
- **Phase B — Serialize port.** Companion `write(JSONWriter,…)`; `JSONPlan` streaming rework (`writeInto`,
  `ObjectLeaf`/`ScalarLeaf` lambda retarget); `plan.jte`/`planLeaf.jte`/`planKeyWriter.jte`/`dynamicMap.jte`;
  delete `JSONBuilder`/`JSONArrayBuilder`; `memberCall.jte`/`arrayAppend.jte` to `JSONWriter`. Gate: 342 tests
  + fixtures zero-diff + `Verify` 3× PASS.
- **Phase C — Deserialize byte parser + int dispatch.** Byte `JSONParser`; `JSONObserver` int methods;
  `observerBody.jte` int-dispatch keeping plan arms; `defaultArmInt.jte`; `SkipObserver`. Gate: 342 tests +
  `Verify` 3× PASS.
- **Phase D — Measure & publish.** Full `./run-benchmarks.sh` vs the `main` baseline
  (`benchmarks/results/*final2.json` is the old-branch number for reference; the true baseline is main's
  current generator). Confirm the win profile holds (expect parity with or better than the original rewrite,
  now also covering deep nesting). `update-readme.sh`; record outcome.

## Risks / open decisions

- **`JSONPlan` serialize rework is the riskiest new code** (no analog in the original rewrite). Mitigated by
  the byte-identical fixture gate over the `large`/nested scenarios and main's DeepCollections tests.
- `ScalarLeaf`/`ObjectLeaf` lambda-signature churn ripples through the `plan*.jte` templates; mechanical but
  broad. The plan **deserialize** lambdas (`fromString`/`fromInteger`/…) are untouched — only the write side
  moves.
- Keeping `JSONBuilder` vs. deleting it: deleting is cleaner and matches the rewrite, but only after `JSONPlan`
  streaming and `any`-writing are fully ported. If a serialize path is missed, the fixture/`Verify` gates
  catch it before deletion lands.
- Polymorphic serialize (`polymorphic.jte`) gains a `write(JSONWriter,…)` dispatcher (as in the rewrite).

## Non-goals

- Changing main's nested-collections *semantics* or its plan *deserialize* model.
- Number-format / `Instant` / cap changes. Vectorized UTF-8 decode (the one cell the rewrite lost to Jackson)
  remains future work.
