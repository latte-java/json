# Plan 2: Annotation Processor + Simple Records Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a working annotation processor that, for any `@JSON`-annotated **record** whose components are primitives, boxed primitives, or `String`, generates a `<typePackage>.internal.<Name>JSON` companion with working `fromJSON`/`toJSON`/`toJSONBytes`, plus emits the runtime helper set into the consumer module's `<moduleName>.internal` package.

**Architecture:** `JSONProcessor extends AbstractProcessor` runs in javac. It (1) emits the 12 runtime helper classes — kept as verbatim text templates under `src/main/resources/` — into `<consumerModule>.internal` with the `package` line rewritten, and (2) generates a per-record observer companion in `<typePackage>.internal`. Because the Latte `java` plugin has no annotation-processing support (see `docs/superpowers/plans/`-referenced constraint), the processor is verified by a TestNG test that drives the `javax.tools.JavaCompiler` API directly against checked-in fixture sources under `src/test/resources/`, compiling to `build/test/generated/` and class-loading the result. Reflection appears **only** in the test harness; the processor and generated code use none.

**Tech Stack:** Java 25, `javax.annotation.processing`, `javax.lang.model`, `javax.tools.JavaCompiler`, TestNG 7.10.2, Latte build (`latte build`, `latte test`, `latte test --test=<Class>`). Module imports (`import module java.base;`).

**Spec:** `docs/design/2026-05-12-serialization.md` (authoritative). Conventions: SPDX header `Copyright (c) 2026 The Latte Project` single year; `@author Brian Pontarelli`; `import module java.base;` only (never explicit `import java.*;`); error/diagnostic runtime values in `[brackets]`; members alphabetized within visibility groups; no blank lines between fields. These conventions also apply to **emitted/generated code**.

**Scope guardrails (Plan 2 only):**
- Records only. A `@JSON` class (non-record) is a compile-time error in Plan 2 (full class support is Plan 6).
- Component types allowed: `boolean byte short int long float double` + boxed wrappers + `String`. Any other component type is a compile-time error in Plan 2 (extras = Plan 3, collections = Plan 4, etc.).
- `@JSON(strict=...)` and unknown-key handling are implemented (lenient default + strict). `@JSONField`, `@JSONCatchAll`, `@JSONTypeInfo`, naming strategies, polymorphism codegen are OUT (later plans) — but the processor must not crash on them; it ignores `@JSONField`/naming in Plan 2 and treats their presence as "not yet supported" only if it would change output (documented per task).
- `omitNulls` default (true) is honored for serialization of `String`/boxed nulls.

---

## File Map

### Create — main resources (helper templates, verbatim copies)
Under `src/main/resources/org/lattejava/json/internal-templates/`, one `<Name>.java.txt` per helper, **byte-identical** to the canonical `src/main/java/org/lattejava/json/<Name>.java` (including the `package org.lattejava.json;` line — the processor rewrites only that line at emit time):
- `JSONProcessingException.java.txt`
- `JSONObjectHandler.java.txt`
- `JSONObserver.java.txt`
- `JSONArrayObserver.java.txt`
- `JSONPolymorphicObserver.java.txt`
- `JSONParser.java.txt`
- `JSONBuilder.java.txt`
- `Numbers.java.txt`
- `SkipObserver.java.txt`
- `SkipArrayObserver.java.txt`
- `AnyObjectObserver.java.txt`
- `AnyArrayObserver.java.txt`

### Create — main source
- `src/main/java/org/lattejava/json/JSONProcessor.java` (the annotation processor)
- `src/main/resources/META-INF/services/javax.annotation.processing.Processor` (one line: `org.lattejava.json.JSONProcessor`) — for real-consumer discovery; the test attaches the processor explicitly so it does not depend on this.

### Create — test source
- `src/test/java/org/lattejava/json/tests/processor/HelperTemplateDriftTest.java`
- `src/test/java/org/lattejava/json/tests/processor/ProcessorHarness.java` (shared compile-API driver)
- `src/test/java/org/lattejava/json/tests/processor/HelperEmissionTest.java`
- `src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java`
- `src/test/java/org/lattejava/json/tests/processor/UnknownKeyPolicyTest.java`
- `src/test/java/org/lattejava/json/tests/processor/ProcessorErrorsTest.java`

### Create — test fixtures (NOT under src/test/java; compiled only by the harness)
- `src/test/resources/fixtures/simple/module-info.java`
- `src/test/resources/fixtures/simple/demo/User.java`
- `src/test/resources/fixtures/simple/demo/Primitives.java`
- `src/test/resources/fixtures/strict/module-info.java`
- `src/test/resources/fixtures/strict/demo/StrictUser.java`
- `src/test/resources/fixtures/badtype/module-info.java`
- `src/test/resources/fixtures/badtype/demo/HasList.java`
- `src/test/resources/fixtures/nomodule/demo/NoModule.java`
- `src/test/resources/fixtures/notarecord/module-info.java`
- `src/test/resources/fixtures/notarecord/demo/NotARecord.java`

### Modify
- `src/test/java/module-info.java` — add `opens org.lattejava.json.tests.processor to org.testng;`

---

## Task 0: Helper templates as resources + drift guard

The processor emits the runtime helpers by reading verbatim text templates off its classpath. The templates must never silently diverge from the canonical compiled helpers, so a guard test asserts byte-equality.

**Files:**
- Create: `src/main/resources/org/lattejava/json/internal-templates/<Name>.java.txt` (12 files)
- Test: `src/test/java/org/lattejava/json/tests/processor/HelperTemplateDriftTest.java`
- Modify: `src/test/java/module-info.java`

- [ ] **Step 1: Add the test-module `opens` for the new test package**

Edit `src/test/java/module-info.java` — add one `opens` line so TestNG can reflect the new package. Final content:

```java
/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json.tests {
  requires org.lattejava.json;
  requires org.testng;

  opens org.lattejava.json.tests to org.testng;
  opens org.lattejava.json.tests.processor to org.testng;
}
```

