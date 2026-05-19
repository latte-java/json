# JSONProcessor Codegen Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the imperative `StringBuilder.append(...)` plumbing in `JSONProcessor` companion generation with a small composable text-block template model, keeping type-dispatch logic in Java.

**Architecture:** A package-public `Template` primitive (literal `{{name}}` substitution + list `join` + Mustache-style standalone-hole re-indentation) plus a `Templates` holder of `static final String` text blocks. `JSONProcessor` keeps the record-iteration and type-dispatch predicates but emits via `Template.render` instead of `sb.append`. Behavior-preserving: the existing processor TestNG suite is the acceptance gate; output may differ in whitespace only.

**Tech Stack:** Java 25, Latte build (`latte test`), TestNG, `javax.annotation.processing`.

**Spec:** `docs/design/2026-05-18-json-codegen-templates-design.md`

**Orthogonality note (do not break):** `JSONProcessor.HELPERS` + `src/main/resources/org/lattejava/json/internal-templates/*.java.txt` is a *separate* mechanism (verbatim copy of runtime helper classes into the consumer module, guarded by `HelperTemplateDriftTest`). `Template` and `Templates` are build-time-only: **never add them to `HELPERS`**, never create `internal-templates` resources for them, never reference them from generated companion code. This keeps `HelperTemplateDriftTest` and `HelperEmissionTest` green without modification.

**Conventions (all new files):** SPDX copyright header first (`Copyright (c) 2026 The Latte Project` / `SPDX-License-Identifier: MIT`), uppercase acronyms (`JSON`, not `Json`), runtime values in exception messages wrapped in `[brackets]`, `import module java.base;` over class imports, fields with no blank lines between them, members ordered static-fields → instance-fields → constructors → static-methods → instance-methods.

---

## File Structure

- **Create** `src/main/java/org/lattejava/json/Template.java` — the template primitive. Public (package `org.lattejava.json` is exported; must be public to unit-test from the `org.lattejava.json.tests` module, same as `JSONProcessor`). Build-time only.
- **Create** `src/main/java/org/lattejava/json/Templates.java` — `public final` holder of `static final String` text-block template bodies. No logic.
- **Create** `src/test/java/org/lattejava/json/tests/processor/TemplateTest.java` — TestNG unit tests for `Template`.
- **Modify** `src/main/java/org/lattejava/json/JSONProcessor.java` — replace `sb.append` plumbing in `generateCompanion`, `appendObserverMethods`, `appendMapCodegen`, the List/Set inner-observer block, `appendElementAccumulator`, `appendMapValueAccumulator`, `appendUnusedArrayObserverStubs`, `appendUnusedMapObserverStubs`, `appendDefaultArm`. Delete those helpers once their callers no longer use `StringBuilder`.

Acceptance gate for every task that touches `JSONProcessor`: the full processor suite green —
`latte test` (or per class: `latte test --test=SimpleRecordCodegenTest` etc. for
`SimpleRecordCodegenTest`, `MapCodegenTest`, `ListCodegenTest`, `SetCodegenTest`, `EnumCodegenTest`,
`EnumCollectionCodegenTest`, `BigNumberCodegenTest`, `TimeCodegenTest`, `UUIDCodegenTest`,
`CollectionRejectionTest`, `UnknownKeyPolicyTest`, `ProcessorErrorsTest`, `HelperEmissionTest`,
`HelperTemplateDriftTest`).

---

## Task 1: The `Template` primitive

