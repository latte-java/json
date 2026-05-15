# Serialization

The goal of this project is to add a pure Java method of serializing and deserializing JSON into Java classes and records. The goal is to be able to use this library in a Java project at compile time only via an annotation processor that generates Java code for the JSON handling. At runtime, the project will contain all the code necessary without depending on this library or any other library.

## Annotating a class or record

A project will identify any classes or records it wants to be capable of serializing and deserializing to and from JSON. The annotation for this is `org.lattejava.json.JSON` and already exists.

Here's an example:

```java
package org.lattejava.project.model;

import module org.lattejava.json;

@JSON
public record User(String name, int age, String email) {
}
```

## Annotation processor

This library provides an annotation processor that generates the serialization and deserialization code at compile time. The processor runs inside javac (discovered via `META-INF/services/javax.annotation.processing.Processor`) and emits everything the consumer needs to serialize and deserialize JSON without any runtime dependency on this library.

Standard `javax.annotation.processing` rules apply: the processor can only **produce new classes**, not modify existing ones. For each `@JSON`-annotated type the processor generates one sibling **companion class** named after the type plus the `JSON` suffix (e.g., `User` → `UserJSON`). The companion is placed in an `internal` sub-package of the annotated type's own package — e.g., `org.lattejava.project.model.User` produces `org.lattejava.project.model.internal.UserJSON`.

The companion is the type's full JSON entry point:

```java
package org.lattejava.project.model.internal;

import org.lattejava.project.model.User;
import org.lattejava.project.internal.JSONObserver;
import org.lattejava.project.internal.JSONParser;
import org.lattejava.project.internal.JSONBuilder;

public final class UserJSON implements JSONObserver<User> {
  // per-field instance state and JSONObserver callbacks ...

  public static User   fromJSON(String json)     { /* ... */ }
  public static String toJSON(User user)         { /* ... */ }
  public static byte[] toJSONBytes(User user)    { /* ... */ }
}
```

A full, fleshed-out example with nested records is in the "Deserialization" section below. Serialization output is detailed in the "Serialization" section.

The companion code calls only into the shared helper classes that live in `<moduleName>.internal` (see the "JSON processing code" section). It does **not** reference `org.lattejava.json` at runtime — the entire infrastructure is local to the consumer's module.

## JSON processing code

To avoid depending on any library at runtime, the code to parse JSON and produce JSON lives inside the consumer's own module. The annotation processor emits a copy of the shared infrastructure (interfaces, parser, builder, stateless helper observers) into a single well-known package per module.

The location is `<moduleName>.internal` — the consumer's module name plus an `.internal` sub-package, matching the standard Java convention for "implementation details, not part of the public surface." If the project lacks a `module-info`, the annotation processor fails the compilation with a clear error.

For a module named `org.lattejava.project`, the helper code is placed in `org.lattejava.project.internal` and contains:

- `JSONObserver<T>`, `JSONArrayObserver<T>`, `JSONPolymorphicObserver<T>` — observer interfaces.
- `JSONParser` — the parser.
- `JSONBuilder` — the writer.
- `JSONProcessingException` — runtime exception thrown by parsing and serialization.
- `SkipObserver`, `SkipArrayObserver`, `AnyObjectObserver`, `AnyArrayObserver` — stateless helper observers for unknowns and catch-alls.
- `Numbers` — range-checked narrowing helpers (`Numbers.toByteExact(long)`, etc.).

Per-type companions live in a separate `<typePackage>.internal` sub-package (see "Annotation processor" above). Both packages share the simple name `internal` but have distinct fully-qualified names (`<typePackage>.internal` vs `<moduleName>.internal`), so there is no actual name collision — only a visual similarity. Both are un-exported by default.

### How the helper source is produced

Helpers are maintained as **ordinary Java source** in this library's source tree — they are compiled and tested as part of this library's own build. That guarantees the templates are valid Java, refactorable in any IDE, and exercisable through a normal test suite before they ever ship.

For distribution, the processor JAR's build step copies those `.java` files into the JAR as text resources (under `META-INF/json-helpers/`). At codegen time, the annotation processor:

1. Reads each helper resource as a string.
2. Rewrites the leading `package` statement to point at `<moduleName>.internal`.
3. Emits the file via `Filer.createSourceFile`.

That is the **only** transformation applied — everything else in the helper source is copied verbatim. No string templating, no AST rewriting, no extra dependency (no JavaPoet). Helpers stay first-class Java; the only thing that varies per consumer is the package line.

### Emission strategy per processor invocation

Helpers are emitted once per `process()` invocation. The processor holds a per-instance flag (`boolean helpersEmitted`) and writes the helper set on the first round that encounters any `@JSON` type. Subsequent rounds in the same invocation skip emission.

There is no project-wide "search the codebase for an existing copy" step. The destination package is well-known (`<moduleName>.internal`), the processor knows exactly what it has emitted, and `Filer` reports cleanly on duplicate writes within a round. Incremental builds handled by the build tool are unaffected — generated sources from a prior compilation are visible but don't conflict with re-emission on a clean build.

### Cross-module references are forbidden in v1

Because each module owns its own copy of the helper interfaces, those interfaces are **distinct types** across module boundaries. Module A's `org.acme.usersA.json.JSONObserver<T>` and module B's `org.acme.accountsB.json.JSONObserver<T>` are unrelated to the Java type system — a `B.JSONObserver` reference cannot hold an `A.JSONObserver` instance. Generated companions can't be composed across modules without either a shared runtime library or boundary marshalling.

For v1, this is resolved by a compile-time restriction:

> If an `@JSON` record component or class field's type is also `@JSON`-annotated and **lives in a different module** from the type being processed, the annotation processor reports a compile-time error and refuses to generate the companion.

The processor detects this using the `javax.lang.model` API:

```java
ModuleElement myModule       = elements.getModuleOf(typeBeingProcessed);
ModuleElement otherModule    = elements.getModuleOf(referencedTypeElement);
if (!myModule.equals(otherModule)) {
  messager.printMessage(ERROR,
      "Cross-module @JSON references are not supported. "
    + "Field [" + field + "] references @JSON type ["
    + referencedTypeElement + "] in module [" + otherModule + "].",
    field);
}
```

What still works across modules: **calling the static entry points.** A consumer in module B may call `org.acme.usersA.internal.UserJSON.fromJSON(json)` or `.toJSON(user)` — the static methods are entirely self-contained inside module A and don't expose the interface types across the boundary. They return / accept the user-facing types (`User`), not observers. Module A only needs to export `org.acme.usersA.internal` (or grant access through `opens` / `exports … to …`) for the companion class itself to be reachable.

What doesn't work: **nesting an @JSON type from another module inside an @JSON type in this module.** That requires the observer-typed bridge that breaks under the per-module-copy model.

A future release may relax this restriction by shipping a small runtime library (single source of truth for the interfaces) or by adding boundary-marshalling codegen. The v1 restriction keeps the spec tight while preserving the "no runtime dependency" goal.

## Nested objects

Nested objects can be included in the serialization and deserialization processes. The code generated for the top-level class or record (those with the `@JSON` annotation) will include handling for all nested objects, subject to the cross-module restriction above.

## No reflection

This library will not use reflection to access the fields of the class or record. Everything will be direct access as needed to both serialization and deserialization.

## Serialization

The annotation processor generates two static entry points on each `*JSON` companion class:

```java
public static String toJSON(T value);     // String form, for general use
public static byte[] toJSONBytes(T value); // UTF-8 byte form, for HTTP bodies, JWT signing, file writes
```

Both call a shared internal `JSONBuilder` that writes directly to a `ByteArrayOutputStream`; the `String` form is `new String(bytes, UTF_8)` on the result. Byte-oriented consumers don't pay the cost of an extra UTF-8 round-trip.