- [ ] **Step 2: Write the failing drift-guard test**

`src/test/java/org/lattejava/json/tests/processor/HelperTemplateDriftTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class HelperTemplateDriftTest {
  private static final List<String> HELPERS = List.of(
      "JSONProcessingException", "JSONObjectHandler", "JSONObserver",
      "JSONArrayObserver", "JSONPolymorphicObserver", "JSONParser",
      "JSONBuilder", "Numbers", "SkipObserver", "SkipArrayObserver",
      "AnyObjectObserver", "AnyArrayObserver");

  @Test
  public void everyHelperHasATemplate() {
    for (String name : HELPERS) {
      Path tmpl = Path.of("src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt");
      assertTrue(Files.exists(tmpl), "Missing template for [" + name + "] at [" + tmpl + "]");
    }
  }

  @Test
  public void templateIsByteIdenticalToCanonicalSource() throws Exception {
    for (String name : HELPERS) {
      String canonical = Files.readString(
          Path.of("src/main/java/org/lattejava/json/" + name + ".java"), StandardCharsets.UTF_8);
      String template = Files.readString(
          Path.of("src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt"),
          StandardCharsets.UTF_8);
      assertEquals(template, canonical,
          "Template for [" + name + "] has drifted from the canonical source; "
          + "regenerate src/main/resources/org/lattejava/json/internal-templates/" + name + ".java.txt");
    }
  }

  @Test
  public void canonicalSourcesDeclareTheRewritablePackage() throws Exception {
    for (String name : HELPERS) {
      String canonical = Files.readString(
          Path.of("src/main/java/org/lattejava/json/" + name + ".java"), StandardCharsets.UTF_8);
      assertTrue(canonical.contains("\npackage org.lattejava.json;\n"),
          "[" + name + "] must contain exactly the line 'package org.lattejava.json;' "
          + "for the processor to rewrite it");
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=HelperTemplateDriftTest`
Expected: FAIL — `everyHelperHasATemplate` fails because the templates don't exist yet.

- [ ] **Step 4: Create the 12 templates as exact copies**

Run this exact shell sequence from the project root (it copies each canonical helper verbatim to its `.java.txt` template):

```bash
mkdir -p src/main/resources/org/lattejava/json/internal-templates
for n in JSONProcessingException JSONObjectHandler JSONObserver JSONArrayObserver \
         JSONPolymorphicObserver JSONParser JSONBuilder Numbers SkipObserver \
         SkipArrayObserver AnyObjectObserver AnyArrayObserver; do
  cp "src/main/java/org/lattejava/json/$n.java" \
     "src/main/resources/org/lattejava/json/internal-templates/$n.java.txt"
done
ls src/main/resources/org/lattejava/json/internal-templates/
```

Expected `ls` output: the 12 `.java.txt` files.

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=HelperTemplateDriftTest`
Expected: PASS, 3 tests run. (`canonicalSourcesDeclareTheRewritablePackage` passes because every helper has `package org.lattejava.json;` on its own line from Plan 1.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/org/lattejava/json/internal-templates/ \
        src/test/java/org/lattejava/json/tests/processor/HelperTemplateDriftTest.java \
        src/test/java/module-info.java
git commit -m "feat: add runtime-helper templates as resources with drift guard"
```

---

## Task 1: Test fixtures

Checked-in fixture sources the harness compiles. They are under `src/test/resources/` so Latte's normal test compile never touches them.

**Files:** the ten fixture files listed in the File Map.

- [ ] **Step 1: Create the `simple` fixture set**

`src/test/resources/fixtures/simple/module-info.java`:

```java
module demo.simple {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/simple/demo/User.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record User(String name, int age, String email) {
}
```

`src/test/resources/fixtures/simple/demo/Primitives.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public record Primitives(boolean flag, byte b, short s, int i, long l,
                         float f, double d, Integer boxedInt, Long boxedLong) {
}
```

- [ ] **Step 2: Create the `strict` fixture set**

`src/test/resources/fixtures/strict/module-info.java`:

```java
module demo.strict {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/strict/demo/StrictUser.java`:

```java
package demo;

import module org.lattejava.json;

@JSON(strict = true)
public record StrictUser(String name, int age) {
}
```

- [ ] **Step 3: Create the negative fixtures**

`src/test/resources/fixtures/badtype/module-info.java`:

```java
module demo.badtype {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/badtype/demo/HasList.java`:

```java
package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public record HasList(String name, List<String> tags) {
}
```

`src/test/resources/fixtures/nomodule/demo/NoModule.java` (intentionally no `module-info.java` in this set):

```java
package demo;

import module org.lattejava.json;

@JSON
public record NoModule(String name) {
}
```

`src/test/resources/fixtures/notarecord/module-info.java`:

```java
module demo.notarecord {
  requires static org.lattejava.json;
}
```

`src/test/resources/fixtures/notarecord/demo/NotARecord.java`:

```java
package demo;

import module org.lattejava.json;

@JSON
public class NotARecord {
  private String name;
}
```

- [ ] **Step 4: Verify the project still builds (fixtures are inert resources)**

