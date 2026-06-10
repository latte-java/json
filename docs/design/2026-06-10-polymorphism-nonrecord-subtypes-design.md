# Polymorphism for non-record subtypes

**Date:** 2026-06-10
**Status:** Approved (design); pending implementation plan
**Scope:** Allow `@JSONConstructor` classes and JavaBean classes (not just records) to be permitted subtypes of a sealed `@JSONTypeInfo` interface. A single change in `PolymorphicValidator` — the rest of the polymorphism machinery is already kind-agnostic. No template, runtime, annotation, or `module-info` change.

## Problem

`PolymorphicValidator.validate` rejects any permitted subtype that isn't a record (`if (sub.getKind() != ElementKind.RECORD) error("... must be a record")`, line 39). So a sealed `@JSONTypeInfo` interface whose `permits` clause includes a `@JSONConstructor` class or a JavaBean fails to compile — even though both are valid `@JSON` classes on their own. This is the last gap from the non-record-class work: classes can be standalone or nested `@JSON` types, but not discriminated subtypes.

## Goal

A sealed `@JSON @JSONTypeInfo` interface may permit any mix of record / `@JSONConstructor`-class / JavaBean subtypes, each `@JSON @JSONSubtype`, and the hierarchy round-trips through the discriminator:

```java
@JSON @JSONTypeInfo(property = "kind")
public sealed interface Shape permits Circle, Square, Note {}

@JSON @JSONSubtype("circle")
public record Circle(double radius) implements Shape {}

@JSON @JSONSubtype("square")
public final class Square implements Shape {                 // @JSONConstructor class subtype
  private final double side;
  @JSONConstructor public Square(double side) { this.side = side; }
  public double getSide() { return side; }
}

@JSON @JSONSubtype("note")
public final class Note implements Shape {                   // JavaBean subtype
  private String text;
  public String getText() { return text; }
  public void setText(String text) { this.text = text; }
}
// {"kind":"square","side":2.0} ⇄ ShapeJSON dispatches to SquareJSON
```

The existing 265-test suite plus new fixtures are the acceptance gate.

## Why this is (almost) free

Everything downstream of validation is already kind-agnostic:

- **`@JSONSubtype` is `@Target(ElementType.TYPE)`** — applies to classes already.
- **Subtype companion** (`CompanionWriter`): the discriminator scan is `ProcessorFacts.discriminatorInterface(type)` — it finds the implemented `@JSONTypeInfo` interface for a *class* subtype just as for a record, so the class's companion emits the discriminator first on serialize and its observer ignores the discriminator key on deserialize. No change.
- **Dispatcher** (`polymorphic.jte` via `PolymorphicWriter`): built from `iface.getPermittedSubclasses()` (kind-agnostic). `observerFor` returns `new <Subtype>JSON()`; `toJSON` is a pattern `switch (value) { case <SubtypeFqn> v -> <SubtypeJSON>.toJSON(v); }` over the sealed hierarchy — `case Square v -> SquareJSON.toJSON(v)` works whether `Square` is a record or a class. The switch stays exhaustive (sealed permits list). No change.
- **Each class subtype is validated independently** by `ClassValidator` (its `@JSONConstructor`/bean rules + `requireDiscriminatorInterface`, which passes since it *has* the interface). No change.
- **Sealing is enforced by `javac`** — a sealed interface's permitted class must be `final`/`sealed`/`non-sealed`; the processor never sees an ill-sealed hierarchy.

## Design

`PolymorphicValidator` is the only file that changes; it gains a `ClassMemberDiscovery` dependency (injected in `JSONProcessor.init()`).

### 1. Accept class subtypes

Replace the record-only kind check with: a permitted subtype must be a **record or a class** (reject `enum`/`@interface`/anything else):

```java
if (sub.getKind() != ElementKind.RECORD && sub.getKind() != ElementKind.CLASS) {
  error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
      + iface.getQualifiedName() + "] must be a record or class");
  ok = false;
  continue;
}
```

