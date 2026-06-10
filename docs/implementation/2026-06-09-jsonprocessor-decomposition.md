# JSONProcessor Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 826-line `JSONProcessor` into focused collaborators — a static facts util, a class-member discoverer, a helper emitter, a writer hierarchy, and a validator hierarchy — with `JSONProcessor` reduced to `init()` wiring + per-element dispatch.

**Architecture:** A pure, behavior-preserving refactor. Methods *move* to new classes in a new non-exported package `org.lattejava.json.processor`; `JSONProcessor` builds each collaborator once in `init()` and delegates. No template, runtime-helper, annotation, or generated-output change.

**Tech Stack:** Java 25 annotation processor (`javax.annotation.processing`), JTE templates, Latte build (`latte clean && latte test`), TestNG via the real-`javac` `ProcessorHarness`.

**Spec:** `docs/design/2026-06-09-jsonprocessor-decomposition-design.md`

---

## Notes (read first — this is a REFACTOR, not feature work)

- **The gate is "the 265-test suite stays green AND generated companions are byte-identical."** There are NO new tests — the tests already drive the processor end-to-end through `javac` (`ProcessorHarness`) and assert `toJSON`/`fromJSON` behavior + some generated source. A green suite ≈ behavior preserved.
- **`latte clean` before EVERY `latte test`** — this branch's incremental compile leaves stale descriptors (`NoSuchMethodError`) when class shapes change. ALWAYS `latte clean && latte test`.
- **New package: `org.lattejava.json.processor`** (mirrors the non-exported, build-time `org.lattejava.json.jte`). **No `module-info.java` change** — internal public classes are usable within the module without `exports`.
- **Naming landmine:** `project.latte:60` copies `src/main/java/org/lattejava/json/` files matching `/Any.*/ /Conversion.*/ /JSON.*/ /Numbers.*/ /Skip.*/` into consumers' runtime `internal` package. New classes live in the `processor` **subdirectory** and **must not** start with those prefixes (hence `ProcessorFacts`, not `JSONFacts`). All other proposed names (`CompanionWriter`, `RecordValidator`, …) are clear.
- **Moved methods become `public`** (called across packages from `JSONProcessor` / sibling collaborators). Each collaborator (except the static `ProcessorFacts`) takes `ProcessingEnvironment processingEnv` in its constructor and stores it as a `private final` field.
- **New classes need imports** (they're in a different package than the annotations): `import module java.base; import module java.compiler;` plus explicit `import org.lattejava.json.<Annotation>;` / `import org.lattejava.json.jte.<View>;` as used. Each file carries the SPDX header.
- **Byte-identity check (do this once per task that touches generation — Tasks 4–6):** `latte clean && latte build` then `git stash` is overkill; simplest is `find build/test/generated -name '*JSON.java' | sort | xargs cat | sha256sum` before and after, or just trust the suite (the codegen tests assert output). The plan calls out where to run it.

---

## File Structure (all new files in `src/main/java/org/lattejava/json/processor/`)

- `ProcessorFacts.java` — static: `naming`/`omitNulls`/`strict`/`discriminatorValue`/`discriminatorInterface`/`asTypeElement`/`qualified`.
- `ClassMemberDiscovery.java` — `isBean`/`jsonConstructors`/`resolveRead`/`discoverProperties` + the bean-discovery internals + the `BeanProperty` record.
- `HelperEmitter.java` — `HELPERS` + `helpersEmitted` + `emit`.
- `AbstractWriter.java` — `writeSource`/`internalPackageOf`.
- `CompanionWriter.java extends AbstractWriter` — the `companion.jte` shell + `collectEnums`.
- `PolymorphicWriter.java extends AbstractWriter` — the `polymorphic.jte` shell.
- `AbstractValidator.java` — `error`/`validateMembers`/`validateType`/`validatePolicy`/`isSupportedComponentType`/`notJSON`/`requireDiscriminatorInterface`.
- `RecordValidator.java extends AbstractValidator`, `ClassValidator.java extends AbstractValidator`, `PolymorphicValidator.java extends AbstractValidator`.
- `JSONProcessor.java` (modified) — `init()` + `process()` dispatch only.

**Gate every task:** `latte clean && latte test` → **265, 0 failures**.

---

## Task 1: Extract `ProcessorFacts` (static pure reads)

**Files:**
- Create: `src/main/java/org/lattejava/json/processor/ProcessorFacts.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Create `ProcessorFacts`**

Move these `JSONProcessor` methods in **verbatim** as `public static`, renamed per the table; they are pure (no `processingEnv`):

| From `JSONProcessor` | `ProcessorFacts` static method |
|---|---|
| `readNaming(TypeElement)` | `naming(TypeElement)` |
| `readOmitNulls(TypeElement)` | `omitNulls(TypeElement)` |
| `readStrict(TypeElement)` | `strict(TypeElement)` |
| `discriminatorValueOf(TypeElement)` | `discriminatorValue(TypeElement)` |
| `asTypeElement(TypeMirror)` | `asTypeElement(TypeMirror)` |
| `qualified(Element)` | `qualified(Element)` |

Plus one **new** helper (extracted from the discriminator scan at `generateCompanion` lines 171–178) so the writer and validator agree on "is this a polymorphic subtype":

```java
  /** The implemented {@code @JSONTypeInfo} interface (the polymorphic parent), or {@code null}. */
  public static TypeElement discriminatorInterface(TypeElement type) {
    for (TypeMirror itf : type.getInterfaces()) {
      TypeElement element = asTypeElement(itf);
      if (element.getAnnotation(JSONTypeInfo.class) != null) {
        return element;
      }
    }
    return null;
  }
```

File skeleton:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSON;
import org.lattejava.json.JSONSubtype;
import org.lattejava.json.JSONTypeInfo;
import org.lattejava.json.NamingStrategy;

/** Pure reads of {@code @JSON}-family annotation facts and element conversions, shared by validators and writers. */
public final class ProcessorFacts {
  private ProcessorFacts() {
  }

  // ... the six moved methods (public static) + discriminatorInterface ...
}
```

(`naming` returns `ann == null ? NamingStrategy.IDENTITY : ann.naming()`, etc. — identical bodies to the originals.)

- [ ] **Step 2: Delegate from `JSONProcessor`**

In `JSONProcessor.java`: delete the six moved methods. Replace every call:
- `readNaming(t)` → `ProcessorFacts.naming(t)` (call sites: `generateCompanion`, `validateMembers`, `validatePolymorphic`, `validateBean`)
- `readOmitNulls(t)` / `readStrict(t)` → `ProcessorFacts.omitNulls(t)` / `ProcessorFacts.strict(t)` (in `generateCompanion`)
- `discriminatorValueOf(t)` → `ProcessorFacts.discriminatorValue(t)` (in `generateCompanion`, `generatePolymorphic`, `validatePolymorphic`)
- `asTypeElement(t)` → `ProcessorFacts.asTypeElement(t)` (in `generateCompanion`, `generatePolymorphic`, `validatePolymorphic`)
- `qualified(e)` → `ProcessorFacts.qualified(e)` (in `process`)

Add `import org.lattejava.json.processor.ProcessorFacts;` to `JSONProcessor`.

- [ ] **Step 3: Verify**

Run: `latte clean && latte test`
Expected: **265, 0 failures**. Pure code relocation — no behavior change.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/json/processor/ProcessorFacts.java src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract ProcessorFacts (pure @JSON-fact reads) from JSONProcessor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Extract `ClassMemberDiscovery` + introduce `init()`

**Files:**
- Create: `src/main/java/org/lattejava/json/processor/ClassMemberDiscovery.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Create `ClassMemberDiscovery`**

Move these `JSONProcessor` members verbatim (make `public` where called externally; keep the bean-discovery internals `private`), into a class holding `private final ProcessingEnvironment processingEnv`:

- `public boolean isBean(TypeElement)`
- `public List<ExecutableElement> jsonConstructors(TypeElement)`
- `public String resolveRead(TypeElement, VariableElement)`
- `public List<BeanProperty> discoverProperties(TypeElement)`
- private: `beanProperty`, `accessorProperty`, `configElement`, `superclassChain`, `capitalize`, `decapitalize`
- `public record BeanProperty(String name, TypeMirror type, Element config, Element at, String read, String write, boolean writeSetter)`

Bodies are verbatim except: anywhere they currently use `processingEnv` (e.g. `processingEnv.getElementUtils().getAllMembers(...)`), it now refers to the new class's field. Keep the fully-qualified `javax.lang.model.element.Modifier`/`javax.lang.model.type.DeclaredType`/`javax.lang.model.util.ElementFilter`.

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONConstructor;
import org.lattejava.json.JSONField;

/** Resolves a class's members: {@code @JSONConstructor} parameters or JavaBean properties. Stateless query object. */
public final class ClassMemberDiscovery {
  private final ProcessingEnvironment processingEnv;

  public ClassMemberDiscovery(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  // ... the moved methods + BeanProperty ...
}
```

- [ ] **Step 2: Introduce `init()` and delegate**

In `JSONProcessor.java`, add the field + `init()` override (the first collaborator instance):

```java
  private ClassMemberDiscovery members;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.members = new ClassMemberDiscovery(processingEnv);
  }
```

Delete the moved methods from `JSONProcessor`; replace calls — `isBean(t)`→`members.isBean(t)`, `jsonConstructors(t)`→`members.jsonConstructors(t)`, `resolveRead(t,p)`→`members.resolveRead(t,p)`, `discoverProperties(t)`→`members.discoverProperties(t)`, and `BeanProperty`→`ClassMemberDiscovery.BeanProperty` (in `generateCompanion`, `validateBean`). Add `import org.lattejava.json.processor.ClassMemberDiscovery;`.

- [ ] **Step 3: Verify, then commit**

Run: `latte clean && latte test` → **265, 0 failures**.

```bash
git add src/main/java/org/lattejava/json/processor/ClassMemberDiscovery.java src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract ClassMemberDiscovery; build collaborators in init()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Extract `HelperEmitter` (owns its state)

**Files:**
- Create: `src/main/java/org/lattejava/json/processor/HelperEmitter.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Create `HelperEmitter`**

Move `HELPERS` (the `List.of(...)`), the `helpersEmitted` boolean, and `emitHelpers` into it. The once-per-round emission logic (currently `process` lines 51–57) moves in too, as a public `emit(ModuleElement)` that self-guards:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

/** Copies the canonical runtime-helper sources into {@code <module>.internal}, once per compilation. Owns its state. */
public final class HelperEmitter {
  public static final List<String> HELPERS = List.of(
      "AnyArrayObserver", "AnyObjectObserver", "Conversions", "JSONArrayBuilder",
      "JSONArrayObserver", "JSONBuilder", "JSONObjectHandler", "JSONObserver",
      "JSONParser", "JSONPolymorphicObserver", "JSONProcessingException", "Numbers",
      "SkipArrayObserver", "SkipObserver");
  private final ProcessingEnvironment processingEnv;
  private boolean emitted = false;

  public HelperEmitter(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  /** Emits the helpers into {@code module}'s {@code .internal} once; subsequent calls no-op. */
  public void emit(ModuleElement module) {
    if (emitted || module == null || module.isUnnamed()) {
      return;
    }
    // ... the body of the old emitHelpers(module), using this.processingEnv ...
    emitted = true;
  }
}
```

(Keep `emitHelpers`'s `getResourceAsStream`/`createSourceFile`/package-rewrite body verbatim inside `emit`, after the guard. `HelperEmissionTest` exercises this end-to-end.)

- [ ] **Step 2: Delegate from `JSONProcessor`**

Delete `HELPERS`, `helpersEmitted`, `emitHelpers`. Add `private HelperEmitter helperEmitter;`, create it in `init()` (`this.helperEmitter = new HelperEmitter(processingEnv);`). Replace the `process()` emission block (lines 51–57) with:

```java
    if (!annotated.isEmpty()) {
      helperEmitter.emit(processingEnv.getElementUtils().getModuleOf(annotated.iterator().next()));
    }
```

If any code referenced `JSONProcessor.HELPERS` (check `HelperEmissionTest`), repoint it to `HelperEmitter.HELPERS`. Add the import.

- [ ] **Step 3: Verify, then commit**

Run: `latte clean && latte test` → **265, 0 failures** (`HelperEmissionTest` is the key guard here).

```bash
git add src/main/java/org/lattejava/json/processor/HelperEmitter.java src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract HelperEmitter owning the once-per-round emit state

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Extract the writer hierarchy

**Files:**
- Create: `AbstractWriter.java`, `CompanionWriter.java`, `PolymorphicWriter.java` (in `processor/`)
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: `AbstractWriter`**

Holds `processingEnv`, `internalPackageOf` (moved verbatim, made `protected`), and a new `writeSource` extracted from the **two identical** `try { Filer … }` blocks in `generateCompanion` (lines 191–200) and `generatePolymorphic` (lines 224–233):

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

/** Base for collaborators that write a generated source file into {@code <typePackage>.internal}. */
public abstract class AbstractWriter {
  protected final ProcessingEnvironment processingEnv;

  protected AbstractWriter(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  /** The {@code <typePackage>.internal} package (or bare {@code internal} in the unnamed package). */
  protected String internalPackageOf(Element element) {
    String pkg = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
    return pkg.isEmpty() ? "internal" : pkg + ".internal";
  }

  /** Writes {@code source} to {@code companionPackage.name}, attributing diagnostics to {@code origin}. */
  protected void writeSource(String companionPackage, String name, String source, Element origin) {
    try {
      var file = processingEnv.getFiler().createSourceFile(companionPackage + "." + name, origin);
      try (Writer w = file.openWriter()) {
        w.write(source);
      }
    } catch (IOException ioe) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed writing companion [" + companionPackage + "." + name + "]: " + ioe.getMessage(), origin);
    }
  }
}
```

- [ ] **Step 2: `CompanionWriter extends AbstractWriter`**

Move `generateCompanion` in as `public void write(TypeElement type, ModuleElement module)` and `collectEnums` in as `private`. Constructor `CompanionWriter(ProcessingEnvironment processingEnv, ClassMemberDiscovery members)` (stores `members`). Body changes from the original `generateCompanion`:
- member-discovery branch uses `members.isBean(...)`, `members.discoverProperties(...)`, `members.jsonConstructors(...)`, `members.resolveRead(...)`.
- `readNaming/readOmitNulls/readStrict` → `ProcessorFacts.naming/omitNulls/strict`.
- the discriminator scan (lines 171–178) becomes `TypeElement itf = ProcessorFacts.discriminatorInterface(type); if (itf != null) { discriminatorKey = itf.getAnnotation(JSONTypeInfo.class).property(); discriminatorValue = ProcessorFacts.discriminatorValue(type); }`.
- **KEEP** the `@JSONSubtype`-without-interface check (lines 180–184) for now — Task 5 relocates it to the validators and deletes it here, but in isolation Task 4 still needs it green (`PolyRejectionTest.orphanSubtypeRejected` / `badpoly_orphan`). Since `CompanionWriter` has no `error()` method, emit it directly: `if (discriminatorKey.isEmpty() && type.getAnnotation(JSONSubtype.class) != null) { processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@JSONSubtype on [" + type.getQualifiedName() + "] requires an implemented @JSONTypeInfo interface", type); return; }`. (This is a transient wart removed in Task 5.) Add `import org.lattejava.json.JSONSubtype;`.
- the `try { Filer … }` tail → `writeSource(companionPkg, companion, source, type)`.

- [ ] **Step 3: `PolymorphicWriter extends AbstractWriter`**

Move `generatePolymorphic` in as `public void write(TypeElement iface, ModuleElement module)`; `asTypeElement`/`discriminatorValueOf`→`ProcessorFacts.*`; the `try` tail → `writeSource(...)`.

- [ ] **Step 4: Delegate from `JSONProcessor`**

Delete `generateCompanion`, `generatePolymorphic`, `collectEnums`, `internalPackageOf`. Add fields + `init()` construction:

```java
    this.companionWriter = new CompanionWriter(processingEnv, members);
    this.polymorphicWriter = new PolymorphicWriter(processingEnv);
```

In `process()`, replace `generateCompanion(type, module)` → `companionWriter.write(type, module)` and `generatePolymorphic(type, module)` → `polymorphicWriter.write(type, module)`. Add imports.

- [ ] **Step 5: Verify (byte-identity)**

Run: `latte clean && latte test` → **265, 0 failures**.
Then confirm generation is byte-identical: `find build/test/generated -name '*JSON.java' | sort | xargs cat | sha256sum` and compare to the same command run at `HEAD~1` (stash/worktree, or eyeball a couple of `find build/test/generated -name 'UserJSON.java' -exec cat {} \;`). The companion + polymorphic output must be unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/processor/AbstractWriter.java \
        src/main/java/org/lattejava/json/processor/CompanionWriter.java \
        src/main/java/org/lattejava/json/processor/PolymorphicWriter.java \
        src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract AbstractWriter + CompanionWriter + PolymorphicWriter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Extract the validator hierarchy

**Files:**
- Create: `AbstractValidator.java`, `RecordValidator.java`, `ClassValidator.java`, `PolymorphicValidator.java` (in `processor/`)
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: `AbstractValidator`**

Holds `processingEnv` + the shared validation primitives, moved verbatim (made `protected`): `error`, `validateMembers`, `validateType`, `validatePolicy`, `isSupportedComponentType`, `notJSON`. Inside them, `readNaming`→`ProcessorFacts.naming`. Plus a new shared check extracted from the writer:

```java
  /** Rejects a record/class carrying {@code @JSONSubtype} without an implemented {@code @JSONTypeInfo} interface. */
  protected boolean requireDiscriminatorInterface(TypeElement type) {
    if (type.getAnnotation(JSONSubtype.class) != null && ProcessorFacts.discriminatorInterface(type) == null) {
      error(type, "@JSONSubtype on [" + type.getQualifiedName() + "] requires an implemented @JSONTypeInfo interface");
      return false;
    }
    return true;
  }
```

Skeleton: `public abstract class AbstractValidator { protected final ProcessingEnvironment processingEnv; protected AbstractValidator(ProcessingEnvironment pe) { this.processingEnv = pe; } … }`.

**Also delete the transient `@JSONSubtype` check from `CompanionWriter.write`** (the `if (discriminatorKey.isEmpty() && type.getAnnotation(JSONSubtype.class) != null) { … printMessage … return; }` block Task 4 kept) — it now lives in `requireDiscriminatorInterface`, called by `RecordValidator`/`ClassValidator` BEFORE `companionWriter.write(...)` is reached. Remove the now-unused `import org.lattejava.json.JSONSubtype;` from `CompanionWriter`. The diagnostic message + outcome are byte-identical; only the phase (validate vs. generate) changes.

- [ ] **Step 2: `RecordValidator extends AbstractValidator`**

`RecordValidator` needs the `ClassMemberDiscovery` instance (for `jsonConstructors`), so its constructor is `RecordValidator(ProcessingEnvironment, ClassMemberDiscovery members)`. It also absorbs the `@JSONConstructor`-on-record redundancy check (currently `process` lines 86–90).

**Order matters for byte-identical diagnostics.** Originally: the `@JSONConstructor`-on-record check ran in `process()` (and `continue`d), then `validateMembers`, then — only if generation was reached — the `@JSONSubtype` check inside `generateCompanion`. Preserve that exact short-circuit order: redundancy check → `validateMembers` → `requireDiscriminatorInterface`:

```java
  public boolean validate(TypeElement type) {
    if (!members.jsonConstructors(type).isEmpty()) {
      error(type, "@JSONConstructor on record [" + type.getQualifiedName()
          + "] is redundant; records use their canonical constructor");
      return false;
    }
    if (!validateMembers(type, type.getRecordComponents())) {
      return false;
    }
    return requireDiscriminatorInterface(type);
  }
```

- [ ] **Step 3: `ClassValidator extends AbstractValidator`**

Constructor `ClassValidator(ProcessingEnvironment, ClassMemberDiscovery members)`. Move `validateClass` + `validateBean` in (verbatim bodies; `readNaming`→`ProcessorFacts.naming`, `jsonConstructors`/`resolveRead`/`discoverProperties`→`members.*`, `BeanProperty`→`ClassMemberDiscovery.BeanProperty`). Add the public entry point — **member validation first, then the `@JSONSubtype` check** (same short-circuit order as before: the class validation ran in `process()`, the `@JSONSubtype` check in `generateCompanion` only after it passed):

```java
  public boolean validate(TypeElement type) {
    boolean ok = members.isBean(type) ? validateBean(type) : validateClass(type);
    return ok && requireDiscriminatorInterface(type);
  }
```

- [ ] **Step 4: `PolymorphicValidator extends AbstractValidator`**

Move `validatePolymorphic` in as `public boolean validate(TypeElement iface)`; `discriminatorValueOf`/`asTypeElement`→`ProcessorFacts.*`, `readNaming`→`ProcessorFacts.naming`, `Component.wireKey` stays.

- [ ] **Step 5: Delegate from `JSONProcessor` + remove the moved guards**

Delete `validateClass`, `validateBean`, `validatePolymorphic`, `validateMembers`, `validateType`, `validatePolicy`, `isSupportedComponentType`, `notJSON`, `error`, and the inline `@JSONConstructor`-on-record block (now in `RecordValidator`). Add fields + `init()` construction (`recordValidator = new RecordValidator(processingEnv, members)`, `classValidator = new ClassValidator(processingEnv, members)`, `polymorphicValidator = new PolymorphicValidator(processingEnv)`). Rewrite the `process()` body after the kind/module guards:

```java
      if (polyParent) {
        if (polymorphicValidator.validate(type)) {
          polymorphicWriter.write(type, module);
        }
        continue;
      }
      var validator = type.getKind() == ElementKind.CLASS ? classValidator : recordValidator;
      if (validator.validate(type)) {
        companionWriter.write(type, module);
      }
```

Add imports.

- [ ] **Step 6: Verify (byte-identity), then commit**

Run: `latte clean && latte test` → **265, 0 failures**. Re-run the byte-identity check from Task 4 Step 5 — diagnostics for the rejection fixtures (`badpoly_*`, `badclass_*`, `badbean_*`, `badtype`, etc.) must be unchanged (the `@JSONSubtype` check now fires in the validator, but the message + outcome are identical). All rejection tests stay green.

```bash
git add src/main/java/org/lattejava/json/processor/AbstractValidator.java \
        src/main/java/org/lattejava/json/processor/RecordValidator.java \
        src/main/java/org/lattejava/json/processor/ClassValidator.java \
        src/main/java/org/lattejava/json/processor/PolymorphicValidator.java \
        src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: extract validator hierarchy; relocate @JSONSubtype check to validators

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Final tidy + verification

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`

- [ ] **Step 1: Confirm `JSONProcessor` is thin**

`JSONProcessor` should now contain only: the `@SupportedAnnotationTypes`/`@SupportedSourceVersion` annotations, the collaborator fields, `init()`, and `process()` (the round-level guards + the dispatch table). Verify no orphaned imports remain (remove any unused `import org.lattejava.json.jte.*` / `java.compiler` types no longer referenced — e.g. `Component`, `CompanionView`, `PolymorphicView`, `TypeView`, `JTEEngine` are now used only by the collaborators). Update the class Javadoc to describe the dispatch role (it currently describes generation details that now live in `CompanionWriter`).

- [ ] **Step 2: Full verify + byte-identity**

Run: `latte clean && latte test` → **265, 0 failures**.
Final byte-identity: regenerate and confirm the generated tree hash matches the pre-refactor (Task 1 parent) hash:
```
git stash; git checkout <pre-refactor-sha> -- . 2>/dev/null  # OR a clean worktree
```
Simpler in practice: the full green suite (which includes `ScaffoldingIndentationTest` asserting generated source shape, the codegen round-trip tests, and `HelperEmissionTest`) is the proof. Confirm the count is exactly 265/0 with zero skips.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: reduce JSONProcessor to init() wiring + dispatch

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §Structure (two hierarchies + shared collaborators) → Tasks 1–5; thin processor → Task 6.
- §"What moves where" table → each row maps to a Task (facts→T1, discovery→T2, emitter→T3, writers→T4, validators→T5).
- §"Behavior-affecting relocations": `@JSONSubtype` check → validators (T5); `writeSource` dedup (T4); `discriminatorInterface` extraction (T1, used T4+T5).
- §"NOT changed": `validateMembers`/`validateBean` duplication left intact (moved as-is, T5); `@JSONConstructor`+bean stay together in `ClassValidator`/`CompanionWriter` (T5/T4); no template/runtime/annotation change.
- §Migration order matches Tasks 1–6.
- §Risks: `init()`-not-constructor (T2); helper-copy naming (`ProcessorFacts`, `processor` subpackage — Notes); cross-package visibility (moved methods `public`).

**Placeholder scan:** the bulk-move steps reference existing method bodies by name + line + the exact minimal edits (`readNaming`→`ProcessorFacts.naming`, etc.) rather than re-pasting hundreds of unchanged lines — appropriate for a pure relocation; the new skeletons, `writeSource`, `discriminatorInterface`, `requireDiscriminatorInterface`, `emit`, and every `process()`/`init()` rewrite are shown in full.

**Type consistency:** `ProcessorFacts.naming/omitNulls/strict/discriminatorValue/discriminatorInterface/asTypeElement/qualified` (T1) used in T4/T5. `ClassMemberDiscovery` + `.BeanProperty` (T2) injected into `CompanionWriter` (T4) and `RecordValidator`/`ClassValidator` (T5). `HelperEmitter.HELPERS`/`emit` (T3). `AbstractWriter.writeSource`/`internalPackageOf` (T4) used by both writers. `AbstractValidator.error`/`validateMembers`/`validateType`/`validatePolicy`/`isSupportedComponentType`/`notJSON`/`requireDiscriminatorInterface` (T5) used by the three validators. Collaborator fields all built in `init()` (T2 introduces it; T3–T5 add to it). Test count: 265 unchanged at every task (refactor — no new tests).