**Files:**
- Create: `src/main/java/org/lattejava/json/Template.java`
- Test: `src/test/java/org/lattejava/json/tests/processor/TemplateTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/lattejava/json/tests/processor/TemplateTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class TemplateTest {
  @Test
  public void substitutesNamedHoles() {
    String out = Template.of("class {{name}} {}").render(Map.of("name", "Foo"));
    assertEquals(out, "class Foo {}");
  }

  @Test
  public void substitutesRepeatedHole() {
    String out = Template.of("{{x}}+{{x}}").render(Map.of("x", "a"));
    assertEquals(out, "a+a");
  }

  @Test
  public void literalNotRegex_replacementWithDollarAndBackslashIsLiteral() {
    String out = Template.of("v={{e}}").render(Map.of("e", "a -> b$c\\d"));
    assertEquals(out, "v=a -> b$c\\d");
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void unboundHoleThrows() {
    Template.of("{{a}} {{b}}").render(Map.of("a", "1"));
  }

  @Test
  public void joinMapsAndJoins() {
    String out = Template.join(List.of("a", "b", "c"), s -> "[" + s + "]", "\n");
    assertEquals(out, "[a]\n[b]\n[c]");
  }

  @Test
  public void joinEmptyIsEmptyString() {
    assertEquals(Template.join(List.of(), Object::toString, "\n"), "");
  }

  @Test
  public void reindentsMultilineFragmentToHoleColumn() {
    String body = "class Foo {\n  {{fields}}\n}\n";
    String out = Template.of(body).render(Map.of("fields", "int a;\nint b;"));
    assertEquals(out, "class Foo {\n  int a;\n  int b;\n}\n");
  }

  @Test
  public void reindentSkipsBlankInteriorLines() {
    String body = "class Foo {\n  {{methods}}\n}\n";
    String out = Template.of(body).render(Map.of("methods", "void a() {}\n\nvoid b() {}"));
    assertEquals(out, "class Foo {\n  void a() {}\n\n  void b() {}\n}\n");
  }

  @Test
  public void emptyFragmentDropsTheEntireHoleLine() {
    String body = "class Foo {\n  {{fields}}\n}\n";
    String out = Template.of(body).render(Map.of("fields", ""));
    assertEquals(out, "class Foo {\n}\n");
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --test=TemplateTest`
Expected: FAIL — `Template` does not exist (compilation failure).

- [ ] **Step 3: Implement `Template`**

Create `src/main/java/org/lattejava/json/Template.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Minimal build-time text-block template: literal {@code {{name}}} substitution with Mustache-style standalone-hole
 * re-indentation. Not a runtime helper — never added to {@link JSONProcessor#HELPERS} and never emitted into a consumer
 * module. Public only so the {@code org.lattejava.json.tests} module can unit-test it.
 *
 * @author Brian Pontarelli
 */
public final class Template {
  private final String body;

  private Template(String body) {
    this.body = body;
  }

  /**
   * Maps {@code items} through {@code render} and joins the results with {@code separator}. Empty input yields the
   * empty string (so the enclosing hole collapses and its line is dropped).
   */
  public static <T> String join(Collection<T> items, Function<T, String> render, String separator) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (T item : items) {
      if (!first) {
        sb.append(separator);
      }
      sb.append(render.apply(item));
      first = false;
    }
    return sb.toString();
  }

  public static Template of(String body) {
    return new Template(body);
  }

  /**
   * Substitutes every {@code {{name}}} with its bound value. A hole whose line contains only whitespace around the
   * {@code {{name}}} is a standalone hole: a multi-line value is re-indented to the hole's column, and an empty value
   * drops the entire line. Any {@code {{name}}} left after substitution is a hard error.
   */
  public String render(Map<String, String> bindings) {
    String result = body;
    for (Map.Entry<String, String> e : bindings.entrySet()) {
      String token = "{{" + e.getKey() + "}}";
      result = applyBinding(result, token, e.getValue());
    }
    int open = result.indexOf("{{");
    if (open >= 0) {
      int close = result.indexOf("}}", open);
      String name = close > open ? result.substring(open + 2, close) : result.substring(open + 2);
      throw new IllegalStateException("Unbound template hole [" + name + "]");
    }
    return result;
  }

  private String applyBinding(String text, String token, String value) {
    StringBuilder out = new StringBuilder();
    int pos = 0;
    while (true) {
      int at = text.indexOf(token, pos);
      if (at < 0) {
        out.append(text, pos, text.length());
        return out.toString();
      }
      int lineStart = text.lastIndexOf('\n', at) + 1;
      int after = at + token.length();
      boolean standalone =
          text.substring(lineStart, at).isBlank()
          && (after == text.length() || text.substring(after, lineEnd(text, after)).isBlank());
      if (standalone) {
        String indent = text.substring(lineStart, at);
        out.append(text, pos, lineStart);
        if (value.isEmpty()) {
          int nl = text.indexOf('\n', after);
          pos = nl < 0 ? text.length() : nl + 1;
        } else {
          out.append(indent).append(reindent(value, indent));
          int lineEnd = lineEnd(text, after);
          pos = lineEnd;
        }
      } else {
        out.append(text, pos, at).append(value);
        pos = after;
      }
    }
  }

  private int lineEnd(String text, int from) {
    int nl = text.indexOf('\n', from);
    return nl < 0 ? text.length() : nl;
  }

  private String reindent(String value, String indent) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < value.length()) {
      int nl = value.indexOf('\n', i);
      String line = nl < 0 ? value.substring(i) : value.substring(i, nl);
      sb.append(line);
      if (nl < 0) {
        break;
      }
      sb.append('\n');
      boolean lastEmptyTrailing = nl == value.length() - 1;
      if (!lastEmptyTrailing) {
        // peek next line; only indent non-empty lines
        int nextNl = value.indexOf('\n', nl + 1);
        String next = nextNl < 0 ? value.substring(nl + 1) : value.substring(nl + 1, nextNl);
        if (!next.isEmpty()) {
          sb.append(indent);
        }
      }
      i = nl + 1;
    }
    return sb.toString();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --test=TemplateTest`