Run: `latte build`
Expected: BUILD SUCCEEDED. The fixture `.java` files under `src/test/resources/` are NOT compiled by Latte (they are resources, not source).

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/fixtures/
git commit -m "test: add @JSON compile fixtures for processor tests"
```

---

## Task 2: Processor scaffold + the compile-API harness

Stand up `JSONProcessor` doing validation + diagnostics only (no codegen yet), and the reusable `ProcessorHarness` that compiles a fixture set with the processor attached.

**Files:**
- Create: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Create: `src/main/resources/META-INF/services/javax.annotation.processing.Processor`
- Create: `src/test/java/org/lattejava/json/tests/processor/ProcessorHarness.java`
- Create: `src/test/java/org/lattejava/json/tests/processor/ProcessorErrorsTest.java`

- [ ] **Step 1: Write the failing error-policy test**

`src/test/java/org/lattejava/json/tests/processor/ProcessorErrorsTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class ProcessorErrorsTest {
  @Test
  public void nonRecordIsRejected() throws Exception {
    var result = ProcessorHarness.compile("notarecord");
    assertFalse(result.success(), "compilation must fail for a non-record @JSON type");
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("only records") && d.contains("[demo.NotARecord]")),
        "expected a 'only records' error mentioning [demo.NotARecord], got: " + result.diagnostics());
  }

  @Test
  public void unsupportedComponentTypeIsRejected() throws Exception {
    var result = ProcessorHarness.compile("badtype");
    assertFalse(result.success());
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("unsupported") && d.contains("tags")),
        "expected an unsupported-type error mentioning [tags], got: " + result.diagnostics());
  }

  @Test
  public void missingModuleIsRejected() throws Exception {
    var result = ProcessorHarness.compile("nomodule");
    assertFalse(result.success());
    assertTrue(result.diagnostics().stream()
            .anyMatch(d -> d.contains("named module")),
        "expected a named-module error, got: " + result.diagnostics());
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `latte test --test=ProcessorErrorsTest`
Expected: FAIL — `ProcessorHarness` and `JSONProcessor` don't exist (compile error).

- [ ] **Step 3: Write the `ProcessorHarness`**

`src/test/java/org/lattejava/json/tests/processor/ProcessorHarness.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.lattejava.json;

/**
 * Compiles a fixture set under src/test/resources/fixtures/&lt;name&gt; with {@link JSONProcessor}
 * attached, writing generated sources and classes to build/test/generated/&lt;name&gt;. Used only by
 * processor tests; the reflection here is test scaffolding.
 *
 * @author Brian Pontarelli
 */
public final class ProcessorHarness {
  private ProcessorHarness() {
  }

  public record Result(boolean success, List<String> diagnostics, Path outputDir) {
    public ClassLoader loader() throws Exception {
      return new URLClassLoader(new URL[]{outputDir.toUri().toURL()},
          ProcessorHarness.class.getClassLoader());
    }
  }

  public static Result compile(String fixture) throws Exception {
    Path fixtureRoot = Path.of("src/test/resources/fixtures", fixture);
    Path out = Path.of("build/test/generated", fixture);
    if (Files.exists(out)) {
      try (var walk = Files.walk(out)) {
        walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
    Files.createDirectories(out);

    List<Path> sources;
    try (var walk = Files.walk(fixtureRoot)) {
      sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }

    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    var diagCollector = new DiagnosticCollector<JavaFileObject>();
    StandardJavaFileManager fm = javac.getStandardFileManager(diagCollector, null, StandardCharsets.UTF_8);
    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
    fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(out.toFile()));

    Iterable<? extends JavaFileObject> units =
        fm.getJavaFileObjectsFromPaths(sources);
    JavaCompiler.CompilationTask task = javac.getTask(
        null, fm, diagCollector,
        List.of("--release", "25"),
        null, units);
    task.setProcessors(List.of(new JSONProcessor()));
    boolean ok = task.call();
    fm.close();

    List<String> diags = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> d : diagCollector.getDiagnostics()) {
      diags.add(d.getKind() + ": " + d.getMessage(null));
    }
    return new Result(ok, diags, out);
  }
}
```

- [ ] **Step 4: Write the `JSONProcessor` scaffold (validation + diagnostics only)**

`src/main/java/org/lattejava/json/JSONProcessor.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Annotation processor for {@link JSON @JSON}. Plan 2 scope: records whose components are primitives,
 * boxed primitives, or {@code String}. Emits the runtime helper set into the consumer's
 * {@code <module>.internal} package and a per-record observer companion into
 * {@code <typePackage>.internal}.
 *
 * @author Brian Pontarelli
 */
@SupportedAnnotationTypes("org.lattejava.json.JSON")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class JSONProcessor extends AbstractProcessor {
  static final List<String> HELPERS = List.of(
      "JSONProcessingException", "JSONObjectHandler", "JSONObserver",
      "JSONArrayObserver", "JSONPolymorphicObserver", "JSONParser",
      "JSONBuilder", "Numbers", "SkipObserver", "SkipArrayObserver",
      "AnyObjectObserver", "AnyArrayObserver");

  private boolean helpersEmitted = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    TypeElement jsonAnno = processingEnv.getElementUtils().getTypeElement("org.lattejava.json.JSON");
    if (jsonAnno == null) {
      return false;
    }
    Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(jsonAnno);
    for (Element e : annotated) {
      if (e.getKind() != ElementKind.RECORD) {
        error(e, "@JSON supports only records in this release; [" + qualified(e)
            + "] is a [" + e.getKind() + "]");
        continue;
      }
      TypeElement type = (TypeElement) e;
      ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
      if (module == null || module.isUnnamed()) {
        error(e, "@JSON requires a named module (module-info.java); type ["
            + type.getQualifiedName() + "] is in the unnamed module");
        continue;
      }
      if (!validateComponents(type)) {
        continue;
      }
      if (!helpersEmitted) {
        emitHelpers(module);
        helpersEmitted = true;
      }
      generateCompanion(type, module);
    }
    return false;
  }

  void emitHelpers(ModuleElement module) {
    // Filled in by Task 3.
  }

  void generateCompanion(TypeElement record, ModuleElement module) {
    // Filled in by Task 4 and Task 5.
  }

  private void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
  }

  private String qualified(Element e) {
    return e instanceof TypeElement t ? t.getQualifiedName().toString() : e.toString();
  }

  private boolean isSupportedComponentType(TypeMirror t) {
    if (t.getKind().isPrimitive()) {
      return true;
    }
    if (t.getKind() == TypeKind.DECLARED) {
      String name = t.toString();
      return switch (name) {
        case "java.lang.String", "java.lang.Boolean", "java.lang.Byte",
             "java.lang.Short", "java.lang.Integer", "java.lang.Long",
             "java.lang.Float", "java.lang.Double" -> true;
        default -> false;
      };
    }
    return false;
  }

  private boolean validateComponents(TypeElement record) {
    boolean ok = true;
    for (RecordComponentElement c : record.getRecordComponents()) {
      if (!isSupportedComponentType(c.asType())) {
        error(c, "@JSON component [" + c.getSimpleName() + "] has unsupported type ["
            + c.asType() + "] in this release (only primitives, boxed primitives, and String)");
        ok = false;
      }
    }
    return ok;
  }
}
```

- [ ] **Step 5: Create the service-registration resource**

`src/main/resources/META-INF/services/javax.annotation.processing.Processor` with exactly this single line (no trailing comment):

```
org.lattejava.json.JSONProcessor
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `latte test --test=ProcessorErrorsTest`
Expected: PASS, 3 tests run. (`nonRecordIsRejected`, `unsupportedComponentTypeIsRejected`, `missingModuleIsRejected` all pass — the scaffold emits the right diagnostics and fails compilation; no companion is generated yet, which is fine for these negative cases.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/main/resources/META-INF/services/javax.annotation.processing.Processor \
        src/test/java/org/lattejava/json/tests/processor/ProcessorHarness.java \
        src/test/java/org/lattejava/json/tests/processor/ProcessorErrorsTest.java
git commit -m "feat: add JSONProcessor scaffold with validation and compile-API harness"
```

---

## Task 3: Helper emission into `<module>.internal`

Implement `emitHelpers`: read each template resource, rewrite the package line, write via the `Filer`.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (replace the `emitHelpers` stub)
- Test: `src/test/java/org/lattejava/json/tests/processor/HelperEmissionTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/json/tests/processor/HelperEmissionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class HelperEmissionTest {
  @Test
  public void helpersEmittedIntoModuleInternalAndCompile() throws Exception {
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(),
        "fixture compile (with helper emission) must succeed; diagnostics: " + result.diagnostics());

    // demo.simple module → helpers land in package demo.simple.internal
    for (String helper : org.lattejava.json.JSONProcessor.HELPERS) {
      Path cls = result.outputDir().resolve("demo/simple/internal/" + helper + ".class");
      assertTrue(Files.exists(cls),
          "expected emitted+compiled helper [" + cls + "]");
    }
  }

  @Test
  public void emittedHelperHasRewrittenPackage() throws Exception {
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(), result.diagnostics().toString());
    Path src = result.outputDir().resolve("demo/simple/internal/JSONParser.java");
    assertTrue(Files.exists(src), "expected emitted JSONParser.java source");
    String text = Files.readString(src, StandardCharsets.UTF_8);
    assertTrue(text.contains("package demo.simple.internal;"),
        "emitted helper must have rewritten package; head was:\n"
        + text.substring(0, Math.min(200, text.length())));
    assertFalse(text.contains("package org.lattejava.json;"),
        "original package line must be gone");
  }

  @Test
  public void helpersEmittedOnlyOnce() throws Exception {
    // simple fixture has two @JSON records; helpers must be emitted exactly once (no Filer dup error)
    var result = ProcessorHarness.compile("simple");
    assertTrue(result.success(),
        "duplicate helper emission would surface as a Filer error; diagnostics: "
        + result.diagnostics());
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `latte test --test=HelperEmissionTest`
Expected: FAIL — `emitHelpers` is a stub, so the generated companion will reference non-existent helper types and compilation fails (or helper `.class` files are absent).

- [ ] **Step 3: Implement `emitHelpers`**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, replace:

```java
  void emitHelpers(ModuleElement module) {
    // Filled in by Task 3.
  }
```

with:

```java
  void emitHelpers(ModuleElement module) {
    String pkg = module.getQualifiedName() + ".internal";
    for (String helper : HELPERS) {
      String resource = "/org/lattejava/json/internal-templates/" + helper + ".java.txt";
      String body;
      try (InputStream in = JSONProcessor.class.getResourceAsStream(resource)) {
        if (in == null) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
              "Missing helper template resource [" + resource + "]");
          return;
        }
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException ioe) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "Failed reading helper template [" + resource + "]: " + ioe.getMessage());
        return;
      }
      String rewritten = body.replace(
          "package org.lattejava.json;", "package " + pkg + ";");
      try {
        var file = processingEnv.getFiler().createSourceFile(pkg + "." + helper);
        try (Writer w = file.openWriter()) {
          w.write(rewritten);
        }
      } catch (IOException ioe) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
            "Failed writing helper [" + pkg + "." + helper + "]: " + ioe.getMessage());
        return;
      }
    }
  }
