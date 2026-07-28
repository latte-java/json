# `@JSONRaw` — capturing an object's verbatim JSON

Design for a new member annotation that hands a `@JSON` type the raw JSON text of the object it was
deserialized from.

## Motivation

Some consumers need the exact bytes that produced an object, not just the bound fields:

- **JWT verification.** A signature covers the raw payload/header text. Re-serializing a bound object
  produces different bytes (key order, whitespace, number formatting), so the signature no longer
  checks out. The verifier needs the original slice.
- **Passthrough and audit.** Forwarding or logging a request body exactly as received, while still
  reading a few fields out of it for routing or authorization.
- **Debugging.** Attaching the originating JSON to an object that failed downstream validation.

Today the only way to get this is to parse twice — once into the type, once into a `String` — which
means holding the input alongside the object and re-deriving which slice belongs to which nested
value.

## Summary

`@JSONRaw` marks a single `String` member of a `@JSON` type. During deserialization that member
receives the verbatim JSON text of the object that produced the instance — from its opening `{`
through its matching `}`, inclusive, with interior whitespace and key order exactly as they appeared
in the input.

```java
@JSON
public record Token(String sub, long exp, @JSONRaw String raw) {
}
```

```java
Token t = TokenJSON.fromJSON("{ \"sub\" : \"bob\", \"exp\": 123 }");

t.sub();  // "bob"
t.exp();  // 123
t.raw();  // "{ \"sub\" : \"bob\", \"exp\": 123 }"   <- verbatim, whitespace preserved
```

The member is **deserialize-only**. It owns no wire key, is never matched against an incoming key,
and is never written by `toJSON`:

```java
TokenJSON.toJSON(t);  // {"sub":"bob","exp":123}   — raw contributes nothing
```

Capture applies at **every nesting level**, not just the top-level parsed type. A nested `@JSON`
record, a `@JSON` record inside a `List`, and a `@JSONSubtype` of a sealed hierarchy each receive
their own slice, because each is deserialized by its own companion observer.

## Semantics

### What the slice contains

The slice runs from the byte offset of the object's `{` through the byte offset just past its
matching `}`. Consequences that follow from that definition:

| Input                                                  | Captured value                                                                             |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `{"a":1}`                                              | `{"a":1}`                                                                                  |
| `{ "a" : 1 }`                                          | `{ "a" : 1 }` — interior whitespace preserved                                              |
| `  {"a":1}  ` (top level)                              | `{"a":1}` — surrounding whitespace excluded                                                |
| `{"b":2,"a":1}`                                        | `{"b":2,"a":1}` — original key order                                                       |
| `{}`                                                   | `{}`                                                                                       |
| `{"x":{"y":1}}`, raw on the nested type                | `{"y":1}`                                                                                  |
| `{"kind":"dog","name":"rex"}`, raw on a `@JSONSubtype` | `{"kind":"dog","name":"rex"}` — the discriminator is part of the object, so it is included |

Because the slice is taken from the input bytes, it is byte-exact for anything that round-trips
through UTF-8 — which is the property signature verification depends on. Input arriving through
`fromJSON(String)` is UTF-8 encoded once by the parser before the slice is taken, so the captured
`String` equals the corresponding substring of the original input.

The captured value is **always non-null after a successful parse**, including for `{}`.

### No wire key

A `@JSONRaw` member is excluded from the companion's key table entirely: it gets no `KEY_` byte
constant, no field ordinal, and no `case` arm in any observer callback. A naming strategy does not
apply to it.

That means a JSON key that happens to match the member's Java name is treated as an **unknown key**,
handled by the type's existing unknown-key policy:

```java
@JSON
public record Token(String sub, @JSONRaw String raw) {
}
```

| Input                     | `strict = false` (default)                         | `strict = true`             | with a `@JSONCatchAll`                                            |
|---------------------------|----------------------------------------------------|-----------------------------|-------------------------------------------------------------------|
| `{"sub":"bob","raw":"x"}` | `"raw"` skipped; `raw` still gets the whole object | throws on unknown key `raw` | `"raw"` lands in the catch-all; `raw` still gets the whole object |

This is the correct behavior — `@JSONRaw` describes where a value comes from, not a key to bind —
but it is worth calling out because it is the one place the annotation is not fully invisible.

### Where it may appear

Wherever `@JSONCatchAll` may appear, on the same member kinds:

| Type shape                     | Placement                                                                                                                    |
|--------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `record`                       | a record component                                                                                                           |
| class with `@JSONConstructor`  | a constructor parameter                                                                                                      |
| JavaBean class                 | a public field, or a field paired with a getter and/or setter; the annotation may go on the field, the getter, or the setter |
| `@JSONSubtype` record or class | same as its underlying shape                                                                                                 |

A JavaBean `@JSONRaw` property needs a **writer** (a setter or a public field); the getter is
optional and, if present, is simply never called for output — the member is not serialized. The
annotation itself may sit on the field, the getter, or the setter, independent of which of those
provides the writer. This mirrors how a record component always has an accessor that `toJSON`
ignores.

`@JSONRaw` on the `@JSONTypeInfo` interface itself is meaningless (the interface is never
instantiated) and the annotation's `@Target` does not permit it.

A `transient` field carrying `@JSONRaw` is silently dropped: `ClassMemberDiscovery.discoverProperties`
collects `transientNames` before consulting the annotation predicate, and `names.removeAll(transientNames)`
removes the property before validation ever sees it, so no diagnostic is produced and the member stays
`null` forever. `@JSONCatchAll` and `@JSONField` on a `transient` field are dropped the same way, so this
is an existing house rule ("transient wins, silently") across all three annotations, not a `@JSONRaw`-specific
gap. This is a recorded, deliberate decision, not a bug — making `@JSONRaw` the lone exception would be
inconsistent, and changing it for all three is out of scope for this feature.

## Compile-time validation

All rejections are `Diagnostic.Kind.ERROR` reported on the offending element, following the existing
`@JSONCatchAll` validation shape in `AbstractValidator.validateMembers` and
`ClassValidator.validateBean`.

| Condition                     | Message                                                                                             |
|-------------------------------|-----------------------------------------------------------------------------------------------------|
| Member is not `String`        | `@JSONRaw member [payload] must be of type String but found [int]`                                  |
| More than one per type        | `type [demo.Token] declares [2] @JSONRaw members; at most one is allowed`                           |
| Combined with `@JSONField`    | `@JSONRaw member [raw] cannot also be annotated @JSONField`                                         |
| Combined with `@JSONCatchAll` | `@JSONRaw member [raw] cannot also be annotated @JSONCatchAll`                                      |
| Bean property with no writer  | `@JSONRaw member [raw] on [demo.Token] has no usable writer; add a setter or make the field public` |

`@JSONField` is rejected rather than partially honored because every one of its elements is
meaningless here: `name` and the naming strategy have no wire key to affect, `format`/`instant`
require a temporal type, `readOnly`/`writeOnly` describe a direction the member does not have, and
`ignore` contradicts the annotation.

A type whose *only* member is `@JSONRaw` is legal in all three shapes. It round-trips asymmetrically
by design — it captures whatever it is given and serializes to `{}`. No new check is needed:
`ClassValidator.validateBean`'s existing "has no serializable properties" rejection still passes,
because the raw property is discovered like any other, and the generated observer's `switch`
statements each carry a `default ->` arm, so an empty key table compiles.

## Implementation

### 1. The annotation

New `src/main/java/org/lattejava/json/JSONRaw.java`, alongside `JSONCatchAll`:

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface JSONRaw {
}
```

`SOURCE` retention and the existing `exports org.lattejava.json` need no `module-info` change.

### 2. Parser hook

`JSONObserver` gains one default method:

```java
/**
 * Delivers the verbatim byte span of the JSON object just parsed — {@code src[start]} is its
 * {@code '{'} and {@code src[end - 1]} its matching {@code '}'}. Called once per object, after the
 * body and before {@link #finish()}. The default ignores it; only companions with a
 * {@code @JSONRaw} member override and decode the slice.
 */