Expected: PASS — all 9 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/Template.java \
        src/test/java/org/lattejava/json/tests/processor/TemplateTest.java
git commit -m "feat: add build-time Template primitive for codegen

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `Templates` holder + migrate fixed scaffolding

Migrates the constant-shape part of `generateCompanion` (header, package, imports, field declarations, the four static `toJSON`/`toJSONBytes`/`builder`/`fromJSON` methods — `JSONProcessor.java:110-168`) to a template. The structural blocks (`appendMapCodegen`, the List/Set block, `appendObserverMethods`) stay on `StringBuilder` for now and are spliced in via a single `{{body}}` hole so the suite stays green.

**Files:**
- Create: `src/main/java/org/lattejava/json/Templates.java`
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (`generateCompanion`, lines 99-219)
- Test (acceptance): full processor suite

- [ ] **Step 1: Run the suite to capture the green baseline**

Run: `latte test`
Expected: PASS — record this as the behavior-preserving baseline. Every later step compares against it.

- [ ] **Step 2: Create `Templates` with the companion scaffolding template**

Create `src/main/java/org/lattejava/json/Templates.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Build-time text-block templates for {@link JSONProcessor} companion generation. Build-time only — not a runtime
 * helper, never added to {@link JSONProcessor#HELPERS}.
 *
 * @author Brian Pontarelli
 */
public final class Templates {
  public static final String COMPANION = """
      /*
       * Copyright (c) 2026 The Latte Project
       * SPDX-License-Identifier: MIT
       */
      package {{package}};

      import module java.base;
      import {{qualifiedType}};
      import {{internalPkg}}.Conversions;
      import {{internalPkg}}.JSONArrayBuilder;
      import {{internalPkg}}.JSONArrayObserver;
      import {{internalPkg}}.JSONBuilder;
      import {{internalPkg}}.JSONObjectHandler;
      import {{internalPkg}}.JSONObserver;
      import {{internalPkg}}.JSONParser;
      import {{internalPkg}}.JSONProcessingException;
      import {{internalPkg}}.Numbers;
      {{enumImports}}

      /**
       * Generated by org.lattejava.json.JSONProcessor. Do not edit.
       *
       * @author Latte JSON
       */
      public final class {{companion}} implements JSONObserver<{{simpleName}}> {
        {{fields}}

        public static String toJSON({{simpleName}} value) {
          return builder(value).build();
        }

        public static byte[] toJSONBytes({{simpleName}} value) {
          return builder(value).buildBytes();
        }

        private static JSONBuilder builder({{simpleName}} value) {
          return new JSONBuilder({{omitNulls}})
              {{builderCalls}}
              ;
        }

        public static {{simpleName}} fromJSON(String json) {
          var observer = new {{companion}}();
          return new JSONParser().parse(json, observer);
        }

        public static {{simpleName}} fromJSON(byte[] json) {
          var observer = new {{companion}}();
          return new JSONParser().parse(json, observer);
        }

        {{body}}
      }
      """;

  private Templates() {
  }
}
```

Note the literal `{{...}}` tokens — text-block bodies are constant strings, never `String.format`.

- [ ] **Step 3: Rewrite `generateCompanion` scaffolding to render the template**

In `src/main/java/org/lattejava/json/JSONProcessor.java`, replace the body of `generateCompanion` from the `StringBuilder sb = new StringBuilder();` line (currently line 110) through the `appendObserverMethods(sb, record, comps);` / `sb.append("}\n");` lines (currently 205-207) with the following. Keep everything above line 110 (the `internalPkg`/`companionPkg`/`simpleName`/`companion`/`qualifiedType`/`comps`/`omitNulls` locals) and the `try { ... createSourceFile ... }` block below (currently 209-218) unchanged, except the variable written to the file becomes `source` instead of `sb.toString()`:

```java
    StringBuilder structural = new StringBuilder();
    for (RecordComponentElement c : comps) {
      String ck = collectionKind(c.asType());
      if (ck == null) {
        continue;
      }
      String dt = declType(c.asType());
      if (ck.equals("Map")) {
        appendMapCodegen(structural, c, dt, omitNulls);
        continue;
      }
      TypeMirror elem = typeArg(c.asType(), 0);
      structural.append("  private static String ").append(c.getSimpleName()).append("ToJSON(")
        .append(dt).append(" v) {\n");
      structural.append("    var b = new JSONArrayBuilder();\n");
      structural.append("    for (var e : v) b").append(arrayAppend(elem, "e")).append(";\n");
      structural.append("    return b.build();\n");
      structural.append("  }\n");
      String obs = cap(c.getSimpleName().toString()) + "ArrayObserver";
      structural.append("  private static final class ").append(obs)
        .append(" implements JSONArrayObserver<").append(dt).append("> {\n");
      structural.append("    private final ").append(dt).append(" acc = new ")
        .append(ck.equals("Set") ? "java.util.LinkedHashSet" : "java.util.ArrayList")
        .append("<>();\n");
      appendElementAccumulator(structural, elem, "acc");
      appendUnusedArrayObserverStubs(structural, producedElementCallbacks(elem), elem);
      structural.append("    @Override public ").append(dt).append(" finish() { return acc; }\n");
      structural.append("    @Override public JSONObjectHandler beginObject() { "
          + "throw new JSONProcessingException(\"nested objects in collections unsupported\"); }\n");
      structural.append("    @Override public JSONArrayObserver<?> beginArray() { "
          + "throw new JSONProcessingException(\"nested collections unsupported\"); }\n");
      structural.append("    @Override public void object(Object value) {}\n");
      structural.append("    @Override public void array(Object value) {}\n");
      structural.append("  }\n");
    }
    StringBuilder observers = new StringBuilder();
    appendObserverMethods(observers, record, comps);

    String enumImportLines = Template.join(
        enumImports, fqn -> "import " + fqn + ";", "\n");
    String fieldLines = Template.join(comps, c -> {
      String declaredTypeName = isEnum(c.asType())
          ? lastSegment(c.asType().toString())
          : simpleType(c.asType().toString());
      return "private " + declaredTypeName + " " + c.getSimpleName() + ";";
    }, "\n");
    String builderCallLines = Template.join(comps,
        c -> "." + builderCall(c, "value." + c.getSimpleName() + "()"), "\n");

    String source = Template.of(Templates.COMPANION).render(Map.of(
        "package", companionPkg,
        "qualifiedType", qualifiedType,
        "internalPkg", internalPkg,
        "enumImports", enumImportLines,
        "companion", companion,
        "simpleName", simpleName,
        "fields", fieldLines,
        "omitNulls", String.valueOf(omitNulls),
        "builderCalls", builderCallLines,
        "body", (structural + observers.toString()).strip()));
```

Then update the file-write block: change `w.write(sb.toString());` to `w.write(source);`. Remove the now-unused `enumImports.forEach(...)` line and the old `sb`-based scaffolding entirely (it is fully replaced above). The `Set<String> enumImports` collection loop (currently lines 129-132) stays — it feeds `enumImportLines`.

- [ ] **Step 4: Run the suite to verify behavior is preserved**

Run: `latte test`
Expected: PASS — same result as Step 1 baseline. Generated companion compiles and round-trips identically (whitespace may differ; behavior must not).