```

Add these imports to the existing `import` block of `JSONProcessor.java` (they are all in `java.base`, already covered by `import module java.base;` — no new import lines needed; `InputStream`, `Writer`, `IOException` resolve via the module import).

Note: `generateCompanion` is still a stub, so the fixture's `@JSON` records produce no companion yet — but the fixture compile in this test does not require the companion to exist; it only checks helper emission and that the fixture (records with no companion) still compiles. Since the records themselves are valid Java independent of the companion, compilation succeeds.

- [ ] **Step 4: Run to verify it passes**

Run: `latte test --test=HelperEmissionTest`
Expected: PASS, 3 tests run. All 12 helper `.class` files exist under `build/test/generated/simple/demo/simple/internal/`, `JSONParser.java` has the rewritten package, and emitting for two records does not double-write.

- [ ] **Step 5: Regression check**

Run: `latte test --test=HelperTemplateDriftTest` and `latte test --test=ProcessorErrorsTest`
Expected: both PASS (no regression).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/java/org/lattejava/json/tests/processor/HelperEmissionTest.java
git commit -m "feat: processor emits runtime helpers into <module>.internal"
```

---

## Task 4: Companion serialization codegen (`toJSON` / `toJSONBytes`)

Generate the companion class with working serialization for the Plan-2 component types. Deserialization is added in Task 5; until then `fromJSON` is generated but unverified.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (replace `generateCompanion` stub; add helpers)
- Test: `src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java`

