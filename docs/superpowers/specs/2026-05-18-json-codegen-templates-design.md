# JSONProcessor codegen: composable text-block templates

**Date:** 2026-05-18
**Status:** Approved (design); pending implementation plan
**Scope:** Internal refactor of `org.lattejava.json.JSONProcessor` code generation. No public API change, no `module-info` change, no new dependency.

## Problem

`JSONProcessor` generates each `<Type>JSON` companion class with ~340 lines of imperative `StringBuilder.append(...)` plumbing (see `JSONProcessor.java` lines ~110-420). The shape of the generated source is not visible in the source that produces it: a single emitted line is spread across 4-6 `.append()` calls interleaved with control flow. This makes the generator hard to read, hard to modify safely, and easy to emit malformed Java from. Test coverage is growing (recent commits are all processor-suite tests), so the cost of this friction is increasing.

## Goal

Replace the string plumbing with a small composable-template model so the generated source shape is readable directly in the generator, while keeping the irreducible type-dispatch logic in Java. Behavior-preserving: the existing processor TestNG suite is the acceptance gate.

## Non-goals

- No external template engine or dependency (project ethos: zero runtime deps, tight control).
- No externalized template resource files — templates are inline Java text blocks ("simple text blocks").
- No byte-identical output guarantee — whitespace may shift.
- No change to generated runtime behavior, public API, or the module descriptor.

## Design

### 1. The `Template` primitive

Package-private final class in `org.lattejava.json` (build-time only; not exported via `module-info`).

- `Template.of(String body)` — wraps a text block.
- `String render(Map<String,String> bindings)` — substitutes `{{name}}` with its bound value using **literal** `String.replace` (not regex, not `String.format`). Generated Java is full of `%`, `$`, `{`, `}`, `\`; positional/regex substitution is unsafe and unreadable here. The "printf-style" idea is intentionally rejected for this reason.
- Any `{{name}}` left unbound after substitution is a hard error (throws) — missing holes fail loud at processor-run time and are caught by the harness rather than producing silently-wrong source.
- Static `join(Collection<T> items, Function<T,String> render, String separator)` helper for the list-rendering (Mustache "section") case.
- The `Template` primitive owns indentation handling (section 3); callers never compute indentation.

Approx. 40-60 lines. Has its own unit tests (substitution, unbound-hole error, join, indentation, empty-render line drop).

### 2. The template tree

Templates are `static final String` text blocks grouped in a package-private `Templates` holder class (keeps `JSONProcessor` from ballooning; all generated shapes readable in one place).

- `COMPANION` — outer class. Holes: `{{header}}`, `{{package}}`, `{{imports}}`, `{{simpleName}}`, `{{companion}}`, `{{fields}}`, `{{omitNulls}}`, `{{builderCalls}}`, `{{collectionCodegen}}`, `{{observerMethods}}`.
- `OBSERVER_METHOD` — one render per scalar callback (`string`/`integer`/`bigInteger`/`decimal`/`bool`/`nullValue`). Holes: `{{signature}}`, `{{cases}}`, `{{defaultArm}}`.
- `ARRAY_OBSERVER` / `MAP_OBSERVER` — inner-class templates. Holes: `{{accumulator}}`, `{{stubs}}`, plus the declared-type / element-type bindings.
- Leaf fragments: `CASE_ARM` and the accumulator-line templates used by `appendElementAccumulator` / `appendMapValueAccumulator` equivalents.

`JSONProcessor` retains only the logic: iterating `record.getRecordComponents()`, and the type-dispatch predicates (`integerNarrowing`, `bigIntegerNarrowing`, `decimalNarrowing`, `stringConversion`, `isEnum`, `collectionKind`, …) that decide which arm applies to which component. These stay in Java by design — they are logic, not text. Example: the current `appendObserverMethods` becomes — for each scalar callback, filter `comps` by applicability, `Template.join` the `CASE_ARM`s, render `OBSERVER_METHOD`.

### 3. Indentation

A `{{hole}}` carrying multi-line or list content **must be alone on its line** — only leading/trailing whitespace and the `{{name}}`, no other characters. This makes indent detection unambiguous and is the contract callers must honor when authoring templates.

Algorithm (owned by `Template`, modeled on Mustache standalone-partial indentation):

1. Capture the indent as the **literal whitespace string** between the preceding `\n` and `{{` (store the string, not a count — handles tabs, simpler).
2. Re-indent the rendered fragment by prefixing the captured indent after every newline (`replace("\n", "\n" + indent)`-style). The fragment's **first line gets no added indent** — it inherits the template literal's own prefix already sitting before the hole.
3. **Skip empty lines:** the indent is prefixed only to non-empty lines, so intentional blank lines inside a fragment do not become whitespace-only (trailing-whitespace) lines. Fragments carry **no trailing newline**.
4. **Empty fragment drops the line:** if the rendered fragment is empty (e.g. a record with no fields, an empty collection set), the entire template line including its trailing newline is removed, rather than leaving a stray indented blank line.

Consequence: sub-templates are authored at column 0 and are depth-independent. Rendering is inner-first, so a parent always re-indents an already-flat fragment; compounding indentation across nesting levels is correct and desired (an inner observer class injected at indent 2 has its whole body shifted +2). No post-formatter is needed.

### 4. Migration & validation

- **Acceptance gate:** the existing processor TestNG suite — the test classes under `src/test/java/org/lattejava/json/tests/processor/` (`SimpleRecordCodegenTest`, `MapCodegenTest`, `ListCodegenTest`, `SetCodegenTest`, `EnumCodegenTest`, `EnumCollectionCodegenTest`, `BigNumberCodegenTest`, `TimeCodegenTest`, `UUIDCodegenTest`, `CollectionRejectionTest`, `UnknownKeyPolicyTest`, `ProcessorErrorsTest`, `HelperEmissionTest`, `HelperTemplateDriftTest`), each driven through `ProcessorHarness` (real `javac` with the processor attached, then class load + JSON round-trip). Nothing ships unless every one of these is green (run the full suite with `latte test`, or individually via `latte test --test=<ClassName>`). Output is behavior-preserving, **not** byte-identical; whitespace may shift.
- **No surface change:** internal refactor of `JSONProcessor` plus new package-private `Template` and `Templates` classes. No public API, no `module-info`, no dependency change.
- **Conventions:** new files follow project rules — SPDX copyright header first, uppercase acronyms, `[brackets]` around runtime values in any error messages, alphabetization / in-class member order.
- **Incremental order**, suite green at each step:
  1. Introduce `Template` (+ its unit tests).
  2. Migrate the fixed scaffolding (header, imports, fields, static `toJSON`/`toJSONBytes`/`builder`/`fromJSON`).
  3. Migrate the scalar observer methods.
  4. Migrate the array/map inner-observer classes.
  5. Delete the now-dead `append*` helper methods.

## Risks

- **Indentation edge cases** (empty render, blank interior lines, trailing whitespace) — mitigated by the explicit rules in section 3, exercised by `Template` unit tests.
- **Silent output drift** — mitigated by the compile-and-round-trip suite as the acceptance gate and the incremental, suite-green-at-each-step migration.
- **`JSONProcessor` logic vs. template boundary blurring** — the rule "type-dispatch predicates stay in Java, text shape lives in templates" is the boundary; reviewed per migration step.