### The generated `toJSON`

```java
package org.lattejava.project.model.internal;

import org.lattejava.project.model.User;
import org.lattejava.project.internal.JSONBuilder;

public final class UserJSON {
  public static String toJSON(User user) {
    return new JSONBuilder()
        .string("name", user.name())
        .integer("age", user.age())
        .string("email", user.email())
        .build();
  }

  public static byte[] toJSONBytes(User user) {
    return new JSONBuilder()
        .string("name", user.name())
        .integer("age", user.age())
        .string("email", user.email())
        .buildBytes();
  }
}
```

The `JSONBuilder` is part of the shared helper code emitted into `<moduleName>.internal`.

### Field order

Source-declaration order. For records, the canonical component order; for classes, the order fields are declared in the source file. This is also the visual order users see in their IDE — predictable, matches OpenAPI documentation ordering, and aligns with how every major JSON library behaves.

### Null and empty-collection emission

By default, **null values and empty collections are omitted** from the output. The user opts into emitting them with `@JSON(omitNulls = false)`.

Default (omit):

```java
@JSON
public record User(String name, String email, List<String> tags) {}

// new User("Alice", null, List.of()) → {"name":"Alice"}
```

Opt-in to emit:

```java
@JSON(omitNulls = false)
public record User(String name, String email, List<String> tags) {}

// new User("Alice", null, List.of()) → {"name":"Alice","email":null,"tags":[]}
```

Codegen for omit-nulls wraps each nullable-or-collection field in an `if (value != null && !value.isEmpty())` check before the `JSONBuilder` call. Codegen for emit-mode emits the call unconditionally and relies on `JSONBuilder` to write `null` / `[]` / `{}` appropriately.

### Pretty-printing

Out of scope for v1. The library emits compact output only. Future addition (a separate `toJSON(T, IndentStyle)` method or a builder flag) is a non-breaking extension and doesn't change any of the locked decisions above.

## Deserialization

Deserialization uses an observer pattern. The annotation processor generates a `*JSON` class for each `@JSON` type that implements `JSONObserver<T>`. The shared `JSONParser` (emitted into `<moduleName>.internal`) walks the JSON text and drives the observer through typed callbacks; the observer accumulates field values and returns the constructed instance from `finish()`.

### The `JSONObserver` interface

```java
public interface JSONObserver<T> {
  // Scalar value callbacks. Parser routes numeric values to the typed bucket
  // it already classifies during digit-walk (no boxing on the long fast path).
  void string(String key, String value);
  void integer(String key, long value);
  void bigInteger(String key, BigInteger value);
  void decimal(String key, BigDecimal value);
  void bool(String key, boolean value);
  void nullValue(String key);

  // Nested object: parent returns the child observer to use. Parser drives the
  // child to completion, calls child.finish(), then delivers the result to the
  // parent via object(...). Recursion lives in the parser; each observer only
  // handles its own one level of structure.
  JSONObserver<?> beginObject(String key);
  void object(String key, Object value);

  // Nested array: parent returns a JSONArrayObserver. Parser drives it to
  // completion and delivers the finished list back via array(...).
  JSONArrayObserver<?> beginArray(String key);
  void array(String key, Object value);

  // Called after this object's closing '}' is consumed.
  T finish();
}
```

### The `JSONArrayObserver` interface

Mirror of `JSONObserver` without keys — array elements are positional, not named. Returned from a parent's `beginArray(String key)` and driven by the parser through one full array. Element-is-`@JSON` cases dispatch via `beginObject()` / `object(value)`, mirroring the object protocol exactly one level down. Nested arrays dispatch via `beginArray()` / `array(value)`.

```java
public interface JSONArrayObserver<T> {
  void string(String value);
  void integer(long value);
  void bigInteger(BigInteger value);
  void decimal(BigDecimal value);
  void bool(boolean value);
  void nullValue();

  JSONObserver<?> beginObject();           // element is a JSON object
  void object(Object value);

  JSONArrayObserver<?> beginArray();       // element is itself an array
  void array(Object value);

  T finish();
}
```

Codegen emits one inner `JSONArrayObserver` per `List<E>` field on a record, scoped to the enclosing `*JSON` class. Element type drives which callbacks the inner observer implements meaningfully; the rest fall through to the lenient default (no-op for scalars, `SkipObserver` / `SkipArrayObserver` for nested) or throw under `@JSON(strict=true)`. See the "Field policies" section for the full rules.

### Example: nested records

```java
@JSON public record User(String name, int age, Address address) {}
@JSON public record Address(String street, String city, String zip) {}
```

Generated `AddressJSON` (the child):

```java
public final class AddressJSON implements JSONObserver<Address> {
  private String street;
  private String city;
  private String zip;

  AddressJSON() {}

  @Override public void string(String key, String value) {
    switch (key) {
      case "street" -> street = value;
      case "city"   -> city   = value;
      case "zip"    -> zip    = value;
    }
  }

  // Numeric / boolean / null / nested callbacks omitted from this example: Address
  // has no fields of those kinds. Under the lenient default policy, scalar callbacks
  // fall through the switch (no-op, silently drop) and nested-dispatch callbacks
  // return SkipObserver.INSTANCE / SkipArrayObserver.INSTANCE. Under @JSON(strict=true),
  // every default arm throws JSONProcessingException with the unknown key.

  @Override public Address finish() {
    return new Address(street, city, zip);
  }

  public static String toJSON(Address a) {
    return new JSONBuilder()
      .string("street", a.street())
      .string("city",   a.city())
      .string("zip",    a.zip())
      .build();
  }
}
```

Generated `UserJSON` (the parent):

```java
public final class UserJSON implements JSONObserver<User> {
  private String name;
  private int age;
  private Address address;

  UserJSON() {}

  public static User fromJSON(String json) {
    var observer = new UserJSON();
    new JSONParser().parse(json, observer);
    return observer.finish();
  }

  @Override public void string(String key, String value) {
    switch (key) {
      case "name" -> name = value;
    }
  }

  @Override public void integer(String key, long value) {
    switch (key) {
      case "age" -> age = Math.toIntExact(value);
    }
  }

  @Override public JSONObserver<?> beginObject(String key) {
    return switch (key) {
      case "address" -> new AddressJSON();
      default -> throw new JSONProcessingException("Unexpected object at key [" + key + "]");
    };
  }

  @Override public void object(String key, Object value) {
    switch (key) {
      case "address" -> address = (Address) value;
    }
  }

  @Override public User finish() {
    return new User(name, age, address);
  }
}
```

### Execution trace

For input `{"name":"Alice","age":30,"address":{"street":"123 Main","city":"Boulder","zip":"80301"}}`:

```
parser.parse(json, userObs)
  → userObs.string("name", "Alice")
  → userObs.integer("age", 30)
  → child = userObs.beginObject("address")       // returns AddressJSON
  → parser recurses into child:
       → child.string("street", "123 Main")
       → child.string("city",   "Boulder")
       → child.string("zip",    "80301")
       → addr = child.finish()                   // builds Address record
  → userObs.object("address", addr)              // parent stores nested result
  → user = userObs.finish()                      // builds User record
```

The `JSONParser` and `JSONObserver` interface are part of the shared JSON processing code emitted into `<moduleName>.internal` (see the `JSON processing code` section above).

## Type coverage

### Baseline supported types

The annotation processor accepts the following Java types on `@JSON` record components and class fields:

| Java type                        | JSON form        | Receive callback                            | Codegen narrowing                                                  |
|----------------------------------|------------------|---------------------------------------------|--------------------------------------------------------------------|
| `boolean` / `Boolean`            | `true` / `false` | `bool(...)`                                 | direct                                                             |
| `byte` / `Byte`                  | number           | `integer(long)`                             | range-checked narrowing to `byte`                                  |
| `short` / `Short`                | number           | `integer(long)`                             | range-checked narrowing to `short`                                 |
| `int` / `Integer`                | number           | `integer(long)`                             | `Math.toIntExact(value)`                                           |
| `long` / `Long`                  | number           | `integer(long)`                             | direct                                                             |
| `float` / `Float`                | number           | `integer(long)` or `decimal(BigDecimal)`    | `(float) value` / `value.floatValue()` (lossy)                     |
| `double` / `Double`              | number           | `integer(long)` or `decimal(BigDecimal)`    | `(double) value` / `value.doubleValue()` (lossy)                   |
| `BigInteger`                     | number           | `integer(long)` or `bigInteger(BigInteger)` | `BigInteger.valueOf(value)` / direct                               |
| `BigDecimal`                     | number           | `integer(long)` or `decimal(BigDecimal)`    | `BigDecimal.valueOf(value)` / direct                               |
| `String`                         | string           | `string(...)`                               | direct                                                             |
| Any `enum` type                  | string           | `string(...)`                               | `EnumType.valueOf(value)` / `enum.name()`; unknown constant throws |
| `UUID`                           | string           | `string(...)`                               | `UUID.fromString(value)` / `uuid.toString()`                       |
| `Instant`                        | string           | `string(...)`                               | `Instant.parse(value)` / `instant.toString()` (ISO-8601)           |
| `LocalDate`                      | string           | `string(...)`                               | `LocalDate.parse(value)` / `.toString()` (ISO-8601)                |
| `LocalDateTime`                  | string           | `string(...)`                               | `LocalDateTime.parse(value)` / `.toString()` (ISO-8601)            |
| `OffsetDateTime`                 | string           | `string(...)`                               | `OffsetDateTime.parse(value)` / `.toString()` (ISO-8601)           |
| `ZonedDateTime`                  | string           | `string(...)`                               | `ZonedDateTime.parse(value)` / `.toString()` (ISO-8601)            |
| `Duration`                       | string           | `string(...)`                               | `Duration.parse(value)` / `.toString()` (ISO-8601, e.g. `PT1H30M`) |
| `Period`                         | string           | `string(...)`                               | `Period.parse(value)` / `.toString()` (ISO-8601, e.g. `P1Y2M3D`)   |
| Another `@JSON` record/class     | object           | `beginObject` / `object`                    | direct (cast — generated code knows the target)                    |
| `List<E>` where `E` is supported | array            | `beginArray` / `array`                      | generated `JSONArrayObserver` per field; accumulates into `ArrayList`              |
| `Set<E>` where `E` is supported  | array            | `beginArray` / `array`                      | generated `JSONArrayObserver` per field; accumulates into `LinkedHashSet` (preserves insertion order) |
| `Map<K, V>` where `V` is supported and `K` is `String`, `UUID`, an `enum`, or a `java.time` type | object | `beginObject` / `object` | generated `JSONObserver` per field accepting any key; key parse via `K`'s string-form (identity for `String`, `UUID.fromString`, `Enum.valueOf`, `Type.parse`); value path follows `V`'s normal callback rules; accumulates into `LinkedHashMap` (preserves insertion order) |

When a number arrives in a callback that doesn't statically match the field type (e.g. `integer(long)` for a `BigDecimal` field), the codegen emits a widening conversion using the standard JDK constructor/factory. Float and double narrowing from `BigDecimal` is intrinsically lossy — `float`/`double` can't represent every `BigDecimal` exactly — and the codegen silently rounds via `BigDecimal.doubleValue()` / `floatValue()`. Users who need exact precision should declare the field as `BigDecimal` rather than a binary float type; there is no per-field annotation to "make narrowing loud" because the loss is inherent to the target type, not a policy choice.

Range-checked narrowing for `byte` and `short` is emitted inline by codegen:

```java
case "small" -> {
  if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
    throw new JSONProcessingException("Value [" + value + "] out of range for byte field [small]");
  }
  small = (byte) value;
}
```

Or, equivalently, a helper `Numbers.toByteExact(long)` / `Numbers.toShortExact(long)` lives in the shared helper code (`<moduleName>.internal`) and codegen calls it — keeps generated switch arms tidy. Final placement decided alongside §8 (Helper-code distribution).

### Explicitly unsupported in the baseline

- `char` / `Character` — JSON has no character type; mapping a one-character string is footgun-prone, and use cases that need a `char` are vanishingly rare. Compile-time error if used on an `@JSON` member.
- `byte[]` — no native binary type in JSON. Avoids committing the library to a specific binary encoding (base64 vs. JSON-array-of-ints). Consumers with binary fields can use `String` plus their own encoding. Compile-time error if used on an `@JSON` member.
- `Optional<T>` — JSON has only `null` and absent-key; mapping these onto `Optional.empty()` involves ambiguous semantics (is missing the same as null?) and round-tripping is contentious (serialize `Optional.empty()` as `null` or omit the key?). Required vs. optional semantics on a field are addressed via `@JSONField(required=…)` in §5 instead. Compile-time error if used on an `@JSON` member.
- `T[]` (arrays) — not idiomatic in modern Java DTOs; `List<T>` covers the same wire form. Compile-time error if used on an `@JSON` member.
- `Map<K, V>` where `K` is not a supported string-form type (e.g. `Map<Integer, V>`, `Map<MyRecord, V>`). JSON object keys must be strings; integer-as-string-key conversion is a footgun and arbitrary objects have no canonical string form. Compile-time error if used on an `@JSON` member.

### Compile-time enforcement

Any unsupported type on an `@JSON` record component or class field is reported via `Messager.printMessage(Diagnostic.Kind.ERROR, ..., element)` so the error lands on the offending source line in the IDE. The processor must not generate a half-complete `*JSON` companion that fails at runtime.

### Construction and access strategy

Records have an unambiguous shape: a canonical constructor and bare-name accessors. Non-record classes need explicit rules so the codegen knows how to instantiate, populate, and read them — without reflection.

**Deserialization (writing values into the instance):**

| Case                                              | Strategy                                                                                                                                                              |
|---------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Record                                            | Canonical constructor. Component names define the JSON-key → field mapping.                                                                                            |
| Class with `@JSONConstructor` on a constructor    | Use that constructor. Parameter names define the JSON-key → field mapping. Parameter names are read from the source-level model via `VariableElement.getSimpleName()`. |
| Class without `@JSONConstructor`                  | Class must have a public no-arg constructor. For each parsed JSON key the codegen tries, in order: (a) setter `setFoo(value)`; (b) public field `foo`.                |

**Serialization (reading values from the instance):**

| Case   | Strategy                                                                                                                                       |
|--------|------------------------------------------------------------------------------------------------------------------------------------------------|
| Record | Canonical accessor `foo()`.                                                                                                                    |
| Class  | For each field, codegen tries in order: (a) `getFoo()`; (b) `isFoo()` for `boolean` / `Boolean` only; (c) `foo()` (record-style); (d) public field `foo`. |

**`@JSONConstructor`**

```java
package org.lattejava.json;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.CONSTRUCTOR)
public @interface JSONConstructor {
}
```

Marks the constructor the processor should use for deserialization on non-record classes. JSON-key mapping is taken from the constructor's parameter names (always available from the source-level model in the same compilation round). Affects construction only; serialization on the same class still uses the getter/field strategy above.

**Compile-time errors (reported on the offending element via `Messager`):**

- Class without `@JSONConstructor` and no public no-arg constructor.
- Class field with no usable writer (no setter, not public).
- Class field with no usable reader (no getter, not public).
- `@JSONConstructor` on a record — records have a canonical constructor; the annotation is redundant and would be silently ignored.
- More than one constructor annotated with `@JSONConstructor` on the same class.