If failures: read a generated companion under `build/test/generated/simple/` and diff its structure against the baseline; the likely culprits are the standalone-hole rules (`{{fields}}`, `{{builderCalls}}`, `{{body}}` must each sit alone on their line — they do in `COMPANION`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/Templates.java \
        src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: render companion scaffolding via Template

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Migrate scalar observer methods

Converts `appendObserverMethods` (`JSONProcessor.java:331-478`) from `sb.append` to templates. The six scalar callbacks (`string`, `integer`, `bigInteger`, `decimal`, `bool`, `nullValue`) plus `beginObject`/`object`/`beginArray`/`array`/`finish` become rendered text; the per-component type-dispatch predicates stay in Java.

**Files:**
- Modify: `src/main/java/org/lattejava/json/Templates.java` (add observer-method templates)
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java` (rewrite `appendObserverMethods`, drop `appendDefaultArm`)
- Test (acceptance): full processor suite

- [ ] **Step 1: Add observer templates to `Templates`**

Add these `public static final String` constants to `Templates` (alphabetical order among the constants):

```java
  public static final String OBSERVER_BODY = """
      @Override public void string(String key, String value) {
        switch (key) {
      {{stringCases}}
      {{defaultArm}}
        }
      }
      @Override public void integer(String key, long value) {
        switch (key) {
      {{integerCases}}
      {{defaultArm}}
        }
      }
      @Override public void bigInteger(String key, BigInteger value) {
        switch (key) {
      {{bigIntegerCases}}
      {{defaultArm}}
        }
      }
      @Override public void decimal(String key, BigDecimal value) {
        switch (key) {
      {{decimalCases}}
      {{defaultArm}}
        }
      }
      @Override public void bool(String key, boolean value) {
        switch (key) {
      {{boolCases}}
      {{defaultArm}}
        }
      }
      @Override public void nullValue(String key) {
        switch (key) {
      {{nullCases}}
      {{defaultArm}}
        }
      }
      @Override public JSONObjectHandler beginObject(String key) {
        switch (key) {
      {{beginObjectCases}}
        }
        throw new IllegalStateException("nested objects unsupported in this release");
      }
      @SuppressWarnings("unchecked")
      @Override public void object(String key, Object value) {
        switch (key) {
      {{objectCases}}
      {{defaultArm}}
        }
      }
      @Override public JSONArrayObserver<?> beginArray(String key) {
        switch (key) {
      {{beginArrayCases}}
        }
        throw new IllegalStateException("arrays unsupported in this release");
      }
      @SuppressWarnings("unchecked")
      @Override public void array(String key, Object value) {
        switch (key) {
      {{arrayCases}}
      {{defaultArm}}
        }
      }
      @Override public {{simpleName}} finish() {
        return new {{simpleName}}({{ctorArgs}});
      }""";
```

The case-arm and default-arm strings are built in Java (they are one line each, no template needed) and joined with `Template.join`.

- [ ] **Step 2: Rewrite `appendObserverMethods`**

Replace the entire body of `appendObserverMethods` (`JSONProcessor.java:331-478`) with:

```java
  private void appendObserverMethods(StringBuilder sb, TypeElement record,
                                     List<RecordComponentElement> comps) {
    boolean strict = readStrict(record);
    String simpleName = record.getSimpleName().toString();
    String defaultArm = strict
        ? "      default -> throw new JSONProcessingException(\"Unknown JSON key [\" + key + \"] for type ["
          + simpleName + "]\");"
        : "      default -> { /* lenient: ignore unknown key */ }";

    String stringCases = Template.join(comps, c -> {
      String tt = c.asType().toString();
      String name = c.getSimpleName().toString();
      if (tt.equals("java.lang.String")) {
        return "      case \"" + name + "\" -> this." + name + " = value;";
      }
      if (isEnum(c.asType())) {
        return "      case \"" + name + "\" -> this." + name + " = Conversions.toEnum("
            + lastSegment(tt) + ".class, value);";
      }
      if (stringConversion(tt) != null) {
        return "      case \"" + name + "\" -> this." + name + " = Conversions."
            + stringConversion(tt) + "(value);";
      }
      return null;
    }, "\n");

    String integerCases = narrowingCases(comps, this::integerNarrowing);
    String bigIntegerCases = narrowingCases(comps, this::bigIntegerNarrowing);
    String decimalCases = narrowingCases(comps, this::decimalNarrowing);

    String boolCases = Template.join(comps, c -> {
      String t = c.asType().toString();
      String name = c.getSimpleName().toString();
      return (t.equals("boolean") || t.equals("java.lang.Boolean"))
          ? "      case \"" + name + "\" -> this." + name + " = value;" : null;
    }, "\n");

    String nullCases = Template.join(comps, c -> {
      String name = c.getSimpleName().toString();
      return c.asType().getKind().isPrimitive()
          ? "      case \"" + name + "\" -> throw new JSONProcessingException(\"null for primitive field ["
            + name + "]\");"
          : "      case \"" + name + "\" -> this." + name + " = null;";
    }, "\n");

    String beginObjectCases = Template.join(comps, c -> {
      String name = c.getSimpleName().toString();
      return "Map".equals(collectionKind(c.asType()))
          ? "      case \"" + name + "\" -> { return new " + cap(name) + "MapObserver(); }" : null;
    }, "\n");

    String objectCases = Template.join(comps, c -> {
      String name = c.getSimpleName().toString();
      return "Map".equals(collectionKind(c.asType()))
          ? "      case \"" + name + "\" -> this." + name + " = (" + declType(c.asType()) + ") value;"
          : null;
    }, "\n");

    String beginArrayCases = Template.join(comps, c -> {
      String ck = collectionKind(c.asType());
      String name = c.getSimpleName().toString();
      return ("List".equals(ck) || "Set".equals(ck))
          ? "      case \"" + name + "\" -> { return new " + cap(name) + "ArrayObserver(); }" : null;
    }, "\n");

    String arrayCases = Template.join(comps, c -> {
      String ck = collectionKind(c.asType());
      String name = c.getSimpleName().toString();
      return ("List".equals(ck) || "Set".equals(ck))
          ? "      case \"" + name + "\" -> this." + name + " = (" + declType(c.asType()) + ") value;"
          : null;
    }, "\n");

    String ctorArgs = Template.join(comps,
        c -> "this." + c.getSimpleName(), ", ");

    sb.append(Template.of(Templates.OBSERVER_BODY).render(Map.ofEntries(
        Map.entry("stringCases", stringCases),
        Map.entry("integerCases", integerCases),
        Map.entry("bigIntegerCases", bigIntegerCases),
        Map.entry("decimalCases", decimalCases),
        Map.entry("boolCases", boolCases),
        Map.entry("nullCases", nullCases),
        Map.entry("beginObjectCases", beginObjectCases),
        Map.entry("objectCases", objectCases),
        Map.entry("beginArrayCases", beginArrayCases),
        Map.entry("arrayCases", arrayCases),
        Map.entry("defaultArm", defaultArm),
        Map.entry("simpleName", simpleName),
        Map.entry("ctorArgs", ctorArgs))));
  }

  private String narrowingCases(List<RecordComponentElement> comps,
                                Function<String, String> narrowing) {
    return Template.join(comps, c -> {
      String narrow = narrowing.apply(c.asType().toString());
      String name = c.getSimpleName().toString();
      return narrow != null ? "      case \"" + name + "\" -> this." + name + " = " + narrow + ";" : null;
    }, "\n");
  }
```

Note: `Template.join`'s `render` function returns `null` for non-applicable components; update `Template.join` to skip `null` results (a no-applicable-component callback then renders empty, and the `{{...Cases}}` standalone hole drops its own line, leaving a valid empty `switch`). Make this `Template.join` change in this task:

In `Template.join`, replace the loop body with:

```java
    for (T item : items) {
      String rendered = render.apply(item);
      if (rendered == null) {
        continue;
      }
      if (!first) {
        sb.append(separator);
      }
      sb.append(rendered);
      first = false;
    }
```

Then delete the now-unused `appendDefaultArm` method (`JSONProcessor.java:221-228`).

- [ ] **Step 3: Add a `Template.join` null-skip test (TDD for the changed contract)**

Add to `TemplateTest`:

```java
  @Test
  public void joinSkipsNullResults() {
    String out = Template.join(List.of("a", "b", "c"),
        s -> s.equals("b") ? null : "[" + s + "]", "\n");
    assertEquals(out, "[a]\n[c]");
  }
```

- [ ] **Step 4: Run the suite**

Run: `latte test`
Expected: PASS — `TemplateTest` (10 tests) green and the full processor suite green, matching the Task 2 baseline behavior.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/Templates.java \
        src/main/java/org/lattejava/json/JSONProcessor.java \
        src/main/java/org/lattejava/json/Template.java \
        src/test/java/org/lattejava/json/tests/processor/TemplateTest.java
git commit -m "refactor: render scalar observer methods via Template

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Migrate array/map inner-observer classes

Converts the List/Set inner-observer block (now in `generateCompanion`'s `structural` builder from Task 2), `appendMapCodegen`, `appendElementAccumulator`, `appendMapValueAccumulator`, `appendUnusedArrayObserverStubs`, `appendUnusedMapObserverStubs` to templates.

**Files:**
- Modify: `src/main/java/org/lattejava/json/Templates.java` (add `ARRAY_OBSERVER`, `MAP_OBSERVER`)
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Test (acceptance): full processor suite

- [ ] **Step 1: Add collection-observer templates to `Templates`**

```java
  public static final String ARRAY_OBSERVER = """
      private static String {{field}}ToJSON({{declType}} v) {
        var b = new JSONArrayBuilder();
        for (var e : v) b{{arrayAppend}};
        return b.build();
      }
      private static final class {{obs}} implements JSONArrayObserver<{{declType}}> {
        private final {{declType}} acc = new {{accImpl}}<>();
        {{accumulator}}
        {{stubs}}
        @Override public {{declType}} finish() { return acc; }
        @Override public JSONObjectHandler beginObject() { throw new JSONProcessingException("nested objects in collections unsupported"); }
        @Override public JSONArrayObserver<?> beginArray() { throw new JSONProcessingException("nested collections unsupported"); }
        @Override public void object(Object value) {}
        @Override public void array(Object value) {}
      }""";

  public static final String MAP_OBSERVER = """
      private static String {{field}}ToJSON({{declType}} v) {
        var b = new JSONBuilder({{omitNulls}});
        for (var en : v.entrySet()) b.{{memberCall}};
        return b.build();
      }
      private static final class {{obs}} implements JSONObserver<{{declType}}> {
        private final {{declType}} map = new java.util.LinkedHashMap<>();
        {{accumulator}}
        {{stubs}}
        @Override public void nullValue(String key) { map.put({{keyFromString}}, null); }
        @Override public {{declType}} finish() { return map; }
        @Override public JSONObjectHandler beginObject(String key) { throw new JSONProcessingException("nested objects in collections unsupported"); }
        @Override public JSONArrayObserver<?> beginArray(String key) { throw new JSONProcessingException("nested collections unsupported"); }
        @Override public void object(String key, Object value) {}
        @Override public void array(String key, Object value) {}
      }""";
```

- [ ] **Step 2: Change accumulator/stub emitters to return `String`**

Change `appendElementAccumulator`, `appendMapValueAccumulator`, `appendUnusedArrayObserverStubs`, `appendUnusedMapObserverStubs` from `void ...(StringBuilder sb, ...)` to `String ...(...)` returning the joined lines (replace each `sb.append("X\n")` with appending `"X"` to a `List<String>`, then `String.join("\n", list)`). Their bodies are otherwise unchanged — same predicates, same emitted text minus the trailing `\n` per line. Example for `appendElementAccumulator`:

```java
  private String elementAccumulator(TypeMirror t, String target) {
    List<String> lines = new ArrayList<>();
    String s = t.toString();
    if (isEnum(t)) {
      lines.add("@Override public void string(String value) { " + target
          + ".add(Conversions.toEnum(" + lastSegment(s) + ".class, value)); }");
    } else if (s.equals("java.lang.String")) {
      lines.add("@Override public void string(String value) { " + target + ".add(value); }");
    } else if (s.equals("java.util.UUID")) {
      lines.add("@Override public void string(String value) { " + target
          + ".add(Conversions.toUUID(value)); }");
    } else if (stringConversion(s) != null) {
      lines.add("@Override public void string(String value) { " + target
          + ".add(Conversions." + stringConversion(s) + "(value)); }");
    } else if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      lines.add("@Override public void bool(boolean value) { " + target + ".add(value); }");
    } else {
      lines.add("@Override public void integer(long value) { " + target + ".add("
          + integerNarrowing(s) + "); }");
      lines.add("@Override public void bigInteger(java.math.BigInteger value) { " + target
          + ".add(" + bigIntegerNarrowing(s) + "); }");
      lines.add("@Override public void decimal(java.math.BigDecimal value) { " + target
          + ".add(" + decimalNarrowing(s) + "); }");
    }
    lines.add("@Override public void nullValue() { " + target + ".add(null); }");
    return String.join("\n", lines);
  }
