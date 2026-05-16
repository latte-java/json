/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;
import module java.compiler;

import javax.lang.model.type.TypeKind;

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
  public static final List<String> HELPERS = List.of(
      "AnyArrayObserver", "AnyObjectObserver", "JSONArrayObserver",
      "JSONBuilder", "JSONObjectHandler", "JSONObserver", "JSONParser",
      "JSONPolymorphicObserver", "JSONProcessingException", "Numbers",
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

    for (RecordComponentElement c : comps) {
      sb.append("  private ").append(c.asType()).append(' ')
        .append(c.getSimpleName()).append(";\n");
    }
    sb.append('\n');

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

    sb.append("  public static ").append(simpleName).append(" fromJSON(String json) {\n");
    sb.append("    var observer = new ").append(companion).append("();\n");
    sb.append("    return new JSONParser().parse(json, observer);\n");
    sb.append("  }\n\n");
    sb.append("  public static ").append(simpleName).append(" fromJSON(byte[] json) {\n");
    sb.append("    var observer = new ").append(companion).append("();\n");
    sb.append("    return new JSONParser().parse(json, observer);\n");
    sb.append("  }\n\n");

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
    sb.append("    throw new IllegalStateException(\"no nested object in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void object(String key, Object value) {}\n");
    sb.append("  @Override public JSONArrayObserver<?> beginArray(String key) {\n");
    sb.append("    throw new IllegalStateException(\"no array in this release\");\n");
    sb.append("  }\n");
    sb.append("  @Override public void array(String key, Object value) {}\n");
    sb.append("  @Override public ").append(record.getSimpleName())
      .append(" finish() { return null; }\n");
  }

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

  private void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
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