default void raw(byte[] src, int start, int end) {
}
```

`JSONParser.parseObjectBody` records the offset of the opening brace and calls the hook at both of
its return points:

```java
private <T> void parseObjectBody(JSONObserver<T> target, int depth, byte[] skipKey) {
  if (depth > maxNestingDepth) {
    throw error("Maximum nesting depth [" + maxNestingDepth + "] exceeded");
  }
  int objectStart = pos;                       // at the '{'
  expect('{');
  skipWhitespace();
  if (pos < len && src[pos] == '}') {
    pos++;
    target.raw(src, objectStart, pos);         // empty object
    return;
  }
  ...
    if (nc == '}') { pos++; target.raw(src, objectStart, pos); return; }
  ...
}
```

Every object body in the parser routes through `parseObjectBody` — top level (`parse`), nested
values (`parseValueTyped`/`parseValueLegacy`), array elements (`parseArrayValue`), and polymorphic
subtypes (`parsePolymorphicObject`) — so all four paths are covered by this one change.

Ordering is already correct: `parseObjectBody` returns before every caller invokes `finish()`, so
the raw value is in place when the record constructor or bean setter run.

`JSONPolymorphicObserver` is a sibling of `JSONObserver` under `JSONObjectHandler`, not a subtype, so
it is unaffected. `AnyObjectObserver`, `JSONPlanMapObserver`, and `SkipObserver` inherit the no-op
default.

**Cost.** One empty interface call per parsed object for every observer, including those that do not
opt in — the same order of overhead as the existing `dispatchUnknown()` call, and cheaper than a
flag-plus-call pair. No allocation is added on the non-opted-in path; the parser hands over offsets
and only the generated companion decodes. This is the reason for the byte-span signature rather than
`raw(String)`, which would force the parser to allocate for every object regardless of interest.

### 3. Decoding helper

`Conversions` gains a static method (it is already in `HelperEmitter.HELPERS` and already imported
by every companion, so nothing else needs wiring):

```java
/** Decodes the verbatim JSON slice {@code [start, end)} for a {@code @JSONRaw} member. */
public static String rawString(byte[] src, int start, int end) {
  for (int i = start; i < end; i++) {
    if (src[i] < 0) {
      return new String(src, start, end - start, StandardCharsets.UTF_8);
    }
  }
  return new String(src, start, end - start, StandardCharsets.ISO_8859_1);
}
```

The ASCII fast path mirrors the parser's private `stringFrom`.

### 4. Processor changes

**`Component`** — add a `raw` flag read from the element (all three constructors, alongside the
existing `catchAll` read) and an `isRaw()` accessor.

**`CompanionView`** — add:

```java
/** The Java name of the {@code @JSONRaw} component, or {@code ""} when the type has none. */
public String raw() { ... }
```

and extend `typedComponents()` to filter raw members as well as the catch-all:

```java
public List<Component> typedComponents() {
  return components.stream().filter(c -> !c.isCatchAll() && !c.isRaw()).toList();
}
```

That single filter is what makes the member invisible to the wire: `typedComponents()` drives the
`KEY_` constants, the `field`/`fieldOf` ordinals, every observer `case` arm, and the `write` method's
serialize loop. The member stays in `components()`, which is what `finish()` iterates — so a record
still gets it as a positional constructor argument, and a bean still gets its setter call.

**`ClassMemberDiscovery`** — `@JSONRaw` is treated like `@JSONCatchAll` in the bean field-selection
predicate in `discoverProperties`, which is what keeps a raw property from being dropped as
unserializable. Resolving *which* element carries the annotation needed to go further than that:
`configElement` returns only the first candidate (field, getter, `is`-getter, setter, in that order)
bearing *any* of `@JSONField`/`@JSONCatchAll`/`@JSONRaw`, so it structurally cannot see a `@JSONRaw`
that sits on a different element than a competing `@JSONField`/`@JSONCatchAll` — which silently
dropped the annotation. Commit `9d7e08c` fixed this with a `firstAnnotated` helper that scans every
candidate for one specific annotation, and widened `BeanProperty` to carry three
independently-resolved elements (`raw`, `catchAll`, `field`) alongside `config`.
`ClassValidator.validateBean` uses the independent elements to catch the conflict regardless of which
physical element carries which annotation; `config` (and therefore `Component`, built only after
validation passes) still resolves field-first as before.

**`AbstractValidator.validateMembers`** and **`ClassValidator.validateBean`** — add the raw branch
(type check, `@JSONField`/`@JSONCatchAll` conflict, writer check for beans) and a per-type count,
mirroring the catch-all branch immediately above it.

**`ClassValidator.validateClass`** — exempt `@JSONRaw` parameters from the "no usable reader"
check, exactly as `@JSONCatchAll` parameters are exempt today.

### 5. Templates

`observerBody.jte` gains one guarded block:

```
@if(!view.raw().isEmpty())
  @Override public void raw(byte[] src, int start, int end) {
    this.${view.raw()} = Conversions.rawString(src, start, end);
  }