- [ ] **Step 1: Write the failing serialization test**

`src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class SimpleRecordCodegenTest {

  private static ProcessorHarness.Result simple;

  @BeforeClass
  public void compileOnce() throws Exception {
    simple = ProcessorHarness.compile("simple");
    assertTrue(simple.success(), "fixture compile must succeed; " + simple.diagnostics());
  }

  @Test
  public void serializesUserToJSONStringInDeclarationOrder() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Alice", 30, "alice@example.com");
      String json = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      assertEquals(json, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
    }
  }

  @Test
  public void toJSONBytesMatchesToJSON() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Bob", 1, "b@x.io");
      String s = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      byte[] b = (byte[]) userJson.getMethod("toJSONBytes", userClass).invoke(null, user);
      assertEquals(new String(b, StandardCharsets.UTF_8), s);
    }
  }

  @Test
  public void omitsNullStringByDefault() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object user = userClass.getConstructor(String.class, int.class, String.class)
          .newInstance("Cara", 7, null);
      String json = (String) userJson.getMethod("toJSON", userClass).invoke(null, user);
      assertEquals(json, "{\"name\":\"Cara\",\"age\":7}");
    }
  }

  @Test
  public void serializesAllPrimitiveKinds() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> p     = loader.loadClass("demo.Primitives");
      Class<?> pJson = loader.loadClass("demo.internal.PrimitivesJSON");
      Object obj = p.getConstructor(boolean.class, byte.class, short.class, int.class,
              long.class, float.class, double.class, Integer.class, Long.class)
          .newInstance(true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.25d, 7, 8L);
      String json = (String) pJson.getMethod("toJSON", p).invoke(null, obj);
      assertEquals(json,
          "{\"flag\":true,\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5.5,\"d\":6.25,\"boxedInt\":7,\"boxedLong\":8}");
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `latte test --test=SimpleRecordCodegenTest`
Expected: FAIL — `demo.internal.UserJSON` does not exist (`generateCompanion` is a stub).

- [ ] **Step 3: Implement `generateCompanion` (serialization half)**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, replace:

```java
  void generateCompanion(TypeElement record, ModuleElement module) {
    // Filled in by Task 4 and Task 5.
  }
```

with the following. (This emits the full companion: serialization is functional now; the observer/`fromJSON` half is finished in Task 5. The observer methods are generated here as compilable stubs that Task 5 fills — they must compile so the whole companion compiles.)

```java
  void generateCompanion(TypeElement record, ModuleElement module) {
    String internalPkg = module.getQualifiedName() + ".internal";
    String typePkg = processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
    String companionPkg = typePkg.isEmpty() ? "internal" : typePkg + ".internal";
    String simpleName = record.getSimpleName().toString();
    String companion = simpleName + "JSON";
    String qualifiedType = record.getQualifiedName().toString();

    List<RecordComponentElement> comps = List.copyOf(record.getRecordComponents());
    boolean omitNulls = readOmitNulls(record);

    StringBuilder sb = new StringBuilder();
    sb.append("""
        /*
         * Copyright (c) 2026 The Latte Project
         * SPDX-License-Identifier: MIT
         */
        """);
    sb.append("package ").append(companionPkg).append(";\n\n");
    sb.append("import module java.base;\n");
    sb.append("import ").append(qualifiedType).append(";\n");
    sb.append("import ").append(internalPkg).append(".JSONBuilder;\n");
    sb.append("import ").append(internalPkg).append(".JSONObserver;\n");
    sb.append("import ").append(internalPkg).append(".JSONArrayObserver;\n");
    sb.append("import ").append(internalPkg).append(".JSONObjectHandler;\n");
    sb.append("import ").append(internalPkg).append(".JSONParser;\n");
    sb.append("import ").append(internalPkg).append(".Numbers;\n\n");
    sb.append("public final class ").append(companion)
      .append(" implements JSONObserver<").append(simpleName).append("> {\n");

    // instance fields (observer accumulation), declaration order kept for finish()
    for (RecordComponentElement c : comps) {
      sb.append("  private ").append(c.asType()).append(' ')
        .append(c.getSimpleName()).append(";\n");
    }
    sb.append('\n');

    // ---- serialization ----
    sb.append("  public static String toJSON(").append(simpleName).append(" value) {\n");
    sb.append("    return builder(value).build();\n");
    sb.append("  }\n\n");
    sb.append("  public static byte[] toJSONBytes(").append(simpleName).append(" value) {\n");
    sb.append("    return builder(value).buildBytes();\n");
    sb.append("  }\n\n");
    sb.append("  private static JSONBuilder builder(").append(simpleName).append(" value) {\n");
    sb.append("    return new JSONBuilder(").append(omitNulls).append(")\n");
    for (RecordComponentElement c : comps) {
      sb.append("        .").append(builderCall(c, "value." + c.getSimpleName() + "()")).append('\n');
    }
    sb.append("        ;\n");
    sb.append("  }\n\n");

    // ---- deserialization entry ----
    sb.append("  public static ").append(simpleName).append(" fromJSON(String json) {\n");
    sb.append("    var observer = new ").append(companion).append("();\n");
    sb.append("    return new JSONParser().parse(json, observer);\n");
    sb.append("  }\n\n");
    sb.append("  public static ").append(simpleName).append(" fromJSON(byte[] json) {\n");
    sb.append("    var observer = new ").append(companion).append("();\n");
    sb.append("    return new JSONParser().parse(json, observer);\n");
    sb.append("  }\n\n");

    // ---- observer callbacks (Task 5 fills the bodies; stubs must compile) ----
    appendObserverMethods(sb, record, comps);

    sb.append("}\n");

    try {
      var file = processingEnv.getFiler().createSourceFile(companionPkg + "." + companion, record);
      try (Writer w = file.openWriter()) {
        w.write(sb.toString());
      }
    } catch (IOException ioe) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed writing companion [" + companionPkg + "." + companion + "]: " + ioe.getMessage(),
          record);
    }
  }

  private boolean readOmitNulls(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann == null || ann.omitNulls();
  }

  private boolean readStrict(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann != null && ann.strict();
  }

  /** The fluent JSONBuilder call for a component, e.g. {@code string("name", value.name())}. */
  private String builderCall(RecordComponentElement c, String accessor) {
    String key = c.getSimpleName().toString();
    String t = c.asType().toString();
    return switch (t) {
      case "java.lang.String" -> "string(\"" + key + "\", " + accessor + ")";
      case "boolean", "java.lang.Boolean" -> "bool(\"" + key + "\", " + accessor + ")";
      case "byte", "short", "int", "long",
           "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
          "integer(\"" + key + "\", " + accessor + ")";
      case "float", "double", "java.lang.Float", "java.lang.Double" ->
          "decimal(\"" + key + "\", java.math.BigDecimal.valueOf(" + accessor + "))";
      default -> throw new IllegalStateException("unreachable: validated type " + t);
    };
  }