**Out of scope for v1:** fluent setters (those that return `this` rather than `void`). The single-shape JavaBean setter is the only writer pattern the codegen looks for. Adding fluent-setter recognition is a small, future-compatible extension.

## Polymorphism

The library supports OpenAPI-style polymorphic deserialization: a sealed interface or abstract base type is mapped to one of several concrete subtypes by reading a single discriminator property from the JSON object. This matches the `discriminator` mechanism in OpenAPI 3.x and is the dominant wire convention across the JSON ecosystem (Jackson, Pydantic, System.Text.Json, serde, Zod).

### Annotation surface

```java
package org.lattejava.json;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONTypeInfo {
  // Wire property name of the discriminator. No default — OpenAPI requires it.
  String property();
}

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONSubtype {
  // Discriminator value for this subtype. Optional; defaults to the simple class name.
  String value() default "";
}
```

Usage:

```java
@JSON
@JSONTypeInfo(property = "petType")
public sealed interface Pet permits Dog, Cat {}

@JSON @JSONSubtype("Dog") public record Dog(String name, int packSize) implements Pet {}
@JSON @JSONSubtype("Cat") public record Cat(String name, int lives)    implements Pet {}
```

### Where polymorphism works

- **Root:** `PetJSON.fromJSON(json)` returns the right subtype; `PetJSON.toJSON(pet)` dispatches on the concrete runtime type.
- **Field:** `record Owner(String name, Pet pet)` — `pet` is parsed/written polymorphically with no extra ceremony.
- **List element:** `List<Pet> pets` — each element dispatches independently.
- **Map value:** `Map<String, Pet>` (once map support lands in §3).
- **Nested:** all of the above compose recursively without limit.

### The `JSONPolymorphicObserver` interface

Sits alongside `JSONObserver` and `JSONArrayObserver`. A parent's `beginObject(key)` may return either a `JSONObserver<?>` (concrete type) or a `JSONPolymorphicObserver<?>` (sealed hierarchy). The parser detects which and routes accordingly.

```java
public interface JSONPolymorphicObserver<T> {
  // Wire property name of the discriminator. Constant per generated class.
  String discriminatorKey();

  // Return the concrete child observer for a given discriminator value.
  // Implementations throw JSONProcessingException on an unknown value.
  JSONObserver<? extends T> observerFor(String discriminatorValue);
}
```

Generated `PetJSON`:

```java
public final class PetJSON implements JSONPolymorphicObserver<Pet> {
  @Override public String discriminatorKey() { return "petType"; }

  @Override public JSONObserver<? extends Pet> observerFor(String value) {
    return switch (value) {
      case "Dog" -> new DogJSON();
      case "Cat" -> new CatJSON();
      default -> throw new JSONProcessingException(
          "Unknown discriminator value [" + value + "] for [petType]");
    };
  }

  public static Pet fromJSON(String json) {
    var poly = new PetJSON();
    return (Pet) new JSONParser().parsePolymorphic(json, poly);
  }

  public static String toJSON(Pet pet) {
    return switch (pet) {
      case Dog d -> DogJSON.toJSON(d);
      case Cat c -> CatJSON.toJSON(c);
    };
  }
}
```

### Parser strategy — two-pass on the substring

When the parser is given a `JSONPolymorphicObserver` as the target for an object, it does the following:

1. Save the current input position (`int saved = pos`).
2. Scan ahead through the object's keys looking for the one matching `discriminatorKey()`. Skip values structurally — that is, recognize `{`/`}`, `[`/`]`, strings (with escape handling), numbers, booleans, and null well enough to step over them, without invoking observer callbacks. Track brace depth so the scan stops at the end of the object being scanned, not the document.
3. Rewind to the saved position (`pos = saved`).
4. Call `poly.observerFor(discriminatorValue)` to get the concrete child observer.
5. Parse the object body normally into the child observer, ignoring the discriminator key (which doesn't correspond to a field on the subtype).
6. Call `child.finish()` and deliver the result to the parent via `object(key, value)`.

Re-walking the substring is fine: input is a `String`/`byte[]`, position is an `int`, and rewind costs nothing. The scan-ahead is faster than a full parse because it only does the minimum lexing needed to step over nested values. Worst-case work is ~1.5–2× a normal object parse — negligible at JWT and DTO sizes.

### Serialization — discriminator emission

Each generated `*JSON.toJSON(...)` for a subtype emits the discriminator pair as the **first** key, when the subtype is part of an `@JSONTypeInfo` hierarchy. Standalone `@JSON` records that aren't in a hierarchy don't emit a discriminator. This is determined at codegen time by looking at the subtype's super-interfaces.

```java
// DogJSON.toJSON
public static String toJSON(Dog d) {
  return new JSONBuilder()
    .string("petType", "Dog")     // discriminator first
    .string("name", d.name())
    .integer("packSize", d.packSize())
    .build();
}
```

The polymorphic parent's `toJSON` (e.g., `PetJSON.toJSON(Pet)`) is a switch on the concrete runtime type that dispatches to the right subtype's `toJSON`.

### Errors

**Compile-time (via `Messager.printMessage(ERROR, ...)`):**

- `@JSONTypeInfo` on a non-sealed type.
- A permitted subtype of an `@JSONTypeInfo` interface missing `@JSON`.
- Two subtypes resolving to the same discriminator value (after applying the simple-class-name default).
- Discriminator property name collides with an actual record component or field on any subtype.
- `@JSONSubtype` on a type that doesn't implement an `@JSONTypeInfo` parent.

**Runtime (`JSONProcessingException`):**

- Discriminator value matching no subtype — message names both the value and the property.
- Discriminator key absent from the JSON object — message names the property.

## Field policies

Default behavior aligns with OpenAPI 3.x semantics (fields not in `required` are optional, `additionalProperties` defaults to true). Per-field strictness is opt-in via `@JSONField(required=true)`; per-class strictness is opt-in via `@JSON(strict=true)`. Both annotations are specified in §5.

### Missing JSON fields

A JSON object that omits a record component or class field is accepted by default.

- **Primitive field absent** — codegen leaves it at the Java default (`0` for numeric primitives, `false` for `boolean`). For records, this means the canonical constructor is invoked with the default value for any uninitialized primitive component.
- **Reference field absent (`String`, `UUID`, nested record, list, etc.)** — codegen leaves it at `null`. For records, this means the canonical constructor receives `null` for that component.
- **Collection field absent** — left at `null` (not an empty collection). Codegen does not silently substitute an empty list/set/map; users that prefer empty defaults can declare them on the record and use `@JSONField(required=false)` semantics.

With `@JSONField(required=true)`, the codegen emits a `boolean fooSeen` flag in the observer, sets it in each relevant callback for that field, and checks all required flags at the top of `finish()`. Throws `JSONProcessingException` listing any unset required fields. Tracking machinery is only emitted for required fields — zero overhead for the common lenient case.

### Unknown JSON fields

JSON keys that don't appear on the target type are silently dropped by default. This is implemented entirely through the existing observer pattern via two singletons in the shared helper code (`<moduleName>.internal`):

```java
public final class SkipObserver implements JSONObserver<Object> {
  public static final SkipObserver INSTANCE = new SkipObserver();
  private SkipObserver() {}

  @Override public void string(String key, String value) {}
  @Override public void integer(String key, long value) {}
  @Override public void bigInteger(String key, BigInteger value) {}
  @Override public void decimal(String key, BigDecimal value) {}
  @Override public void bool(String key, boolean value) {}
  @Override public void nullValue(String key) {}
  @Override public JSONObserver<?> beginObject(String key) { return INSTANCE; }
  @Override public void object(String key, Object value) {}
  @Override public JSONArrayObserver<?> beginArray(String key) { return SkipArrayObserver.INSTANCE; }
  @Override public void array(String key, Object value) {}
  @Override public Object finish() { return null; }
}

public final class SkipArrayObserver implements JSONArrayObserver<Object> {
  public static final SkipArrayObserver INSTANCE = new SkipArrayObserver();
  // ... all callbacks no-op, beginObject() returns SkipObserver.INSTANCE,
  //     beginArray() returns SkipArrayObserver.INSTANCE, finish() returns null
}
```

Codegen emits `default -> SkipObserver.INSTANCE` in the parent's `beginObject` switch and `default -> SkipArrayObserver.INSTANCE` in its `beginArray` switch. Unknown scalar keys fall through the parent's `string` / `integer` / etc. switches with no `default` action — they're no-ops by definition.

With `@JSON(strict=true)`, codegen swaps every `default` arm to `throw new JSONProcessingException("Unknown JSON key [" + key + "] for type [User]");` instead.

### Null for a primitive field

JSON `null` for a primitive-typed field (e.g. `"age": null` where `age` is `int`) always throws `JSONProcessingException`, regardless of strictness mode. No coercion is defensible — `null` doesn't fit in a primitive — and silently substituting the default would mask data bugs. The codegen for a primitive field emits a throwing `case "foo" -> throw ...` in `nullValue(String key)`.

### Numeric width mismatch

A JSON number that doesn't fit the target Java primitive (e.g. `"age": 1e20` for `int age`) throws `JSONProcessingException` at runtime, via the `Math.toIntExact` / `BigDecimal.intValueExact` / `BigInteger.intValueExact` narrowing calls the codegen already emits (see "Construction and access strategy" above). Always-throw, not configurable — silent truncation is never the right answer.

### Catch-all for unknown fields

For forward-compatibility (OpenAPI's `additionalProperties: schema` pattern), a record/class may declare exactly one `Map<String, Object>` field annotated `@JSONCatchAll`. All JSON keys that don't map to a named component or field land in that map.

```java
@JSON
public record APIResponse(
    String id,
    String status,
    @JSONCatchAll Map<String, Object> extras
) {}
```

**Annotation:**

```java
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface JSONCatchAll {
}
```

**Wire-to-Java mapping for catch-all values.** The map's value type is `Object`. Each JSON value lands as its natural Java shape — the same shapes the current `JSONProcessor` produces:

| JSON value                  | Java shape inside the catch-all |
|-----------------------------|---------------------------------|
| string                      | `String`                        |
| integer (≤ 18 digits)       | `Long`                          |
| integer (> 18 digits)       | `BigInteger`                    |
| number with `.` or exponent | `BigDecimal`                    |
| `true` / `false`            | `Boolean`                       |
| `null`                      | `null` (entry is still added)   |
| object                      | `LinkedHashMap<String, Object>` |
| array                       | `ArrayList<Object>`             |

**Codegen mechanics.** Codegen detects `@JSONCatchAll` and emits a `Map<String, Object> catchAll = new LinkedHashMap<>()` in the observer. Each scalar `default` arm becomes `catchAll.put(key, value);` instead of a no-op. The `default` arms of `beginObject` and `beginArray` return two new helper observers from the shared helper code:

```java
public final class AnyObjectObserver implements JSONObserver<Map<String, Object>> {
  private final Map<String, Object> map = new LinkedHashMap<>();
  // every scalar callback does map.put(key, value)
  // beginObject(key) returns a fresh AnyObjectObserver
  // beginArray(key) returns a fresh AnyArrayObserver
  // object(key, value) does map.put(key, value)
  // array(key, value) does map.put(key, value)
  @Override public Map<String, Object> finish() { return map; }
}

public final class AnyArrayObserver implements JSONArrayObserver<List<Object>> {
  // analogous: List<Object> accumulator, beginObject() → AnyObjectObserver,
  // beginArray() → AnyArrayObserver, finish() → list
}
```

These observers are *not* singletons (unlike `SkipObserver`) — each instance accumulates its own structure. They allocate per nested object/array, but only along the unknown-keys path.

**Interaction with `@JSON(strict=true)`.** When `@JSONCatchAll` is present, it overrides strictness for unknowns — unknown keys are captured, not rejected. Otherwise the annotation would be useless on strict types.

**Serialization.** `toJSON` writes the catch-all map's entries after the named fields, in iteration order. A catch-all key that duplicates the name of a real field throws `JSONProcessingException` at write time — should never happen with sensible code, but cheap to detect.

**Compile-time errors:**

- `@JSONCatchAll` on a field whose type is not `Map<String, Object>` — exact type required.
- More than one `@JSONCatchAll` on the same type.
- `@JSONCatchAll` combined with `@JSONField(name=...)` or `@JSONField(required=true)` — the catch-all has no single key, so these don't apply.

## Field naming and selection

### Naming strategy

The Java field name is used as the JSON key by default (identity mapping). To convert a whole record/class to a different convention without per-field annotations, set `naming` on `@JSON`:

```java
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record User(String userName, int packSize) {}
// wire form: {"user_name":"...","pack_size":3}
```

```java
public enum NamingStrategy {
  IDENTITY,
  CAMEL_CASE,
  SNAKE_CASE,
  PASCAL_CASE,
  KEBAB_CASE
}
```

The strategy is applied at compile time — codegen runs each Java field name through the strategy and bakes the resulting wire key as a string literal into the generated switch arms. Zero runtime cost; the JSON parser is never aware of naming strategy.

Per-field rename overrides the strategy:

```java
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record APIRequest(
    String userName,                       // → user_name
    @JSONField(name = "X-Request-ID") String requestId  // → X-Request-ID
) {}
```

### The `@JSON` annotation

```java
package org.lattejava.json;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSON {
  boolean strict() default false;
  NamingStrategy naming() default NamingStrategy.IDENTITY;
  boolean omitNulls() default true;
}
```

- `strict = true` — unknown JSON keys throw at parse time, instead of being silently dropped (overridden by `@JSONCatchAll` if present).
- `naming` — class-wide naming strategy applied to every field that doesn't have an explicit `@JSONField(name=…)`.
- `omitNulls = true` (default) — null values and empty collections are omitted from serialized output. Set to `false` to emit `"foo": null` and `"tags": []` faithfully (preserves null-vs-absent distinction at the cost of larger payloads).

The annotation has `SOURCE` retention — it's only needed at compile time by the processor, never at runtime.

### The `@JSONField` annotation

```java
package org.lattejava.json;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface JSONField {
  String name() default "";          // wire-form override; "" = strategy-derived from Java name
  boolean required() default false;  // throw on missing during deserialization
  boolean ignore() default false;    // skip both serialization and deserialization
  String format() default "";        // custom format for java.time fields (DateTimeFormatter pattern)
  boolean readOnly() default false;  // serialize only (OpenAPI vocabulary)
  boolean writeOnly() default false; // deserialize only (OpenAPI vocabulary)
}
```

**`name`** — overrides the wire-form key for this one field. Useful for keys that are reserved Java identifiers, contain hyphens, or otherwise can't match the field name through any strategy.

**`required`** — codegen tracks whether the field was set during a parse (boolean `seen` flag, set in each callback that handles this field), and `finish()` throws if any required field was unset. Tracking machinery is only emitted for required fields.

**`ignore`** — codegen omits this field from both `toJSON` (no serialization) and the observer (no callback handling). The field still exists on the Java side; it's just invisible to JSON. Useful for derived fields, computed values, or sensitive data.

**`format`** — only valid on `Instant`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `Duration`, or `Period` fields. Codegen emits `private static final DateTimeFormatter FORMAT_foo = DateTimeFormatter.ofPattern("…");` on the generated `*JSON` class and uses it for both parse and format. Compile-time error if applied to any other type.

**`readOnly`** — vocabulary matches OpenAPI: "the server reads this out to the client; the client never sends it back." Implementation: codegen emits this field in `toJSON` but not in the observer. Deserialization silently drops the key (or throws under `@JSON(strict=true)`).

**`writeOnly`** — vocabulary matches OpenAPI: "the client sends this; the server never echoes it." Implementation: codegen handles this field in the observer but omits it from `toJSON`.

### Compile-time errors

- `readOnly = true` and `writeOnly = true` on the same `@JSONField` — equivalent to `ignore`, ambiguous.
- `ignore = true` combined with `name`, `required`, `format`, `readOnly`, or `writeOnly` — the other attributes have no effect.
- `format` on a field whose type is not one of the `java.time` types.
- `name` on a field annotated `@JSONCatchAll` — no single key applies to a catch-all map.
- `required = true` on a field annotated `@JSONCatchAll` — the map itself is the catch-all.
- `@JSONField` on a field annotated `@JSONCatchAll` if any attribute other than (no attributes) is set — same reasoning.
- Two fields resolving to the same wire-form key after applying the naming strategy and per-field renames.

## Errors

### Compile-time errors

Every compile-time check is reported through the annotation processor's `Messager` so the error lands on the offending source line in the user's IDE:

```java
processingEnv.getMessager().printMessage(
    Diagnostic.Kind.ERROR,
    "Cross-module @JSON references are not supported. Field [...] references [...] in module [...].",
    offendingElement);
```

Every compile-time error case described elsewhere in this document follows the same pattern:

- Unsupported field type (`char`, `byte[]`, `Optional<T>`, `T[]`, unsupported `Map` key).
- Class without `@JSONConstructor` and no public no-arg constructor.
- Class field with no usable writer or reader.
- `@JSONConstructor` on a record, or multiple on one class.
- `@JSONTypeInfo` on a non-sealed type.
- `@JSONSubtype` on a type not under an `@JSONTypeInfo` interface.
- Duplicate discriminator values across subtypes; discriminator-key colliding with a field name.
- Invalid `@JSONField` combinations (`readOnly`+`writeOnly`, `ignore` plus other attributes, `format` on a non-date type, `name` or `required` on a catch-all).
- `@JSONCatchAll` on the wrong field type, or more than one per type.
- Two fields resolving to the same wire-form key after naming strategy and per-field renames are applied.
- Cross-module `@JSON` references in record components or class fields.
- Project missing a `module-info`.

The processor must not generate any half-complete companion or helper code when any of these errors occurs.

### Runtime errors

All runtime errors raised by the parser, builder, or generated companions are of type `JSONProcessingException` (a `RuntimeException`). The exception message includes:

- **The JSON-path** of the current location (jq-style: `$` for root, `.foo` for object keys, `[N]` for array indices). The parser maintains an `ArrayDeque<String>` push/pop as it descends and ascends through objects and arrays; the format step is only paid when an exception is thrown.
- **The byte offset** into the input (`at position [N]`) for lexical errors (malformed numbers, unterminated strings, etc.) where the path isn't yet known.
- **The runtime value or key** that caused the error, wrapped in square brackets per the project's error-message convention.

Example messages:

```
Unknown discriminator value [Bird] for [petType] at path [$.users[3].pet]
Value [99999999999999999999] out of range for [int] field [age] at path [$.users[0].age]
Expected [string] but found [null] for primitive field [name] at path [$.profile.name]
Duplicate JSON key [email] at path [$.users[2]]
Required field [id] missing at path [$.products[0]]
```

The path-tracking machinery adds one `ArrayDeque` allocation per `JSONParser.parse(...)` call and incurs no hot-path cost beyond `push` / `pop` on each begin/end of a container. Negligible for JWT and DTO payloads; small but acceptable for large documents.

## Module integration

### Consumer's `module-info.java`

The consumer declares a compile-only dependency on this library:

```java
module org.lattejava.project {
  requires static org.lattejava.json;
  // ... other requires ...
}
```

`requires static` makes the library required at compile time and optional at runtime. The compile-time half is necessary because the consumer's source references the annotation types (`@JSON`, `@JSONField`, etc.) — without `requires` of any kind, javac reports `package org.lattejava.json is not visible`. The runtime half is unnecessary because:

- All annotations have `SOURCE` retention — they don't appear in the consumer's compiled `.class` files.
- The helper code lives inside the consumer's own `<moduleName>.internal` package — no runtime reach into `org.lattejava.json`.

The annotation processor itself runs in javac's process, discovered via `META-INF/services/javax.annotation.processing.Processor`. It does **not** require any specific consumer `module-info` declaration to run — but the user's source code containing `@JSON` won't compile without `requires static`.

### Generated package handling

The `<moduleName>.internal` package is created implicitly by the processor emitting source files into it. By default, packages declared in `module-info.java` are non-exported unless explicitly listed under `exports`. The user takes no action to keep `internal` private — it just is.

The processor emits a single `package-info.java` alongside the helper files:

```java
// Generated by org.lattejava.json — do not edit.
// All files in this package are regenerated on every clean build.
package org.lattejava.project.internal;
```

The comment block signals to anyone browsing the source tree that the package is machine-emitted. No `@Retention` annotations, no programmatic behavior — purely an in-source signpost.

A future release may grow per-type companion packages a similar `package-info.java`, but it isn't required for v1.

## Thread safety

The static entry points generated on every `*JSON` companion class are thread-safe:

```java
UserJSON.fromJSON(json)         // safe to call from any thread, with any inputs
UserJSON.toJSON(user)           // safe to call from any thread, with any inputs
UserJSON.toJSONBytes(user)      // safe to call from any thread, with any inputs
```

Each invocation allocates its own `JSONParser` or `JSONBuilder` plus per-call observer instances. No state is shared between calls.

An observer instance returned by `new UserJSON()` is **not** thread-safe — it accumulates field state during a single parse and is intended to be discarded after `finish()`. Generated code uses each observer for exactly one parse and then drops it. Users who write their own observer-driving code should follow the same pattern.

The stateless singletons in `<moduleName>.internal` — `SkipObserver.INSTANCE`, `SkipArrayObserver.INSTANCE` — are thread-safe by construction (no mutable state at all). `AnyObjectObserver` and `AnyArrayObserver` are *not* singletons; each instance accumulates its own structure and is per-call.

## Annotations reference

All six annotations are introduced in context throughout the doc. Consolidated here for reference:

| Annotation         | Target                               | Retention | Purpose                                                                                                          |
|--------------------|--------------------------------------|-----------|------------------------------------------------------------------------------------------------------------------|
| `@JSON`            | `TYPE`                               | `SOURCE`  | Marks a record, class, or sealed interface for JSON serialization. Attributes: `strict`, `naming`, `omitNulls`.  |
| `@JSONField`       | `RECORD_COMPONENT`, `FIELD`          | `SOURCE`  | Per-field configuration: `name`, `required`, `ignore`, `format` (date types), `readOnly`, `writeOnly`.           |
| `@JSONTypeInfo`    | `TYPE` (sealed interfaces only)      | `SOURCE`  | Declares a sealed type polymorphic. Attribute: `property` (discriminator key name; required).                    |
| `@JSONSubtype`     | `TYPE` (subtypes of `@JSONTypeInfo`) | `SOURCE`  | Sets the discriminator value for this subtype. Attribute: `value` (defaults to simple class name).               |
| `@JSONConstructor` | `CONSTRUCTOR` (non-record classes)   | `SOURCE`  | Marks the constructor the processor should use for deserialization. Parameter names define the JSON-key mapping. |
| `@JSONCatchAll`    | `RECORD_COMPONENT`, `FIELD`          | `SOURCE`  | Marks a `Map<String, Object>` field as the bucket for unknown JSON keys. Exactly one per type allowed.           |

All annotations use `@Retention(RetentionPolicy.SOURCE)` — they're only needed by the annotation processor at compile time, never at runtime. This strips them from consumer `.class` files (smallest possible footprint, no reflection-visibility leak) and matches the library's "no runtime dependency" goal.

No general-purpose converter SPI is provided in v1. Users with custom types that don't map cleanly to the supported set should either wrap them in records (cleanest), switch to a supported string-form type, or use `@JSONField(ignore = true)` and handle that field manually. A converter SPI is a natural future extension and is intentionally deferred.

## Open design questions

Decisions that still need to be made before this design is implementable. Ordered roughly by how much each one ripples through the rest of the design — settling earlier items tends to constrain the choices for later ones.

### 1. Observer interface shape

- [x] **Callback granularity.** Per-JSON-type, with numbers split into three typed methods: `integer(String key, long value)`, `bigInteger(String key, BigInteger value)`, `decimal(String key, BigDecimal value)`. Strings, booleans, and null each get their own callback. Parser routes to each based on the bucket it already classifies during digit-walk. Avoids boxing on the common `long` fast path and gives the codegen statically typed values at every call site — no `instanceof` branching, easy precision-safe narrowing via `Math.toIntExact` / `BigInteger.intValueExact` / `BigDecimal.intValueExact`.
- [x] **Nested object dispatch.** Parent observer returns a child observer from `JSONObserver<?> beginObject(String key)`. Parser drives the child to completion, calls `child.finish()`, then delivers the resulting value to the parent via `void object(String key, Object value)`. Recursion lives in the parser; each observer only handles one level of structure. Codegen for the parent is a key-switch returning the right `*JSON` instance; for the child, the cast in `object(...)` is type-safe because codegen knows the expected target type.
- [x] **Array handling.** Separate `JSONArrayObserver<T>` interface returned from `JSONObserver.beginArray(String key)`. Parser drives the array observer to completion, calls `finish()`, then delivers the result back via `JSONObserver.array(String key, Object value)`. Element-is-`@JSON` cases dispatch via the array observer's own `beginObject()` / `object(value)`, mirroring the object protocol one level down. Codegen emits a small inner `JSONArrayObserver` per `List<E>` field. Element callbacks are positional (no key, no index).
- [x] **Where JSON→Java coercion happens.** Three-layer pipeline. The parser only classifies into JSON-side buckets (`String`, `long`-fits, `BigInteger`, `BigDecimal`, `boolean`, `null`, object, array) and has no knowledge of target Java types. The `JSONObserver` interface is shaped by those buckets, not by field types. The generated `*JSON` class performs the final narrowing in each switch arm using JDK methods that throw on data loss (`Math.toIntExact`, `BigDecimal.intValueExact`, `UUID.fromString`, `Instant.parse`, `Enum.valueOf`, etc.). No `instanceof` chains, no reflection. Silent precision loss occurs only on intrinsically lossy target-type choices (e.g. `float`/`double` field receiving a `BigDecimal`); users who need exact precision declare the field as `BigDecimal` instead.

### 2. Type coverage

- [x] **Baseline.** `boolean`, `byte`, `short`, `int`, `long`, `float`, `double` (and their boxed forms), `String`, `BigInteger`, `BigDecimal`, records/classes annotated `@JSON`, `List<E>` where `E` is supported. `char` / `Character` and `byte[]` are explicitly out — JSON has no character type and no binary type, and the library declines to pick a binary encoding for users. Unsupported types fail at compile time via `Messager.printMessage(ERROR, ...)`. See the "Type coverage" section above for the full table.
- [x] **Common extras.** In: any `enum` type (string form via `name()` / `valueOf`); `UUID` (ISO 8-4-4-4-12); `Instant`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `Duration`, `Period` (each via its built-in ISO-8601 `parse` / `toString`). Out: `Optional<T>` — required vs. optional semantics are handled via `@JSONField(required=…)` in §5 instead of through a wrapper type.
- [x] **Classes vs. records.** Deserialization tiers: record canonical constructor → `@JSONConstructor`-marked constructor on a class (parameter names from `VariableElement.getSimpleName()`) → public no-arg constructor plus per-field setter (`setFoo`) → public field. Serialization tiers: record accessor → `getFoo()` → `isFoo()` (boolean only) → `foo()` → public field. New `@JSONConstructor` annotation (`@Target(CONSTRUCTOR)`, `@Retention(SOURCE)`). Compile-time errors for: no usable constructor, no usable writer or reader per field, `@JSONConstructor` on a record, multiple `@JSONConstructor` on one class. Fluent setters out of scope for v1. See the "Construction and access strategy" subsection.
- [x] **Polymorphism.** Full OpenAPI-style polymorphism for sealed interfaces. New annotations `@JSONTypeInfo(property=...)` on the sealed type and `@JSONSubtype(value=...)` on each permitted subtype (defaulting to simple class name). New `JSONPolymorphicObserver<T>` interface returned from parent `beginObject(key)`. Parser implements two-pass-on-the-substring: save position, scan-ahead for the discriminator key, rewind, parse normally with the chosen child observer. Works at root, as a field, as a list element, as a map value (when maps land in §3), and arbitrarily nested. Each subtype's `toJSON` emits the discriminator pair as the first key. Compile-time errors for: `@JSONTypeInfo` on non-sealed types, subtypes missing `@JSON`, duplicate discriminator values, discriminator-key/field-name collision. See the "Polymorphism" section.

### 3. Generic containers

- [x] **Which collections.** `List<E>`, `Set<E>` (deserializes to `LinkedHashSet`, preserves insertion order), `Map<K, V>` where `V` is supported and `K` is `String` or any other string-form type (`UUID`, any `enum`, the `java.time` types). Map keys are parsed via `K`'s string-form parse method; non-string-form keys (`Map<Integer, V>`, `Map<MyRecord, V>`) are a compile-time error.
- [x] **Arrays.** `T[]` is out — `List<T>` covers the same wire form and is more idiomatic. Compile-time error if used on an `@JSON` member.
- [x] **Nesting.** Unlimited by induction. `List<Map<String, List<Address>>>` and similar fall out of element-type recursion in the codegen.
- [x] **Element-type resolution.** Codegen reads source-level generics via the `javax.lang.model.type` API (`DeclaredType.getTypeArguments()`). Type arguments are visible at the source level even when erased at runtime, so `List<User>` and `Map<UUID, Pet>` dispatch into the right `*JSON` (and `JSONPolymorphicObserver` for sealed value types) without reflection.

### 4. Field policies

- [x] **Missing JSON field for a primitive.** Lenient default — codegen leaves the field at its Java default (`0` / `0.0` / `false`). Per-field `@JSONField(required=true)` tightens to a throw, implemented via per-required-field `seen` flags checked at `finish()`. See "Field policies" above.
- [x] **Missing JSON field for a reference.** Lenient default — `null` (including for `List`, `Set`, `Map` — no silent empty-collection substitution). Per-field `@JSONField(required=true)` tightens to a throw.
- [x] **JSON `null` for a primitive field.** Always throws `JSONProcessingException`, regardless of strictness mode. No coercion is defensible. Codegen emits a throwing `case "foo" -> throw ...` arm in `nullValue(String key)` for every primitive field.
- [x] **Unknown JSON fields.** Lenient default — silently dropped via shared `SkipObserver` / `SkipArrayObserver` singletons in the helper code. Codegen emits `default -> SkipObserver.INSTANCE` in the parent's `beginObject` switch (and analogous for arrays). With `@JSON(strict=true)`, codegen swaps every `default` arm to `throw new JSONProcessingException("Unknown JSON key [...] for type [...]")`.
- [x] **Numeric width mismatch.** Always throws at runtime via the `Math.toIntExact` / `intValueExact` calls the codegen already emits during narrowing. Single global policy; not configurable — silent truncation is never the right answer.
- [x] **Catch-all for unknown fields.** New `@JSONCatchAll` annotation, applied to exactly one `Map<String, Object>` field on a record or class. Unknown JSON keys are captured into that map at their natural Java shape (`String`, `Long`, `BigInteger`, `BigDecimal`, `Boolean`, `null`, `LinkedHashMap<String, Object>`, `ArrayList<Object>`). Two new helper observers in the shared helper code (`AnyObjectObserver`, `AnyArrayObserver`) drive nested unknown structures. Catch-all overrides `@JSON(strict=true)`. Compile-time errors for wrong type, multiple catch-alls per type, or combining with `@JSONField(name=...)` / `@JSONField(required=true)`. See "Catch-all for unknown fields" above.

### 5. Field naming and selection

- [x] **Naming strategy.** Identity by default. Per-class override via `@JSON(naming = NamingStrategy.X)` where `X` is `IDENTITY`, `CAMEL_CASE`, `SNAKE_CASE`, `PASCAL_CASE`, or `KEBAB_CASE`. Per-field override via `@JSONField(name = "...")`. Strategy applied at compile time — codegen bakes the resulting wire key as a string literal into the generated switch arms. Zero runtime cost. See "Naming strategy" above.
- [x] **Per-field overrides.** New `@JSONField` annotation on record components and class fields. Attributes: `name`, `required`, `ignore`, `format` (date types only), `readOnly`, `writeOnly`. Compile-time errors for `readOnly`+`writeOnly` together, `ignore` plus any other attribute, `format` on a non-date type, and a handful of `@JSONCatchAll` interaction rules. See "The `@JSONField` annotation" above.
- [x] **Read-only / write-only fields.** Supported via `@JSONField(readOnly = true)` and `@JSONField(writeOnly = true)`. Vocabulary matches OpenAPI exactly: `readOnly` means serialize-only on the Java side (server-to-client), `writeOnly` means deserialize-only (client-to-server). Codegen handles this field in only the matching direction.

### 6. Serialization output

- [x] **Return type of `toJSON`.** Both: `String toJSON(T)` and `byte[] toJSONBytes(T)` on every generated `*JSON` class. Shared internal writer produces UTF-8 bytes; the `String` form decodes them. Byte-oriented consumers avoid an extra UTF-8 round-trip.
- [x] **Field order.** Source-declaration order (record component order for records, field-declaration order for classes). Matches every major JSON library and aligns with OpenAPI documentation ordering.
- [x] **`null` value emission.** Omit by default. `null` values and empty collections are skipped from output. Per-class `@JSON(omitNulls = false)` opts into emitting `"foo": null` and `"tags": []` (round-trippable, preserves null-vs-absent distinction).
- [x] **Pretty-printing.** Out of scope for v1. Compact output only. External tools handle the debug case; future addition is a non-breaking extension.

### 7. Cross-module `@JSON` references

- [x] **Per-type companion placement.** Per-type companions stay in `<typePackage>.internal` (hidden from external consumers by default, no collision risk with user-written code, no public package pollution). Cross-module @JSON nesting is forbidden in v1 via a compile-time error — the annotation processor compares `Elements.getModuleOf(typeBeingProcessed)` to the module of any referenced `@JSON` type and rejects mismatches. Cross-module use is limited to calling the static `*JSON.fromJSON` / `*JSON.toJSON` entry points (which don't expose the per-module interface types). See "Cross-module references are forbidden in v1" in the "JSON processing code" section.
- [x] **Naming clash between the two `internal` packages.** Not actually a clash. Per-type companions live in `<typePackage>.internal` and shared helpers live in `<moduleName>.internal`. The two have identical simple names but distinct fully-qualified names, and both are conventionally `internal` (implementation details). Both are un-exported by default. No rename needed.

### 8. Helper-code distribution

- [x] **How the processor produces `JSONParser`/`JSONBuilder`/`JSONObserver` source.** Hybrid approach: helpers are maintained as ordinary Java source in this library's source tree (compiled and tested as part of this library's build). The processor JAR's build step copies those `.java` files into the JAR as text resources under `META-INF/json-helpers/`. At codegen time, the processor reads each resource, rewrites only the leading `package` statement to `<moduleName>.internal`, and emits via `Filer.createSourceFile`. No extra dependencies (no JavaPoet); helpers stay first-class Java for IDE and refactoring support. See "How the helper source is produced" above.
- [x] **Search-for-existing-copy mechanism.** Dropped. The destination package is well-known per module, and the processor tracks emission via a per-invocation `boolean helpersEmitted` flag — writes the helper set on the first round that sees an `@JSON` type, skips on subsequent rounds. `Filer` handles duplicate-write semantics within a round; build tools handle incremental compilation. No project-wide scanning.

### 9. Annotation surface

- [x] **Full annotation set.** Six annotations: `@JSON`, `@JSONField`, `@JSONTypeInfo`, `@JSONSubtype`, `@JSONConstructor`, `@JSONCatchAll`. Attributes pinned per the sections above. See "Annotations reference" for the consolidated table.
- [x] **Custom converter SPI.** Out of scope for v1. Users with custom types wrap them in records, switch to a supported string-form type, or use `@JSONField(ignore = true)` and handle manually. A converter SPI is a future extension.
- [x] **`@JSON` retention.** `SOURCE` for every annotation. Processor reads them at compile time; nothing else needs them. Strips them from consumer `.class` files entirely. Existing `JSON.java` (currently `RUNTIME`) is updated to `SOURCE` during the refactor.

### 10. Errors

- [x] **Runtime parse errors.** Reuse `JSONProcessingException` (a `RuntimeException`). Parser tracks the JSON-path with a per-parse `ArrayDeque<String>` (push on `beginObject` / `beginArray`, pop on end); format paid only at throw time. Messages include the jq-style path (`$.users[3].pet`), the byte offset for lexical errors, and the runtime value/key in square brackets per the project's error-message convention. See "Runtime errors" in the new "Errors" section.
- [x] **Compile-time errors.** All processor diagnostics use `Messager.printMessage(Diagnostic.Kind.ERROR, message, offendingElement)` so they appear on the source line in the IDE. The processor must not emit any half-complete companion or helper code when any compile-time check fails. Full list of compile-time error cases in the new "Errors" section.

### 11. `module-info` interaction

- [x] **Consumer's `requires`.** `requires static org.lattejava.json` in the consumer's `module-info.java`. Compile-only — the processor is invoked by javac, the annotations have `SOURCE` retention so nothing's in the consumer's `.class` files, and the helper code lives inside the consumer's own `<moduleName>.internal` package. Plain `requires` would also compile but would bloat the runtime module graph for no benefit. See "Consumer's `module-info.java`" in the new "Module integration" section.
- [x] **Generated helper package.** Default-unexported; no user `module-info` change needed. Processor emits a `package-info.java` with a `// Generated by org.lattejava.json — do not edit` notice as an in-source signpost. See "Generated package handling" in the new "Module integration" section.

### 12. Thread safety contract

- [x] **Thread safety.** Static `*JSON.fromJSON`, `*JSON.toJSON`, and `*JSON.toJSONBytes` are thread-safe — each call allocates its own parser/builder and observer instances. Observer instances themselves (`new UserJSON()`) are per-call and not thread-safe. Stateless singletons (`SkipObserver.INSTANCE`, `SkipArrayObserver.INSTANCE`) are thread-safe by construction; `AnyObjectObserver` and `AnyArrayObserver` are per-call, not singletons. See "Thread safety" section.