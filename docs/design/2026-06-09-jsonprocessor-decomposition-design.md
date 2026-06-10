# Decomposing JSONProcessor into validators + writers

**Date:** 2026-06-09
**Status:** Approved (design); pending implementation plan
**Scope:** An **internal, behavior-preserving refactor** of the 826-line `JSONProcessor` into focused collaborators — a validator hierarchy, a writer hierarchy, a class-member discoverer, a helper emitter, and a static facts util. **No public API change** (the processor class + the annotations stay exactly as published); **no generated-output change**. The 265-test suite is the gate.

## Motivation

`JSONProcessor` is one class doing six jobs: round dispatch, helper emission, record validation+generation, class (`@JSONConstructor` + JavaBean) validation+generation, polymorphic validation+generation, and a pile of shared utilities. The two axes that should be separated are **validation** (does this type conform? → diagnostics) and **writing** (turn a validated type into companion source). Splitting along those axes — with each `@JSON`-target *kind* (record / class / sealed interface) as a leaf — yields small, single-purpose classes and makes "where does X live?" obvious.

## Structure

Two independent hierarchies (validators ⟂ writers), plus three shared collaborators. Everything is **stateless except `HelperEmitter`**, so `JSONProcessor` builds each collaborator **once** (in `init()`, the first point an `AbstractProcessor` is handed `processingEnv`) and reuses it for every element and round.

```
JSONProcessor extends AbstractProcessor
  init(env): super.init(env); construct every collaborator → final fields
  process(): helperEmitter.emit(module);                       // emitter self-guards (its own state)
             per element: kind/module guards → validator.validate(type) && writer.write(type, module)

══ Writers ═══════════════════════════════════════════════
AbstractWriter(env)
  • protected writeSource(pkg, name, source, originatingElement)   // Filer / Writer / IOException→printMessage
  • protected internalPackageOf(element)
  ├ CompanionWriter(env, members)        → companion.jte   (record | @JSONConstructor | bean)
  │     build Components (branch on kind via `members`) · collectEnums · discriminator scan · CompanionView · render
  ├ PolymorphicWriter(env)               → polymorphic.jte  (PolymorphicView from permitted subtypes)
  └ HelperEmitter(env)                   → copies HELPERS into <module>.internal; OWNS `helpersEmitted`

══ Validators ════════════════════════════════════════════
AbstractValidator(env)
  • protected error(element, message)
  • protected validateMembers(type, List<Element>)             // shared: record components AND @JSONConstructor params
  • protected validateType(at, name, TypeView) · validatePolicy(at, name, JSONField, TypeView)
  • protected isSupportedComponentType(TypeView) · notJSON(element, TypeView)
  • protected requireDiscriminatorInterface(type)             // the @JSONSubtype-without-@JSONTypeInfo check (see below)
  ├ RecordValidator(env)                 → validateMembers(getRecordComponents())
  ├ ClassValidator(env, members)         → isBean ? validateBean : validateClass
  └ PolymorphicValidator(env)            → validatePolymorphic

══ Shared collaborators (stateless, built once) ══════════
ClassMemberDiscovery(env)   // "how to find a class's members"; used by ClassValidator AND CompanionWriter
  • isBean · jsonConstructors · resolveRead          (the @JSONConstructor side)
  • discoverProperties → List<BeanProperty>          (the bean side: + beanProperty/accessorProperty/
                                                       configElement/superclassChain/capitalize/decapitalize)
  • record BeanProperty(...)

JSONFacts (static util)     // pure reads, no processingEnv — shared without re-coupling the two hierarchies
  • naming(type) · omitNulls(type) · strict(type)    (read @JSON)
  • discriminatorValue(type)                          (read @JSONSubtype, else simple name)
  • discriminatorInterface(type) → TypeElement|null   (the implemented @JSONTypeInfo interface)
  • asTypeElement(TypeMirror) · qualified(Element)
```

`members` above is the single `ClassMemberDiscovery` instance, injected into both `ClassValidator` and `CompanionWriter` (a bean is discovered once to validate and once to write — exactly as today; stateless and cheap, so no caching/threading between the two).

## What moves where (member-by-member)

