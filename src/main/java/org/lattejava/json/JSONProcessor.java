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
      String obs = cap(c.getSimpleName().toString()) + "ArrayObserver";
      structural.append(Template.of(Templates.ARRAY_OBSERVER).render(Map.ofEntries(
          Map.entry("field", c.getSimpleName().toString()),
          Map.entry("declType", dt),
          Map.entry("arrayAppend", arrayAppend(elem, "e")),
          Map.entry("obs", obs),
          Map.entry("accImpl", ck.equals("Set") ? "java.util.LinkedHashSet" : "java.util.ArrayList"),
          Map.entry("accumulator", elementAccumulator(elem, "acc")),
          Map.entry("stubs", unusedArrayObserverStubs(producedElementCallbacks(elem), elem)))));
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

    // Net-zero indentation contract: the structural and observers fragments are emitted at the
    // legacy absolute indentation a class member sits at (members 2sp, bodies 4sp, switch/case arms
    // 6sp). This dedent removes the leading 2sp, then the standalone {{body}} hole in COMPANION
    // re-adds 2sp (net zero) so members land back at 2sp inside the companion class. Because of
    // this, template {{...}} content holes (accumulator/stubs/cases) are authored at column 0 so
    // the standalone-hole reindent passes their already-indented fragment lines through verbatim.
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

  /**
   * Maps {@code comps} to {@code "      case \"name\" -> this.name = "} prefixed arms, skipping any component whose
   * {@code narrowing} function returns {@code null} (not applicable to this numeric callback).
   */
  private String narrowingCases(List<RecordComponentElement> comps,
                                Function<String, String> narrowing) {
    return Template.join(comps, c -> {
      String narrow = narrowing.apply(c.asType().toString());
      if (narrow == null) {
        return null;
      }
      return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName() + " = " + narrow + ";";
    }, "\n");
  }

  /**
   * Inner-observer body that accumulates the JSON value into {@code target} (a List/Set) for element type t. Invariant
   * pair with {@link #producedElementCallbacks}: their type-dispatch predicates MUST stay parallel (a callback emitted
   * here must be reported there, and vice versa). Tasks 3/4 inherit this.
   */
  private String elementAccumulator(TypeMirror t, String target) {
    String s = t.toString();
    List<String> lines = new ArrayList<>();
    if (isEnum(t)) {
      lines.add("    @Override public void string(String value) { " + target
          + ".add(Conversions.toEnum(" + lastSegment(s) + ".class, value)); }");
    } else if (s.equals("java.lang.String")) {
      lines.add("    @Override public void string(String value) { " + target + ".add(value); }");
    } else if (s.equals("java.util.UUID")) {
      lines.add("    @Override public void string(String value) { " + target
          + ".add(Conversions.toUUID(value)); }");
    } else if (stringConversion(s) != null) {
      lines.add("    @Override public void string(String value) { " + target
          + ".add(Conversions." + stringConversion(s) + "(value)); }");
    } else if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      lines.add("    @Override public void bool(boolean value) { " + target + ".add(value); }");
    } else {
      lines.add("    @Override public void integer(long value) { " + target + ".add("
          + integerNarrowing(s) + "); }");
      lines.add("    @Override public void bigInteger(java.math.BigInteger value) { " + target
          + ".add(" + bigIntegerNarrowing(s) + "); }");
      lines.add("    @Override public void decimal(java.math.BigDecimal value) { " + target
          + ".add(" + decimalNarrowing(s) + "); }");
    }
    lines.add("    @Override public void nullValue() { " + target + ".add(null); }");
    return String.join("\n", lines);
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
    String obs = cap(c.getSimpleName().toString()) + "MapObserver";
    sb.append(Template.of(Templates.MAP_OBSERVER).render(Map.ofEntries(
        Map.entry("field", c.getSimpleName().toString()),
        Map.entry("declType", dt),
        Map.entry("omitNulls", String.valueOf(omitNulls)),
        Map.entry("memberCall", memberCall(v, keyToString(k, "en.getKey()"), "en.getValue()")),
        Map.entry("obs", obs),
        Map.entry("accumulator", mapValueAccumulator(k, v)),
        Map.entry("stubs", unusedMapObserverStubs(producedElementCallbacks(v), v)),
        Map.entry("keyFromString", keyFromString(k, "key")))));
  }

  /**
   * Inner Map-observer body that accumulates each JSON object member into {@code map} for value type {@code v}, parsing
   * the member name back to a key of type {@code k} via {@link #keyFromString}. Invariant pair with
   * {@link #producedElementCallbacks}: their type-dispatch predicates MUST stay parallel (a value callback emitted here
   * must be reported there, and vice versa), the same discipline {@link #elementAccumulator} follows for
   * List/Set.
   */
  private String mapValueAccumulator(TypeMirror k, TypeMirror v) {
    String key = keyFromString(k, "key");
    String s = v.toString();
    List<String> lines = new ArrayList<>();
    if (isEnum(v)) {
      lines.add("    @Override public void string(String key, String value) { map.put(" + key
          + ", Conversions.toEnum(" + lastSegment(s) + ".class, value)); }");
    } else if (s.equals("java.lang.String")) {
      lines.add("    @Override public void string(String key, String value) { map.put(" + key
          + ", value); }");
    } else if (s.equals("java.util.UUID")) {
      lines.add("    @Override public void string(String key, String value) { map.put(" + key
          + ", Conversions.toUUID(value)); }");
    } else if (stringConversion(s) != null) {
      lines.add("    @Override public void string(String key, String value) { map.put(" + key
          + ", Conversions." + stringConversion(s) + "(value)); }");
    } else if (s.equals("boolean") || s.equals("java.lang.Boolean")) {
      lines.add("    @Override public void bool(String key, boolean value) { map.put(" + key
          + ", value); }");
    } else {
      lines.add("    @Override public void integer(String key, long value) { map.put(" + key
          + ", " + integerNarrowing(s) + "); }");
      lines.add("    @Override public void bigInteger(String key, java.math.BigInteger value) { map.put("
          + key + ", " + bigIntegerNarrowing(s) + "); }");
      lines.add("    @Override public void decimal(String key, java.math.BigDecimal value) { map.put("
          + key + ", " + decimalNarrowing(s) + "); }");
    }
    return String.join("\n", lines);
  }

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
      if (tt.equals("java.lang.String")) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName() + " = value;";
      } else if (isEnum(c.asType())) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName()
            + " = Conversions.toEnum(" + lastSegment(tt) + ".class, value);";
      } else if (stringConversion(tt) != null) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName()
            + " = Conversions." + stringConversion(tt) + "(value);";
      }
      return null;
    }, "\n");

    String boolCases = Template.join(comps, c -> {
      String t = c.asType().toString();
      if (t.equals("boolean") || t.equals("java.lang.Boolean")) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName() + " = value;";
      }
      return null;
    }, "\n");

    String nullCases = Template.join(comps, c -> {
      if (c.asType().getKind().isPrimitive()) {
        return "      case \"" + c.getSimpleName() + "\" -> throw new JSONProcessingException("
            + "\"null for primitive field [" + c.getSimpleName() + "]\");";
      }
      return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName() + " = null;";
    }, "\n");

    String beginObjectCases = Template.join(comps, c -> {
      if ("Map".equals(collectionKind(c.asType()))) {
        return "      case \"" + c.getSimpleName() + "\" -> { return new "
            + cap(c.getSimpleName().toString()) + "MapObserver(); }";
      }
      return null;
    }, "\n");

    String objectCases = Template.join(comps, c -> {
      if ("Map".equals(collectionKind(c.asType()))) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName()
            + " = (" + declType(c.asType()) + ") value;";
      }
      return null;
    }, "\n");

    String beginArrayCases = Template.join(comps, c -> {
      String ck = collectionKind(c.asType());
      if ("List".equals(ck) || "Set".equals(ck)) {
        return "      case \"" + c.getSimpleName() + "\" -> { return new "
            + cap(c.getSimpleName().toString()) + "ArrayObserver(); }";
      }
      return null;
    }, "\n");

    String arrayCases = Template.join(comps, c -> {
      String ck = collectionKind(c.asType());
      if ("List".equals(ck) || "Set".equals(ck)) {
        return "      case \"" + c.getSimpleName() + "\" -> this." + c.getSimpleName()
            + " = (" + declType(c.asType()) + ") value;";
      }
      return null;
    }, "\n");

    String ctorArgs = Template.join(comps, c -> "this." + c.getSimpleName(), ", ");

    sb.append(Template.of(Templates.OBSERVER_BODY).render(Map.ofEntries(
        Map.entry("stringCases", stringCases),
        Map.entry("integerCases", narrowingCases(comps, this::integerNarrowing)),
        Map.entry("bigIntegerCases", narrowingCases(comps, this::bigIntegerNarrowing)),
        Map.entry("decimalCases", narrowingCases(comps, this::decimalNarrowing)),
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

  /**
   * Emits throwing stubs for every scalar element callback that {@link #elementAccumulator} did not produce, so
   * the generated inner observer is a concrete class.
   */
  private String unusedArrayObserverStubs(Set<String> producedCallbacks, TypeMirror elementType) {
    String et = elementType.toString();
    String msg = "throw new JSONProcessingException(\"unexpected JSON value for element type ["
        + et + "]\")";
    List<String> lines = new ArrayList<>();
    if (!producedCallbacks.contains("string")) {
      lines.add("    @Override public void string(String value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("integer")) {
      lines.add("    @Override public void integer(long value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("bigInteger")) {
      lines.add("    @Override public void bigInteger(java.math.BigInteger value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("decimal")) {
      lines.add("    @Override public void decimal(java.math.BigDecimal value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("bool")) {
      lines.add("    @Override public void bool(boolean value) { " + msg + "; }");
    }
    return String.join("\n", lines);
  }

  /**
   * Emits throwing stubs for every member-keyed value callback that {@link #mapValueAccumulator} did not produce,
   * so the generated inner Map observer is a concrete class. Mirrors {@link #unusedArrayObserverStubs} for the
   * {@code (String key, value)} JSONObserver shape.
   */
  private String unusedMapObserverStubs(Set<String> producedCallbacks, TypeMirror valueType) {
    String vt = valueType.toString();
    String msg = "throw new JSONProcessingException(\"unexpected JSON value for Map value type ["
        + vt + "]\")";
    List<String> lines = new ArrayList<>();
    if (!producedCallbacks.contains("string")) {
      lines.add("    @Override public void string(String key, String value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("integer")) {
      lines.add("    @Override public void integer(String key, long value) { " + msg + "; }");
    }
    if (!producedCallbacks.contains("bigInteger")) {
      lines.add("    @Override public void bigInteger(String key, java.math.BigInteger value) { "
          + msg + "; }");
    }
    if (!producedCallbacks.contains("decimal")) {
      lines.add("    @Override public void decimal(String key, java.math.BigDecimal value) { "
          + msg + "; }");
    }
    if (!producedCallbacks.contains("bool")) {
      lines.add("    @Override public void bool(String key, boolean value) { " + msg + "; }");
    }
    return String.join("\n", lines);
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
   * The scalar element callbacks {@link #elementAccumulator} emits for element type {@code t} ({@code nullValue}
   * is always produced and therefore not listed here). Invariant pair with {@link #elementAccumulator}: their
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
