/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;
import module java.compiler;

import javax.lang.model.type.TypeKind;

/**
 * Annotation processor for {@link JSON @JSON}. Generates a serialization/deserialization companion for each annotated
 * record. Supported component types: primitives and their boxed forms, {@code String}, {@code BigInteger},
 * {@code BigDecimal}, any {@code enum}, {@code UUID}, and the ISO-8601 {@code java.time} types ({@code Instant},
 * {@code LocalDate}, {@code LocalDateTime}, {@code OffsetDateTime}, {@code ZonedDateTime}, {@code Duration},
 * {@code Period}). Emits the runtime helper set into the consumer's {@code <module>.internal} package and a per-record
 * observer companion into {@code <typePackage>.internal}.
 *
 * @author Brian Pontarelli
 */
@SupportedAnnotationTypes("org.lattejava.json.JSON")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class JSONProcessor extends AbstractProcessor {
  public static final List<String> HELPERS = List.of(
      "AnyArrayObserver", "AnyObjectObserver", "Conversions", "JSONArrayBuilder",
      "JSONArrayObserver", "JSONBuilder", "JSONObjectHandler", "JSONObserver",
      "JSONParser", "JSONPolymorphicObserver", "JSONProcessingException", "Numbers",
      "SkipArrayObserver", "SkipObserver");
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
        error(e, "@JSON supports only records in this release; [" + qualified(e) + "] is a [" + e.getKind() + "]");
        continue;
      }

      TypeElement type = (TypeElement) e;
      ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
      if (module == null || module.isUnnamed()) {
        error(e, "@JSON requires a named module (module-info.java); type [" + type.getQualifiedName() + "] is in the unnamed module");
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

  void generateCompanion(TypeElement record, ModuleElement module) {
    String internalPkg = module.getQualifiedName() + ".internal";
    String typePkg = processingEnv.getElementUtils().getPackageOf(record).getQualifiedName().toString();
    String companionPkg = typePkg.isEmpty() ? "internal" : typePkg + ".internal";
    String simpleName = record.getSimpleName().toString();
    String companion = simpleName + "JSON";
    String qualifiedType = record.getQualifiedName().toString();

    List<RecordComponentElement> comps = List.copyOf(record.getRecordComponents());
    boolean omitNulls = readOmitNulls(record);

    Set<String> enumImports = new TreeSet<>();
    for (RecordComponentElement c : comps) {
      collectEnumImports(c.asType(), enumImports);
    }

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

    String body = (structural.toString() + observers.toString()).lines()
        .map(line -> line.startsWith("  ") ? line.substring(2) : line)
        .collect(Collectors.joining("\n"))
        .strip();
    String source = Template.of(Templates.COMPANION).render(Map.ofEntries(
        Map.entry("package", companionPkg),
        Map.entry("qualifiedType", qualifiedType),
        Map.entry("internalPkg", internalPkg),
        Map.entry("enumImports", enumImportLines),
        Map.entry("companion", companion),
        Map.entry("simpleName", simpleName),
        Map.entry("fields", fieldLines),
        Map.entry("omitNulls", String.valueOf(omitNulls)),
        Map.entry("builderCalls", builderCallLines),
        Map.entry("body", body)));

    try {
      var file = processingEnv.getFiler().createSourceFile(companionPkg + "." + companion, record);
      try (Writer w = file.openWriter()) {
        w.write(source);
      }
    } catch (IOException ioe) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed writing companion [" + companionPkg + "." + companion + "]: " + ioe.getMessage(),
          record);
    }
  }

  private void appendDefaultArm(StringBuilder sb, boolean strict, String simpleName) {
    if (strict) {
      sb.append("      default -> throw new JSONProcessingException(\"Unknown JSON key [\" + key + \"] for type [")
        .append(simpleName).append("]\");\n");
    } else {
      sb.append("      default -> { /* lenient: ignore unknown key */ }\n");
    }
  }

  /**
   * Inner-observer body that accumulates the JSON value into {@code target} (a List/Set) for element type t. Invariant
   * pair with {@link #producedElementCallbacks}: their type-dispatch predicates MUST stay parallel (a callback emitted
   * here must be reported there, and vice versa). Tasks 3/4 inherit this.
   */
  private void appendElementAccumulator(StringBuilder sb, TypeMirror t, String target) {
    String s = t.toString();
    if (isEnum(t)) {
      sb.append("    @Override public void string(String value) { ").append(target)
        .append(".add(Conversions.toEnum(").append(lastSegment(s)).append(".class, value)); }\n");
    } else if (s.equals("java.lang.String")) {
      sb.append("    @Override public void string(String value) { ").append(target).append(".add(value); }\n");
    } else if (s.equals("java.util.UUID")) {
      sb.append("    @Override public void string(String value) { ").append(target)
        .append(".add(Conversions.toUUID(value)); }\n");
    } else if (stringConversion(s) != null) {
      sb.append("    @Override public void string(String value) { ").append(target)
        .append(".add(Conversions.").append(stringConversion(s)).append("(value)); }\n");
    } else if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      sb.append("    @Override public void bool(boolean value) { ").append(target).append(".add(value); }\n");
    } else {
      sb.append("    @Override public void integer(long value) { ").append(target).append(".add(")
        .append(integerNarrowing(s)).append("); }\n");
      sb.append("    @Override public void bigInteger(java.math.BigInteger value) { ").append(target)
        .append(".add(").append(bigIntegerNarrowing(s)).append("); }\n");
      sb.append("    @Override public void decimal(java.math.BigDecimal value) { ").append(target)
        .append(".add(").append(decimalNarrowing(s)).append("); }\n");
    }
    sb.append("    @Override public void nullValue() { ").append(target).append(".add(null); }\n");
  }

  /**
   * Emits the {@code <field>ToJSON} serializer and inner {@code <Cap>MapObserver} for a {@code Map<K, V>} component.
   * The serializer writes a JSON object whose member names are K's wire form and whose values use the shared scalar
   * {@link #memberCall}; the observer accumulates each JSON object member into a {@link java.util.LinkedHashMap} (key
   * parsed back from the member name via {@link #keyFromString}, value converted per V).
   */
  private void appendMapCodegen(StringBuilder sb, RecordComponentElement c, String dt,
                                boolean omitNulls) {
    TypeMirror k = typeArg(c.asType(), 0);
    TypeMirror v = typeArg(c.asType(), 1);
    sb.append("  private static String ").append(c.getSimpleName()).append("ToJSON(")
      .append(dt).append(" v) {\n");
    sb.append("    var b = new JSONBuilder(").append(omitNulls).append(");\n");
    sb.append("    for (var en : v.entrySet()) b.")
      .append(memberCall(v, keyToString(k, "en.getKey()"), "en.getValue()")).append(";\n");
    sb.append("    return b.build();\n");
    sb.append("  }\n");
    String obs = cap(c.getSimpleName().toString()) + "MapObserver";
    sb.append("  private static final class ").append(obs)
      .append(" implements JSONObserver<").append(dt).append("> {\n");
    sb.append("    private final ").append(dt).append(" map = new java.util.LinkedHashMap<>();\n");
    appendMapValueAccumulator(sb, k, v);
    appendUnusedMapObserverStubs(sb, producedElementCallbacks(v), v);
    sb.append("    @Override public void nullValue(String key) { map.put(")
      .append(keyFromString(k, "key")).append(", null); }\n");
    sb.append("    @Override public ").append(dt).append(" finish() { return map; }\n");
    sb.append("    @Override public JSONObjectHandler beginObject(String key) { "
        + "throw new JSONProcessingException(\"nested objects in collections unsupported\"); }\n");
    sb.append("    @Override public JSONArrayObserver<?> beginArray(String key) { "
        + "throw new JSONProcessingException(\"nested collections unsupported\"); }\n");
    sb.append("    @Override public void object(String key, Object value) {}\n");
    sb.append("    @Override public void array(String key, Object value) {}\n");
    sb.append("  }\n");
  }

  /**
   * Inner Map-observer body that accumulates each JSON object member into {@code map} for value type {@code v}, parsing
   * the member name back to a key of type {@code k} via {@link #keyFromString}. Invariant pair with
   * {@link #producedElementCallbacks}: their type-dispatch predicates MUST stay parallel (a value callback emitted here
   * must be reported there, and vice versa), the same discipline {@link #appendElementAccumulator} follows for
   * List/Set.
   */
  private void appendMapValueAccumulator(StringBuilder sb, TypeMirror k, TypeMirror v) {
    String key = keyFromString(k, "key");
    String s = v.toString();
    if (isEnum(v)) {
      sb.append("    @Override public void string(String key, String value) { map.put(").append(key)
        .append(", Conversions.toEnum(").append(lastSegment(s)).append(".class, value)); }\n");
    } else if (s.equals("java.lang.String")) {
      sb.append("    @Override public void string(String key, String value) { map.put(").append(key)
        .append(", value); }\n");
    } else if (s.equals("java.util.UUID")) {
      sb.append("    @Override public void string(String key, String value) { map.put(").append(key)
        .append(", Conversions.toUUID(value)); }\n");
    } else if (stringConversion(s) != null) {
      sb.append("    @Override public void string(String key, String value) { map.put(").append(key)
        .append(", Conversions.").append(stringConversion(s)).append("(value)); }\n");
    } else if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      sb.append("    @Override public void bool(String key, boolean value) { map.put(").append(key)
        .append(", value); }\n");
    } else {
      sb.append("    @Override public void integer(String key, long value) { map.put(").append(key)
        .append(", ").append(integerNarrowing(s)).append("); }\n");
      sb.append("    @Override public void bigInteger(String key, java.math.BigInteger value) { map.put(")
        .append(key).append(", ").append(bigIntegerNarrowing(s)).append("); }\n");
      sb.append("    @Override public void decimal(String key, java.math.BigDecimal value) { map.put(")
        .append(key).append(", ").append(decimalNarrowing(s)).append("); }\n");
    }
  }

  private void appendObserverMethods(StringBuilder sb, TypeElement record,
                                     List<RecordComponentElement> comps) {
    boolean strict = readStrict(record);
    String simpleName = record.getSimpleName().toString();

    sb.append("  @Override public void string(String key, String value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String tt = c.asType().toString();
      if (tt.equals("java.lang.String")) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = value;\n");
      } else if (isEnum(c.asType())) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = Conversions.toEnum(")
          .append(lastSegment(tt)).append(".class, value);\n");
      } else if (stringConversion(tt) != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = Conversions.")
          .append(stringConversion(tt)).append("(value);\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
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
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void bigInteger(String key, BigInteger value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      String narrow = bigIntegerNarrowing(t);
      if (narrow != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = ").append(narrow).append(";\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void decimal(String key, BigDecimal value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String t = c.asType().toString();
      String narrow = decimalNarrowing(t);
      if (narrow != null) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = ").append(narrow).append(";\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
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
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");

    sb.append("  @Override public void nullValue(String key) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      if (c.asType().getKind().isPrimitive()) {
        sb.append("      case \"").append(c.getSimpleName())
          .append("\" -> throw new JSONProcessingException(")
          .append("\"null for primitive field [").append(c.getSimpleName()).append("]\");\n");
      } else {
        sb.append("      case \"").append(c.getSimpleName())
          .append("\" -> this.").append(c.getSimpleName()).append(" = null;\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");

    sb.append("  @Override public JSONObjectHandler beginObject(String key) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      if ("Map".equals(collectionKind(c.asType()))) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> { return new ")
          .append(cap(c.getSimpleName().toString())).append("MapObserver(); }\n");
      }
    }
    sb.append("    }\n");
    sb.append("    throw new IllegalStateException(\"nested objects unsupported in this release\");\n");
    sb.append("  }\n");
    sb.append("  @SuppressWarnings(\"unchecked\")\n");
    sb.append("  @Override public void object(String key, Object value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      if ("Map".equals(collectionKind(c.asType()))) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = (").append(declType(c.asType()))
          .append(") value;\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");
    sb.append("  @Override public JSONArrayObserver<?> beginArray(String key) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String ck = collectionKind(c.asType());
      if ("List".equals(ck) || "Set".equals(ck)) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> { return new ")
          .append(cap(c.getSimpleName().toString())).append("ArrayObserver(); }\n");
      }
    }
    sb.append("    }\n");
    sb.append("    throw new IllegalStateException(\"arrays unsupported in this release\");\n");
    sb.append("  }\n");
    sb.append("  @SuppressWarnings(\"unchecked\")\n");
    sb.append("  @Override public void array(String key, Object value) {\n");
    sb.append("    switch (key) {\n");
    for (RecordComponentElement c : comps) {
      String ck = collectionKind(c.asType());
      if ("List".equals(ck) || "Set".equals(ck)) {
        sb.append("      case \"").append(c.getSimpleName()).append("\" -> this.")
          .append(c.getSimpleName()).append(" = (").append(declType(c.asType()))
          .append(") value;\n");
      }
    }
    appendDefaultArm(sb, strict, simpleName);
    sb.append("    }\n  }\n");

    sb.append("  @Override public ").append(simpleName).append(" finish() {\n");
    sb.append("    return new ").append(simpleName).append("(");
    for (int i = 0; i < comps.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append("this.").append(comps.get(i).getSimpleName());
    }
    sb.append(");\n  }\n");
  }

  /**
   * Emits throwing stubs for every scalar element callback that {@link #appendElementAccumulator} did not produce, so
   * the generated inner observer is a concrete class.
   */
  private void appendUnusedArrayObserverStubs(StringBuilder sb, Set<String> producedCallbacks,
                                              TypeMirror elementType) {
    String et = elementType.toString();
    String msg = "throw new JSONProcessingException(\"unexpected JSON value for element type ["
        + et + "]\")";
    if (!producedCallbacks.contains("string")) {
      sb.append("    @Override public void string(String value) { ").append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("integer")) {
      sb.append("    @Override public void integer(long value) { ").append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("bigInteger")) {
      sb.append("    @Override public void bigInteger(java.math.BigInteger value) { ")
        .append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("decimal")) {
      sb.append("    @Override public void decimal(java.math.BigDecimal value) { ")
        .append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("bool")) {
      sb.append("    @Override public void bool(boolean value) { ").append(msg).append("; }\n");
    }
  }

  /**
   * Emits throwing stubs for every member-keyed value callback that {@link #appendMapValueAccumulator} did not produce,
   * so the generated inner Map observer is a concrete class. Mirrors {@link #appendUnusedArrayObserverStubs} for the
   * {@code (String key, value)} JSONObserver shape.
   */
  private void appendUnusedMapObserverStubs(StringBuilder sb, Set<String> producedCallbacks,
                                            TypeMirror valueType) {
    String vt = valueType.toString();
    String msg = "throw new JSONProcessingException(\"unexpected JSON value for Map value type ["
        + vt + "]\")";
    if (!producedCallbacks.contains("string")) {
      sb.append("    @Override public void string(String key, String value) { ").append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("integer")) {
      sb.append("    @Override public void integer(String key, long value) { ").append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("bigInteger")) {
      sb.append("    @Override public void bigInteger(String key, java.math.BigInteger value) { ")
        .append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("decimal")) {
      sb.append("    @Override public void decimal(String key, java.math.BigDecimal value) { ")
        .append(msg).append("; }\n");
    }
    if (!producedCallbacks.contains("bool")) {
      sb.append("    @Override public void bool(String key, boolean value) { ").append(msg).append("; }\n");
    }
  }

  /**
   * JSONArrayBuilder call to append one element {@code expr} of type {@code t} (scalar/extra only).
   */
  private String arrayAppend(TypeMirror t, String expr) {
    if (isEnum(t)) {
      return ".string(" + expr + " == null ? null : " + expr + ".name())";
    }
    String s = t.toString();
    return switch (s) {
      case "java.lang.String" -> ".string(" + expr + ")";
      case "boolean" -> ".bool(" + expr + ")";
      case "java.lang.Boolean" -> ".bool(" + expr + ")";
      case "byte", "short", "int", "long" -> ".integer(" + expr + ")";
      case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
          ".integer(" + expr + " == null ? null : " + expr + ".longValue())";
      case "float", "double" -> ".decimal(java.math.BigDecimal.valueOf(" + expr + "))";
      case "java.lang.Float", "java.lang.Double" -> ".decimal(" + expr + " == null ? null : "
          + "java.math.BigDecimal.valueOf(" + expr + "))";
      case "java.math.BigInteger" -> ".bigInteger(" + expr + ")";
      case "java.math.BigDecimal" -> ".decimal(" + expr + ")";
      case "java.util.UUID" -> ".string(" + expr + " == null ? null : " + expr + ".toString())";
      case "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
           "java.time.OffsetDateTime", "java.time.ZonedDateTime",
           "java.time.Duration", "java.time.Period" -> ".string(" + expr + " == null ? null : " + expr + ".toString())";
      default -> throw new IllegalStateException("unreachable element type [" + s + "]");
    };
  }

  private String bigIntegerNarrowing(String type) {
    return switch (type) {
      case "java.math.BigDecimal" -> "new java.math.BigDecimal(value)";
      case "java.math.BigInteger" -> "value";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(Numbers.toLongExact(value))";
      case "double", "java.lang.Double" -> "value.doubleValue()";
      case "float", "java.lang.Float" -> "value.floatValue()";
      case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
      case "long", "java.lang.Long" -> "Numbers.toLongExact(value)";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(Numbers.toLongExact(value))";
      default -> null;
    };
  }

  private String builderCall(RecordComponentElement c, String accessor) {
    String key = c.getSimpleName().toString();
    String ck = collectionKind(c.asType());
    if (ck != null) {
      if ("List".equals(ck) || "Set".equals(ck)) {
        return "array(\"" + key + "\", " + accessor + " == null ? null : "
            + c.getSimpleName() + "ToJSON(" + accessor + "))";
      }
      if ("Map".equals(ck)) {
        return "object(\"" + key + "\", " + accessor + " == null ? null : "
            + c.getSimpleName() + "ToJSON(" + accessor + "))";
      }
      throw new IllegalStateException("unreachable: validated type [" + c.asType() + "]");
    }
    return memberCall(c.asType(), "\"" + key + "\"", accessor);
  }

  /**
   * Capitalizes the first character of {@code name} for use in a generated inner-class name.
   */
  private String cap(String name) {
    return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * Collects the fully-qualified names of every enum referenced anywhere in {@code t} — as the type itself or as a
   * {@code List}/{@code Set} element or {@code Map} key/value — into {@code into}. The generated code emits enum types
   * by simple name, so each must carry a matching import.
   */
  private void collectEnumImports(TypeMirror t, Set<String> into) {
    if (t == null) {
      return;
    }
    String ck = collectionKind(t);
    if (ck == null) {
      if (isEnum(t)) {
        into.add(t.toString());
      }
      return;
    }
    if (ck.equals("Map")) {
      collectEnumImports(typeArg(t, 0), into);
      collectEnumImports(typeArg(t, 1), into);
      return;
    }
    collectEnumImports(typeArg(t, 0), into);
  }

  /**
   * "List" | "Set" | "Map" for the erasure of a java.util collection, else null.
   */
  private String collectionKind(TypeMirror t) {
    if (t.getKind() != TypeKind.DECLARED) {
      return null;
    }
    String raw = processingEnv.getTypeUtils().erasure(t).toString();
    return switch (raw) {
      case "java.util.List" -> "List";
      case "java.util.Map" -> "Map";
      case "java.util.Set" -> "Set";
      default -> null;
    };
  }

  private String decimalNarrowing(String type) {
    return switch (type) {
      case "java.math.BigDecimal" -> "value";
      case "java.math.BigInteger" -> "Numbers.toBigIntegerExact(value)";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(Numbers.toLongExact(value))";
      case "double", "java.lang.Double" -> "value.doubleValue()";
      case "float", "java.lang.Float" -> "value.floatValue()";
      case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
      case "long", "java.lang.Long" -> "Numbers.toLongExact(value)";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(Numbers.toLongExact(value))";
      default -> null;
    };
  }

  /**
   * The component's declared type rendered so generated code compiles under {@code import module java.base} (e.g.
   * {@code List<String>}, {@code Set<Long>}). Element/key/value enum types reduce to their simple name (enums already
   * get a per-enum import).
   */
  private String declType(TypeMirror t) {
    String ck = collectionKind(t);
    if (ck == null) {
      return isEnum(t) ? lastSegment(t.toString()) : simpleType(t.toString());
    }
    if (ck.equals("Map")) {
      return "Map<" + declType(typeArg(t, 0)) + ", " + declType(typeArg(t, 1)) + ">";
    }
    return ck + "<" + declType(typeArg(t, 0)) + ">";
  }

  private void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
  }

  private String integerNarrowing(String type) {
    return switch (type) {
      case "java.math.BigDecimal" -> "java.math.BigDecimal.valueOf(value)";
      case "java.math.BigInteger" -> "java.math.BigInteger.valueOf(value)";
      case "byte", "java.lang.Byte" -> "Numbers.toByteExact(value)";
      case "double", "java.lang.Double" -> "(double) value";
      case "float", "java.lang.Float" -> "(float) value";
      case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
      case "long", "java.lang.Long" -> "value";
      case "short", "java.lang.Short" -> "Numbers.toShortExact(value)";
      default -> null;
    };
  }

  private boolean isEnum(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED
        && ((javax.lang.model.type.DeclaredType) t).asElement().getKind() == ElementKind.ENUM;
  }

  private boolean isStringFormType(TypeMirror t) {
    if (t.getKind() == TypeKind.DECLARED
        && ((javax.lang.model.type.DeclaredType) t).asElement().getKind() == ElementKind.ENUM) {
      return true;
    }
    return switch (t.toString()) {
      case "java.lang.String", "java.util.UUID",
           "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
           "java.time.OffsetDateTime", "java.time.ZonedDateTime",
           "java.time.Duration", "java.time.Period" -> true;
      default -> false;
    };
  }

  private boolean isSupportedComponentType(TypeMirror t) {
    if (t.getKind().isPrimitive()) {
      return true;
    }
    String ck = collectionKind(t);
    if (ck != null) {
      if (ck.equals("Map")) {
        TypeMirror k = typeArg(t, 0);
        TypeMirror v = typeArg(t, 1);
        return k != null && v != null
            && isStringFormType(k)
            && collectionKind(v) == null && isSupportedComponentType(v);
      }
      TypeMirror e = typeArg(t, 0);
      return e != null && collectionKind(e) == null && isSupportedComponentType(e);
    }
    if (t.getKind() == TypeKind.DECLARED) {
      var element = ((javax.lang.model.type.DeclaredType) t).asElement();
      if (element.getKind() == ElementKind.ENUM) {
        return true;
      }
      String name = t.toString();
      return switch (name) {
        case "java.lang.String", "java.lang.Boolean", "java.lang.Byte",
             "java.lang.Short", "java.lang.Integer", "java.lang.Long",
             "java.lang.Float", "java.lang.Double",
             "java.math.BigInteger", "java.math.BigDecimal",
             "java.util.UUID",
             "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
             "java.time.OffsetDateTime", "java.time.ZonedDateTime",
             "java.time.Duration", "java.time.Period" -> true;
        default -> false;
      };
    }
    return false;
  }

  /**
   * Expression parsing a JSON object-key String {@code s} back to a Map key of string-form type {@code t}.
   */
  private String keyFromString(TypeMirror t, String s) {
    if (isEnum(t)) {
      return "Conversions.toEnum(" + lastSegment(t.toString()) + ".class, " + s + ")";
    }
    String tn = t.toString();
    if (tn.equals("java.lang.String")) {
      return s;
    }
    if (tn.equals("java.util.UUID")) {
      return "Conversions.toUUID(" + s + ")";
    }
    return "Conversions." + stringConversion(tn) + "(" + s + ")";
  }

  /**
   * Expression converting a Map key {@code k} of string-form type {@code t} to its JSON object-key String.
   */
  private String keyToString(TypeMirror t, String k) {
    if (isEnum(t)) {
      return k + ".name()";
    }
    if (t.toString().equals("java.lang.String")) {
      return k;
    }
    return k + ".toString()";
  }

  private String lastSegment(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? fqn : fqn.substring(i + 1);
  }

  /**
   * The {@link JSONBuilder} member-append call for a scalar/extra value {@code valExpr} of type {@code t} written under
   * the object key produced by {@code keyExpr} (a Java {@code String} expression). Shared by scalar record components
   * ({@link #builderCall}) and the Map serializer, which reuses it per entry with a dynamic key expression.
   */
  private String memberCall(TypeMirror t, String keyExpr, String valExpr) {
    if (isEnum(t)) {
      return "string(" + keyExpr + ", " + valExpr + " == null ? null : " + valExpr + ".name())";
    }
    String s = t.toString();
    return switch (s) {
      case "java.lang.String" -> "string(" + keyExpr + ", " + valExpr + ")";
      case "boolean", "java.lang.Boolean" -> "bool(" + keyExpr + ", " + valExpr + ")";
      case "byte", "short", "int", "long",
           "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
          "integer(" + keyExpr + ", " + valExpr + ")";
      case "float", "double" -> "decimal(" + keyExpr + ", java.math.BigDecimal.valueOf(" + valExpr + "))";
      case "java.lang.Float", "java.lang.Double" -> "decimal(" + keyExpr + ", " + valExpr + ")";
      case "java.math.BigDecimal" -> "decimal(" + keyExpr + ", " + valExpr + ")";
      case "java.math.BigInteger" -> "bigInteger(" + keyExpr + ", " + valExpr + ")";
      case "java.util.UUID" -> "string(" + keyExpr + ", " + valExpr
          + " == null ? null : " + valExpr + ".toString())";
      case "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
           "java.time.OffsetDateTime", "java.time.ZonedDateTime",
           "java.time.Duration", "java.time.Period" ->
          "string(" + keyExpr + ", " + valExpr + " == null ? null : " + valExpr + ".toString())";
      default -> throw new IllegalStateException("unreachable: validated type [" + s + "]");
    };
  }

  /**
   * The scalar element callbacks {@link #appendElementAccumulator} emits for element type {@code t} ({@code nullValue}
   * is always produced and therefore not listed here). Invariant pair with {@link #appendElementAccumulator}: their
   * type-dispatch predicates MUST stay parallel. Tasks 3/4 inherit this.
   */
  private Set<String> producedElementCallbacks(TypeMirror t) {
    String s = t.toString();
    if (isEnum(t) || s.equals("java.lang.String") || s.equals("java.util.UUID")
        || stringConversion(s) != null) {
      return Set.of("string");
    }
    if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      return Set.of("bool");
    }
    return Set.of("integer", "bigInteger", "decimal");
  }

  private String qualified(Element e) {
    return e instanceof TypeElement t ? t.getQualifiedName().toString() : e.toString();
  }

  private boolean readOmitNulls(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann == null || ann.omitNulls();
  }

  private boolean readStrict(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann != null && ann.strict();
  }

  private String simpleType(String fqn) {
    return switch (fqn) {
      case "java.lang.Boolean" -> "Boolean";
      case "java.lang.Byte" -> "Byte";
      case "java.lang.Double" -> "Double";
      case "java.lang.Float" -> "Float";
      case "java.lang.Integer" -> "Integer";
      case "java.lang.Long" -> "Long";
      case "java.lang.Short" -> "Short";
      case "java.lang.String" -> "String";
      case "java.math.BigDecimal" -> "BigDecimal";
      case "java.math.BigInteger" -> "BigInteger";
      case "java.time.Duration" -> "Duration";
      case "java.time.Instant" -> "Instant";
      case "java.time.LocalDate" -> "LocalDate";
      case "java.time.LocalDateTime" -> "LocalDateTime";
      case "java.time.OffsetDateTime" -> "OffsetDateTime";
      case "java.time.Period" -> "Period";
      case "java.time.ZonedDateTime" -> "ZonedDateTime";
      case "java.util.UUID" -> "UUID";
      default -> fqn;
    };
  }

  private String stringConversion(String type) {
    return switch (type) {
      case "java.time.Duration" -> "toDuration";
      case "java.time.Instant" -> "toInstant";
      case "java.time.LocalDate" -> "toLocalDate";
      case "java.time.LocalDateTime" -> "toLocalDateTime";
      case "java.time.OffsetDateTime" -> "toOffsetDateTime";
      case "java.time.Period" -> "toPeriod";
      case "java.time.ZonedDateTime" -> "toZonedDateTime";
      case "java.util.UUID" -> "toUUID";
      default -> null;
    };
  }

  /**
   * The i-th type argument of a declared parameterized type, or null.
   */
  private TypeMirror typeArg(TypeMirror t, int i) {
    if (t.getKind() != TypeKind.DECLARED) {
      return null;
    }
    var args = ((javax.lang.model.type.DeclaredType) t).getTypeArguments();
    return i < args.size() ? args.get(i) : null;
  }

  private boolean validateComponents(TypeElement record) {
    boolean ok = true;
    for (RecordComponentElement c : record.getRecordComponents()) {
      String ck = collectionKind(c.asType());
      if (ck != null) {
        if (ck.equals("Map")) {
          TypeMirror k = typeArg(c.asType(), 0);
          TypeMirror v = typeArg(c.asType(), 1);
          if (k == null || !isStringFormType(k)) {
            error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported Map key type ["
                + (k == null ? "?" : k) + "] (Map key must be String, UUID, an enum, or a java.time type)");
            ok = false;
            continue;
          }

          if (v == null || collectionKind(v) != null) {
            error(c, "@JSON component [" + c.getSimpleName()
                + "] uses a nested collection as a Map value [" + (v == null ? "?" : v)
                + "] which is not supported in this release");
            ok = false;
            continue;
          }

          if (!isSupportedComponentType(v)) {
            error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported Map value type ["
                + v + "]");
            ok = false;
            continue;
          }

          continue;
        }

        TypeMirror e = typeArg(c.asType(), 0);
        if (e == null || collectionKind(e) != null) {
          error(c, "@JSON component [" + c.getSimpleName() + "] uses a nested collection ["
              + (e == null ? "?" : e) + "] which is not supported in this release");
          ok = false;
          continue;
        }

        if (!isSupportedComponentType(e)) {
          error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported "
              + ck + " element type [" + e + "]");
          ok = false;
          continue;
        }

        continue;
      }

      if (!isSupportedComponentType(c.asType())) {
        error(c, "@JSON component [" + c.getSimpleName() + "] has unsupported type ["
            + c.asType() + "] (supported: primitives, boxed primitives, String, "
            + "BigInteger, BigDecimal, enums, UUID, and java.time types)");
        ok = false;
      }
    }
    return ok;
  }
}