```

Apply the identical mechanical transform to `appendMapValueAccumulator` → `mapValueAccumulator(TypeMirror k, TypeMirror v)`, `appendUnusedArrayObserverStubs` → `unusedArrayObserverStubs(Set<String> producedCallbacks, TypeMirror elementType)`, `appendUnusedMapObserverStubs` → `unusedMapObserverStubs(Set<String> producedCallbacks, TypeMirror valueType)` — drop the `StringBuilder sb` param, accumulate into `List<String>`, `return String.join("\n", lines)`, strip the leading `    ` indentation and trailing `\n` from each line literal (the template's standalone `{{accumulator}}` / `{{stubs}}` hole supplies indentation).

- [ ] **Step 3: Rewrite the List/Set block and `appendMapCodegen` to render templates**

Replace the List/Set `structural.append(...)` block in `generateCompanion` (added in Task 2) with:

```java
      TypeMirror elem = typeArg(c.asType(), 0);
      String obs = cap(c.getSimpleName().toString()) + "ArrayObserver";
      structural.append(Template.of(Templates.ARRAY_OBSERVER).render(Map.of(
          "field", c.getSimpleName().toString(),
          "declType", dt,
          "arrayAppend", arrayAppend(elem, "e"),
          "obs", obs,
          "accImpl", ck.equals("Set") ? "java.util.LinkedHashSet" : "java.util.ArrayList",
          "accumulator", elementAccumulator(elem, "acc"),
          "stubs", unusedArrayObserverStubs(producedElementCallbacks(elem), elem))));
      structural.append('\n');