@endif
```

`companion.jte` needs no change — the raw member's field declaration already falls out of the
`view.components()` loop, and `Conversions` is imported unconditionally.

## Testing

Following the existing fixture-plus-codegen-test structure under
`src/test/resources/fixtures/` and `src/test/java/org/lattejava/json/tests/processor/`.

**New fixture set `fixtures/raw/`** — one type per shape:

| Fixture                                                                   | Covers                                                          |
|---------------------------------------------------------------------------|-----------------------------------------------------------------|
| `Token` (record)                                                          | the base case; whitespace and key-order fidelity                |
| `Envelope` (record with a nested `@JSON` record that also has `@JSONRaw`) | per-level capture                                               |
| `Batch` (record with `List<Token>`)                                       | capture inside an array element                                 |
| `Config` (class with `@JSONConstructor`)                                  | constructor-parameter placement                                 |
| `Session` (JavaBean, setter + private field)                              | bean placement                                                  |
| `Loose` (record with `@JSONRaw` + `@JSONCatchAll`)                        | coexistence; a `"raw"`-named input key landing in the catch-all |
| `Strict` (record with `strict = true`)                                    | a `"raw"`-named input key throwing                              |
| `Pet`/`Dog` (sealed `@JSONTypeInfo` + `@JSONSubtype`)                     | discriminator included in the slice                             |
| `OnlyRaw` (record whose single component is `@JSONRaw`)                   | degenerate case                                                 |

**New `RawCodegenTest`** — compiles `fixtures/raw`, then reflectively round-trips each type
asserting: the captured text is exactly the input slice; `{}` captures `{}`; `toJSON` output omits
the raw member; nested and array-element types capture their own slices; the polymorphic subtype's
slice includes the discriminator.

**New `RawRejectionTest`** with a fixture per diagnostic, named to match the existing convention:

- `badraw_type` — `@JSONRaw int`
- `badraw_two` — two `@JSONRaw` members
- `badraw_field` — `@JSONRaw` + `@JSONField`
- `badraw_catchall` — `@JSONRaw` + `@JSONCatchAll`
- `badraw_nowriter` — bean property with a getter and no setter

**New `JSONParserRawTest`** in the runtime test set — a hand-written `JSONObserver` that records
every `(start, end)` span it receives, asserting the parser's hook directly for: a flat object, an
empty object, nested objects, array elements, and an object containing escaped and multi-byte string
values (so the span is validated in bytes, not chars). The `Recorder` fixture is a `JSONObserver`, not
a `JSONPolymorphicObserver`, so it cannot exercise `parsePolymorphic`; polymorphic-subtype capture is
covered instead by `RawCodegenTest.polymorphicSubtypeCaptureIncludesTheDiscriminator`, which
round-trips a real `@JSONSubtype` companion.

**Regression check** — `HelperEmissionTest` covers the emitted helper set; `Conversions` gaining a
method needs no change there, but the test is re-run to confirm.

**Benchmarks** — a scoped A/B was run rather than the full suite: a worktree at `43aae78` (pre-parser-hook)
against current code, `--libraries latte --modes deserialize --scenarios jwt,large,deep`. No measurable
regression: `large` (~1001 objects/call, the most sensitive scenario and the tightest error band) went
7396.1 → 7393.8 ops/s against a ±34.5 error band; `deep` went 1,597,619 → 1,596,042 ops/s against a
±3,038–8,773 band; `jwt` went 5,155,568 → 5,220,018 ops/s against a ±199,451 band. Allocation bytes/op
were effectively identical before and after. If a future change to the hook regresses a metric beyond
run-to-run variance, the fallback is to gate it behind a `capturesRaw()` predicate read once per object
body, at the cost of a second interface call on the opted-in path.

## Out of scope

- **Per-field raw capture** — binding a `@JSONRaw` member to a named wire key so it receives that
  one value's raw text (e.g. deferring an inner document). Deliberately excluded; only whole-object
  capture is in this design. It could be layered on later by giving the annotation a wire key and
  adding a value-span hook, without changing anything specified here.
- **Raw passthrough on serialization** — emitting the captured text verbatim instead of writing the
  bound members. Rejected: it silently discards mutations made to the object after parsing.
- **`byte[]` raw members** — avoiding the `String` allocation. `String` only, per the annotation's
  contract.
- **Raw capture for top-level arrays** — the parser rejects non-object top-level values by design.