(The subtype's own `@JSON` validity — a class needs `@JSONConstructor` or a valid bean shape — is checked by `ClassValidator` when that subtype is processed; `PolymorphicValidator` only gates the parent's view of the hierarchy.)

### 2. Generalize the discriminator-key collision check

The existing check (line 55) iterates `sub.getRecordComponents()` to ensure no member's wire key equals the discriminator `property` (which would emit two values under one JSON key). For a class subtype `getRecordComponents()` is empty, so it must enumerate the class's members instead:

- **record** → `getRecordComponents()`, `Component.wireKey(component, naming)` (unchanged).
- **`@JSONConstructor` class** → the constructor's parameters, `Component.wireKey(param, naming)`.
- **JavaBean** → the discovered properties; each property's wire key is `@JSONField(name)` (from its config element) if set, else `naming` applied to the property name.

`ClassMemberDiscovery` (injected) supplies `isBean`/`jsonConstructors`/`discoverProperties`. The same "wire key collides with the discriminator" diagnostic is reported on the interface, naming the offending member.

### 3. Files touched

- `src/main/java/org/lattejava/json/processor/PolymorphicValidator.java` — the two changes above; constructor gains `ClassMemberDiscovery`.
- `src/main/java/org/lattejava/json/JSONProcessor.java` — `init()` passes `members` to `new PolymorphicValidator(processingEnv, members)`.

### 4. Conventions

New code follows the project rules: `[brackets]` in error messages, uppercase acronyms, module imports, alphabetized members.

## Non-goals

- **Class-rooted hierarchies.** The discriminated parent stays a **sealed interface** carrying `@JSONTypeInfo`. A sealed *abstract class* as the polymorphic root (with the discriminator + an instance-field contract) is a distinct, larger feature and is out of scope. *(Flagged — if you meant this rather than class subtypes, say so on review.)*
- No change to the discriminator wire format, the `@JSONSubtype` value rules, or the existing record-subtype behavior.

## Testing — acceptance gate

A new `polysub` fixture: a sealed `@JSON @JSONTypeInfo` interface permitting a record subtype, a `@JSONConstructor`-class subtype, and a JavaBean subtype (each `@JSONSubtype`, each `final`). Through the real-`javac` `ProcessorHarness`:

- **Round-trip + dispatch:** parsing `{"kind":"square",…}` / `{"kind":"note",…}` via the interface companion (`ShapeJSON.fromJSON`) yields the right class instance; `ShapeJSON.toJSON(shape)` re-emits the discriminator-first JSON for a class subtype (byte-exact).
- **Mixed hierarchy:** the record subtype still round-trips alongside the class subtypes through the same dispatcher.
- **Class subtype nested elsewhere:** the dispatcher used as a record field / list element resolves class subtypes (proving the companion reference works).
- **Rejections:** a class subtype with a member whose wire key collides with the discriminator `property` (the generalized collision check fires, on a `@JSONConstructor` param and on a bean property); a permitted subtype that is an `enum` (must be a record or class); a permitted class subtype lacking `@JSON` (existing check) and one that is an invalid `@JSON` class (rejected by `ClassValidator` independently).

All existing 265 tests stay green — record hierarchies are unchanged (the kind check still admits records; the collision check's record branch is the original code).

## Risks

- **Discriminator-key collision on bean properties** is the one genuinely new validation path; the bean wire-key computation must match `validateBean`/`CompanionWriter` exactly (`@JSONField(name)` else `naming(propertyName)`), or it would false-positive/miss. Covered by a dedicated rejection fixture.
- **`PolymorphicValidator` gaining a collaborator** is the only wiring change; it stays a pure validator (no generation), consistent with the decomposition.

## Alternatives considered

- **Move the discriminator-collision check into the subtype validators** (`RecordValidator`/`ClassValidator`), since they already enumerate wire keys. Rejected for this cycle: it relocates an existing diagnostic (reported on the interface today) to the subtype, changing the error's attachment point and touching three validators instead of one. Keeping it in `PolymorphicValidator` is the minimal, behavior-consistent change.