```

Notes baked into the design above:
- `integer(String,long)` accepts `byte/short/int/long` and boxed via autoboxing/widening; for boxed nulls under `omitNulls=true` the value would NPE on widening — Plan 2 fixtures use non-null boxed values, and null-boxed-numeric handling is a Plan 5 field-policy concern (documented limitation; not exercised by Plan 2 tests).
- `decimal(...)` uses `BigDecimal.valueOf(double|float)`; `JSONBuilder.decimal` emits `toPlainString()`, so `5.5f`→`5.5`, `6.25d`→`6.25`. `BigDecimal.valueOf(float)` widens via `double`; for the Plan-2 fixture values this is exact. General float fidelity is a Plan 3 concern.
- `String` and boxed nulls are omitted by `JSONBuilder` when `omitNulls` (constructor flag) is true.

- [ ] **Step 4: Add the compilable observer-method stub generator**

Append this method to `JSONProcessor.java` (alphabetical position among private methods — place `appendObserverMethods` before `builderCall`):

```java
  private void appendObserverMethods(StringBuilder sb, TypeElement record,
                                     List<RecordComponentElement> comps) {
    // Task 5 replaces these bodies with real accumulation/dispatch. For now they must compile.
    sb.append("  @Override public void string(String key, String value) {}\n");
    sb.append("  @Override public void integer(String key, long value) {}\n");
    sb.append("  @Override public void bigInteger(String key, java.math.BigInteger value) {}\n");
    sb.append("  @Override public void decimal(String key, java.math.BigDecimal value) {}\n");
    sb.append("  @Override public void bool(String key, boolean value) {}\n");
    sb.append("  @Override public void nullValue(String key) {}\n");
    sb.append("  @Override public JSONObjectHandler beginObject(String key) {\n");
    sb.append("    throw new ").append("IllegalStateException(\"no nested object in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void object(String key, Object value) {}\n");
    sb.append("  @Override public JSONArrayObserver<?> beginArray(String key) {\n");
    sb.append("    throw new IllegalStateException(\"no array in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void array(String key, Object value) {}\n");
    sb.append("  @Override public ").append(record.getSimpleName())
      .append(" finish() { return null; }\n");
  }
```

- [ ] **Step 5: Run to verify serialization passes**

Run: `latte test --test=SimpleRecordCodegenTest`
Expected: PASS, 4 tests run. (`finish()` returns `null` for now, but none of the Task-4 tests call `fromJSON`, so they pass on the serialization path.)

- [ ] **Step 6: Regression check**

Run: `latte test --test=HelperEmissionTest` and `latte test --test=ProcessorErrorsTest` and `latte test --test=HelperTemplateDriftTest`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java
git commit -m "feat: processor generates record serialization companion"
```

---

## Task 5: Companion deserialization codegen (observer bodies + `finish`)

Replace the observer stubs with real per-field accumulation and a real `finish()` that calls the canonical record constructor with precision-safe narrowing.

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (replace `appendObserverMethods`)
- Test: extend `SimpleRecordCodegenTest` with roundtrip cases

- [ ] **Step 1: Add the failing roundtrip tests**

Append these methods to `src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java` (inside the class):