| Current member (line) | New home |
|---|---|
| `process()` (41) | `JSONProcessor` (dispatch) |
| *(new)* `init()` | `JSONProcessor` (build collaborators once) |
| `HELPERS` (33), `helpersEmitted` (38), `emitHelpers` (111) | `HelperEmitter` (state included) |
| `generateCompanion` (140) | `CompanionWriter.write` |
| `collectEnums` (321) | `CompanionWriter` |
| `generatePolymorphic` (203) | `PolymorphicWriter.write` |
| `internalPackageOf` (411) | `AbstractWriter` |
| *(new)* `writeSource` (extracted from the two `try { Filer… }` blocks) | `AbstractWriter` |
| `error` (405) | `AbstractValidator` |
| `validateMembers` (680) | `AbstractValidator` |
| `validateType` (777), `validatePolicy` (730), `isSupportedComponentType` (425), `notJSON` (444) | `AbstractValidator` |
| `validateClass` (606), `validateBean` (528) | `ClassValidator` |
| `validatePolymorphic` (635) | `PolymorphicValidator` |
| `isBean` (416), `jsonConstructors` (438), `resolveRead` (473), `discoverProperties` (358), `beanProperty` (258), `accessorProperty` (237), `configElement` (340), `superclassChain` (515), `capitalize` (313), `decapitalize` (350), `BeanProperty` (824) | `ClassMemberDiscovery` |
| `discriminatorValueOf` (399), `readNaming` (453), `readOmitNulls` (458), `readStrict` (463), `asTypeElement` (253), `qualified` (449) | `JSONFacts` (static) |

## Behavior-affecting relocations (must stay equivalent)

- **The `@JSONSubtype`-without-`@JSONTypeInfo` check moves from generation to validation.** Today it lives inside `generateCompanion` (lines 180–184) and `return`s before writing. With the split it's a *validation* — `AbstractValidator.requireDiscriminatorInterface(type)`, called by `RecordValidator` and `ClassValidator`. Both the validator (to reject) and `CompanionWriter` (to set the discriminator key) scan the implemented interfaces; that scan is factored into `JSONFacts.discriminatorInterface(type)` so the two agree. Net diagnostic + output is identical; only the *phase* it's caught in changes (validate vs. generate), which is invisible to callers since an invalid type is never written either way.
- **`writeSource` deduplicates the two identical `Filer`/`Writer`/`IOException` blocks** in `generateCompanion` and `generatePolymorphic`. Same `Diagnostic.Kind.ERROR` message on failure.

## Explicitly NOT changed (kept behavior-identical)

- The catch-all / wire-key validation **duplication between `validateMembers` and `validateBean`** is left as-is. It's a known, pre-existing duplication; unifying it is a separate concern and would risk behavior drift in a refactor whose whole point is byte-identical output. (Flagged as a possible follow-up, not part of this.)
- `@JSONConstructor`-class and JavaBean handling stay **together** in `ClassValidator` / `CompanionWriter` (an `isBean` branch), not split into separate kind-classes — they share class recognition, the generation shell, and the policy/type validation; only member *source* differs, and that lives in `ClassMemberDiscovery`.
- No template, runtime-helper, or annotation change. Generated companions are byte-identical.

## Migration order (bottom-up; each step compiles + keeps the suite green)

1. **`JSONFacts`** — extract the pure static reads. Mechanical, zero behavior change.
2. **`ClassMemberDiscovery`** — move the class-member resolution (incl. `BeanProperty`); `JSONProcessor` delegates to an instance.
3. **`HelperEmitter`** — move `emitHelpers` + `HELPERS` + `helpersEmitted`; `process()` calls `emit()`.
4. **Writer hierarchy** — `AbstractWriter` (+ `writeSource`/`internalPackageOf`), `CompanionWriter`, `PolymorphicWriter`.
5. **Validator hierarchy** — `AbstractValidator` (+ shared primitives), `RecordValidator`, `ClassValidator`, `PolymorphicValidator`; relocate the `@JSONSubtype` check.
6. **Thin `JSONProcessor`** — `init()` wiring + the per-element dispatch table; delete the now-empty bodies.

## Testing

The 265-test suite (records, nested, polymorphism, naming/`@JSONField`, `@JSONCatchAll`, both class paths) drives real-`javac` compilation through `ProcessorHarness` end-to-end, so it covers every path being moved. The refactor is correct iff the suite stays green at each step; spot-checking a few generated companions byte-against-`main` per step is the byte-identity guard. No new tests are needed (no new behavior); the value is purely structural.

## Risks

- **`init()` vs. constructor.** `processingEnv` is `null` until `AbstractProcessor.init()`, so collaborators are built there, not in the literal constructor. `init()` must call `super.init(env)` first.
- **Cross-cutting helper homes.** The few helpers used by *both* hierarchies (`naming`/`discriminatorValue`/`asTypeElement`/`qualified`) go in the static `JSONFacts` rather than a shared base, deliberately keeping the validator and writer trees independent. (`internalPackageOf` and `error` are `processingEnv`-bound and single-tree, so they stay on `AbstractWriter` / `AbstractValidator` respectively.)
- **`ClassMemberDiscovery` shared by a validator and a writer** is the one place the two hierarchies meet. It's a stateless query object (no mutation), so sharing one instance is safe.

## Names (provisional)

`AbstractWriter` is fixed (your call). `AbstractValidator`, `CompanionWriter`, `PolymorphicWriter`, `RecordValidator`, `ClassValidator`, `PolymorphicValidator`, `ClassMemberDiscovery`, `HelperEmitter`, `JSONFacts` are proposals — rename any before the plan.