```

Replace `appendMapCodegen`'s body with:

```java
  private void appendMapCodegen(StringBuilder sb, RecordComponentElement c, String dt,
                                boolean omitNulls) {
    TypeMirror k = typeArg(c.asType(), 0);
    TypeMirror v = typeArg(c.asType(), 1);
    String obs = cap(c.getSimpleName().toString()) + "MapObserver";
    sb.append(Template.of(Templates.MAP_OBSERVER).render(Map.of(
        "field", c.getSimpleName().toString(),
        "declType", dt,
        "omitNulls", String.valueOf(omitNulls),
        "memberCall", memberCall(v, keyToString(k, "en.getKey()"), "en.getValue()"),
        "obs", obs,
        "accumulator", mapValueAccumulator(k, v),
        "stubs", unusedMapObserverStubs(producedElementCallbacks(v), v),
        "keyFromString", keyFromString(k, "key"))));
    sb.append('\n');
  }
```

- [ ] **Step 4: Run the suite**

Run: `latte test`
Expected: PASS — full processor suite green, matching baseline behavior. `MapCodegenTest`, `ListCodegenTest`, `SetCodegenTest`, `EnumCollectionCodegenTest` are the high-signal classes here.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/json/Templates.java \
        src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: render array/map inner observers via Template

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Remove dead code and final verification

**Files:**
- Modify: `src/main/java/org/lattejava/json/JSONProcessor.java`
- Test (acceptance): full processor suite

- [ ] **Step 1: Delete dead helpers and unused imports**

Confirm no remaining callers (search the file), then delete any now-unreferenced private methods left from the migration. After Tasks 2-4 the only `StringBuilder` left in `JSONProcessor` is the local `structural`/`observers` accumulators in `generateCompanion` and the `sb` parameter threaded into `appendObserverMethods`/`appendMapCodegen`. Verify `javax.lang.model.type.TypeKind` and other imports are still used; remove any that are not.

Run: `grep -n 'sb.append\|StringBuilder' src/main/java/org/lattejava/json/JSONProcessor.java`
Expected: only the `structural`, `observers`, and the `sb`-parameter accumulators remain — no constant-string `.append("...")` plumbing.

- [ ] **Step 2: Full suite + targeted drift/emission check**

Run: `latte test`
Expected: PASS — entire suite. Explicitly confirm `HelperTemplateDriftTest` and `HelperEmissionTest` pass unchanged (proves the orthogonal helper mechanism and `HELPERS` list were untouched).

Run: `git -P diff --stat main -- src/main/java/org/lattejava/json/JSONProcessor.java`
Expected: net line reduction in `JSONProcessor.java`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/json/JSONProcessor.java
git commit -m "refactor: drop dead StringBuilder helpers from JSONProcessor

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Spec §1 `Template` primitive (named substitution, literal replace, unbound-hole error, `join`, owns indentation) → Task 1.
- Spec §2 template tree (`COMPANION`, `OBSERVER_BODY`, `ARRAY_OBSERVER`/`MAP_OBSERVER`, leaf arms in Java) → Tasks 2-4.
- Spec §3 indentation (standalone hole, literal-whitespace indent, skip blank lines, empty drops line) → Task 1 Steps 1/3 (tests `reindentsMultilineFragmentToHoleColumn`, `reindentSkipsBlankInteriorLines`, `emptyFragmentDropsTheEntireHoleLine`) and the `Template.render` implementation.
- Spec §4 migration/validation (behavior-preserving, suite is the gate, no API/module/dep change, incremental order, conventions, `HELPERS` orthogonality) → Task 2 Step 1 baseline + per-task suite gate; orthogonality verified Task 5 Step 2.
- Spec Risks (indentation edge cases, silent drift, logic/template boundary) → Task 1 tests; per-task suite gate; type-dispatch predicates explicitly kept in Java in Tasks 3-4.

**Placeholder scan:** No TBD/TODO. Every code step shows complete code or an exact mechanical transform with a worked example (`elementAccumulator`) standing in for the three identical sibling transforms in Task 4 Step 2.

**Type consistency:** `Template.of(String)`, `Template.render(Map<String,String>)`, `Template.join(Collection<T>, Function<T,String>, String)` used consistently across Tasks 1-4. `Template.join` null-skip behavior is introduced in Task 3 Step 2 and tested in Task 3 Step 3 before first relied upon. Renamed emitters (`elementAccumulator`, `mapValueAccumulator`, `unusedArrayObserverStubs`, `unusedMapObserverStubs`, `narrowingCases`) are defined and called within the same task (Tasks 3-4). `structural`/`observers`/`source` locals introduced in Task 2 Step 3 are referenced consistently in Task 4 Step 3.