```java
  @Test
  public void roundTripsUser() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "Alice");
      assertEquals(userClass.getMethod("age").invoke(parsed), 30);
      assertEquals(userClass.getMethod("email").invoke(parsed), "alice@example.com");
      String reser = (String) userJson.getMethod("toJSON", userClass).invoke(null, parsed);
      assertEquals(reser, "{\"name\":\"Alice\",\"age\":30,\"email\":\"alice@example.com\"}");
    }
  }

  @Test
  public void roundTripsAllPrimitiveKinds() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> p     = loader.loadClass("demo.Primitives");
      Class<?> pJson = loader.loadClass("demo.internal.PrimitivesJSON");
      String json = "{\"flag\":true,\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5.5,\"d\":6.25,\"boxedInt\":7,\"boxedLong\":8}";
      Object obj = pJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(p.getMethod("flag").invoke(obj), true);
      assertEquals(p.getMethod("b").invoke(obj), (byte) 1);
      assertEquals(p.getMethod("s").invoke(obj), (short) 2);
      assertEquals(p.getMethod("i").invoke(obj), 3);
      assertEquals(p.getMethod("l").invoke(obj), 4L);
      assertEquals(p.getMethod("f").invoke(obj), 5.5f);
      assertEquals(p.getMethod("d").invoke(obj), 6.25d);
      assertEquals(p.getMethod("boxedInt").invoke(obj), 7);
      assertEquals(p.getMethod("boxedLong").invoke(obj), 8L);
      assertEquals(pJson.getMethod("toJSON", p).invoke(null, obj), json);
    }
  }

  @Test
  public void missingPrimitiveKeyLeavesJavaDefault() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"OnlyName\"}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "OnlyName");
      assertEquals(userClass.getMethod("age").invoke(parsed), 0);     // int default
      assertNull(userClass.getMethod("email").invoke(parsed));        // String default
    }
  }

  @Test(expectedExceptions = java.lang.reflect.InvocationTargetException.class)
  public void intOverflowThrows() throws Exception {
    try (var loader = (URLClassLoader) simple.loader()) {
      Class<?> userJson = loader.loadClass("demo.internal.UserJSON");
      userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"X\",\"age\":99999999999999999999}");
      // age is int; 20-digit → BigInteger path → intValueExact overflow → JSONProcessingException
    }
  }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `latte test --test=SimpleRecordCodegenTest`
Expected: FAIL on the new roundtrip tests — `finish()` returns `null`, observer bodies are empty, so parsed values are wrong / `parsed` is null.

- [ ] **Step 3: Replace `appendObserverMethods` with real codegen**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, replace the entire `appendObserverMethods` method with:

```java
  private void appendObserverMethods(StringBuilder sb, TypeElement record,
                                     List<RecordComponentElement> comps) {
    boolean strict = readStrict(record);
    String simpleName = record.getSimpleName().toString();

    // group keys by which callback delivers them
    sb.append("  @Override public void string(String key, String value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      if (c.asType().toString().equals("java.lang.String")) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = value;\n");
      }
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void integer(String key, long value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      String narrow = integerNarrowing(t);
      if (narrow != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = ").append(narrow).append(";\n");
      }
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void bigInteger(String key, java.math.BigInteger value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      String narrow = bigIntegerNarrowing(t);
      if (narrow != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = ").append(narrow).append(";\n");
      }
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void decimal(String key, java.math.BigDecimal value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      String narrow = decimalNarrowing(t);
      if (narrow != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = ").append(narrow).append(";\n");
      }
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void bool(String key, boolean value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      if (t.equals("boolean") || t.equals("java.lang.Boolean")) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = value;\n");
      }
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void nullValue(String key) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      if (c.asType().getKind().isPrimitive()) {
        sb.append("      case \"").append(c.getSimpleName())
          .append("\" -> throw new IllegalStateException(")
          .append("\"null for primitive field [").append(c.getSimpleName()).append("]\");\n");
      }
      // reference fields: null is the default; nothing to do
    }
    appendDefaultArm(sb, strict);
    sb.append("    }\n  }\n");

    sb.append("  @Override public JSONObjectHandler beginObject(String key) {\n");
    sb.append("    throw new IllegalStateException(\"nested objects unsupported in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void object(String key, Object value) {}\n");
    sb.append("  @Override public JSONArrayObserver<?> beginArray(String key) {\n");
    sb.append("    throw new IllegalStateException(\"arrays unsupported in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void array(String key, Object value) {}\n");

    sb.append("  @Override public ").append(simpleName).append(" finish() {\n");
    sb.append("    return new ").append(simpleName).append("(");
    for (int i = 0; i < comps.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append("this.").append(comps.get(i).getSimpleName());
    }
    sb.append(");\n  }\n");
  }

  private void appendDefaultArm(StringBuilder sb, boolean strict) {
    if (strict) {
      sb.append("      default -> throw new IllegalStateException(\"Unknown JSON key [\" + key + \"]\");\n");
    } else {
      sb.append("      default -> { /* lenient: ignore unknown key */ }\n");
    }
  }

  /** Narrowing expression to assign an {@code integer(long value)} callback into a component, or null. */
  private String integerNarrowing(String type) {
    return switch (type) {
      case "long", "java.lang.Long" -> "value";
      case "int", "java.lang.Integer" -> "Math.toIntExact(value)";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(value)";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(value)";
      case "float", "java.lang.Float" -> "(float) value";
      case "double", "java.lang.Double" -> "(double) value";
      default -> null;
    };
  }

  private String bigIntegerNarrowing(String type) {
    return switch (type) {
      case "long", "java.lang.Long" -> "value.longValueExact()";
      case "int", "java.lang.Integer" -> "value.intValueExact()";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(value.longValueExact())";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(value.longValueExact())";
      case "float", "java.lang.Float" -> "value.floatValue()";
      case "double", "java.lang.Double" -> "value.doubleValue()";
      default -> null;
    };
  }

  private String decimalNarrowing(String type) {
    return switch (type) {
      case "float", "java.lang.Float" -> "value.floatValue()";
      case "double", "java.lang.Double" -> "value.doubleValue()";
      case "int", "java.lang.Integer" -> "value.intValueExact()";
      case "long", "java.lang.Long" -> "value.longValueExact()";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(value.longValueExact())";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(value.longValueExact())";
      default -> null;
    };
  }
```

(`bool`/`string` deliver only their own kinds; numeric components accept `integer`, `bigInteger`, or `decimal` callbacks per the design's type-mapping table, so a numeric field gets a `case` in all three numeric callbacks. A JSON integer for a `double` field arrives via `integer`→`(double) value`; a JSON `6.25` arrives via `decimal`→`value.doubleValue()`.)

- [ ] **Step 4: Run to verify roundtrip passes**

Run: `latte test --test=SimpleRecordCodegenTest`
Expected: PASS, 8 tests run (4 from Task 4 + 4 new). `intOverflowThrows` passes because the 20-digit value routes through `bigInteger` → `intValueExact()` → `ArithmeticException`, which the generated `finish` path surfaces; the reflective call wraps it in `InvocationTargetException`.

- [ ] **Step 5: Full regression**

Run: `latte test`
Expected: all pass (Plan-1 87 + new processor tests), 0 failures. Report the count.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java \
        src/test/java/org/lattejava/json/tests/processor/SimpleRecordCodegenTest.java
git commit -m "feat: processor generates record deserialization with safe narrowing"
```

---

## Task 6: Unknown-key policy (lenient default vs `@JSON(strict=true)`)

`appendDefaultArm` already emits lenient-vs-strict default arms. This task verifies both behaviors end-to-end.

**Files:**
- Test: `src/test/java/org/lattejava/json/tests/processor/UnknownKeyPolicyTest.java`

- [ ] **Step 1: Write the test**

`src/test/java/org/lattejava/json/tests/processor/UnknownKeyPolicyTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class UnknownKeyPolicyTest {
  @Test
  public void lenientByDefaultDropsUnknownScalarKeys() throws Exception {
    var r = ProcessorHarness.compile("simple");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> userClass = loader.loadClass("demo.User");
      Class<?> userJson  = loader.loadClass("demo.internal.UserJSON");
      Object parsed = userJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Z\",\"age\":1,\"email\":\"z@z\",\"extra\":\"ignored\",\"more\":5}");
      assertEquals(userClass.getMethod("name").invoke(parsed), "Z");
      assertEquals(userClass.getMethod("age").invoke(parsed), 1);
      assertEquals(userClass.getMethod("email").invoke(parsed), "z@z");
    }
  }

  @Test(expectedExceptions = java.lang.reflect.InvocationTargetException.class)
  public void strictRejectsUnknownKey() throws Exception {
    var r = ProcessorHarness.compile("strict");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> sj = loader.loadClass("demo.internal.StrictUserJSON");
      sj.getMethod("fromJSON", String.class)
        .invoke(null, "{\"name\":\"Z\",\"age\":1,\"surprise\":true}");
    }
  }

  @Test
  public void strictAcceptsExactKeys() throws Exception {
    var r = ProcessorHarness.compile("strict");
    assertTrue(r.success(), r.diagnostics().toString());
    try (var loader = (URLClassLoader) r.loader()) {
      Class<?> sc = loader.loadClass("demo.StrictUser");
      Class<?> sj = loader.loadClass("demo.internal.StrictUserJSON");
      Object parsed = sj.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Z\",\"age\":1}");
      assertEquals(sc.getMethod("name").invoke(parsed), "Z");
      assertEquals(sc.getMethod("age").invoke(parsed), 1);
    }
  }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `latte test --test=UnknownKeyPolicyTest`
Expected: PASS, 3 tests run. (Behavior already implemented in Task 5's `appendDefaultArm`; this task is the guard. If `strictRejectsUnknownKey` fails, the strict default arm is wrong — fix `appendDefaultArm`, not the test.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/json/tests/processor/UnknownKeyPolicyTest.java
git commit -m "test: verify lenient/strict unknown-key policy end to end"
```

---

## Task 7: Full-suite smoke + clean rebuild

- [ ] **Step 1: Clean rebuild + full suite**

Run: `latte clean && latte build && latte test`
Expected: BUILD SUCCEEDED; all tests pass (Plan-1 87 + Plan-2 processor tests), 0 failures. Report the total.

- [ ] **Step 2: Verify the drift guard still holds after all work**

Run: `latte test --test=HelperTemplateDriftTest`
Expected: PASS, 3 tests. (No helper source was changed in Plan 2, so templates and canonical sources remain byte-identical. If this fails, a helper was edited without regenerating templates — re-run Task 0 Step 4's copy loop and commit.)

- [ ] **Step 3: Verification gate — no commit**

If green, Plan 2 is complete. If anything fails, surface it to the reviewer rather than silently patching.

---

## Self-Review (performed during authoring)

**Spec coverage:** Plan 2 implements the design's "annotation processor + simple records" slice — processor scaffold, `Messager` diagnostics on the offending element, helper emission into `<module>.internal` with package rewrite (design "JSON processing code" section), per-record companion in `<typePackage>.internal` (design "Annotation processor" section), `fromJSON`/`toJSON`/`toJSONBytes`, observer accumulation with precision-safe narrowing (`Math.toIntExact`/`Numbers.toByteExact`/`*Exact`), lenient-vs-strict unknown-key policy, omit-nulls serialization. Out-of-scope items (classes, collections, extras, `@JSONField`, `@JSONCatchAll`, polymorphism codegen, naming strategies) are explicitly guarded by compile-time errors or documented limitations and deferred to Plans 3–8 per the phasing table.

**Placeholder scan:** every code step contains complete code. The two `generateCompanion`/`emitHelpers`/`appendObserverMethods` "stubs" are real compilable code introduced in one task and fully replaced in a later task with the replacement shown verbatim — not placeholders.

**Type consistency:** `JSONProcessor.HELPERS` is referenced by `HelperEmissionTest`. The generated companion imports `JSONObjectHandler` (matching the Plan-1 `beginObject` return type), `JSONObserver`, `JSONArrayObserver`, `JSONParser`, `JSONBuilder`, `Numbers` from `<module>.internal`. `JSONBuilder(boolean)` and `.string/.integer/.decimal/.bool/.build/.buildBytes` match the Plan-1 `JSONBuilder` API. `JSONParser.parse(String, JSONObserver<T>)` / `parse(byte[], JSONObserver<T>)` match Plan-1 signatures. Narrowing uses `Numbers.toByteExact/toShortExact` (Plan-1) and `Math.toIntExact`, `BigInteger.intValueExact/longValueExact`, `BigDecimal.intValueExact/longValueExact/doubleValue/floatValue` (JDK).

**Known Plan-2 limitations (intentional, deferred):** null boxed-numeric components under `omitNulls` (Plan 5 field policy); float/double exact fidelity from `BigDecimal.valueOf` (Plan 3); `@JSONField`/naming/`@JSONCatchAll`/polymorphism codegen (Plans 5/7). None are exercised by Plan-2 fixtures.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-16-processor-and-simple-records.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review (spec then code quality) between tasks, fast iteration in this session.
2. **Inline Execution** — execute tasks here via executing-plans with batch checkpoints.

Which approach?
