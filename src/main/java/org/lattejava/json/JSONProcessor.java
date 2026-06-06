/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import org.lattejava.json.jte.Component;
import org.lattejava.json.jte.CompanionView;
import org.lattejava.json.jte.JTEEngine;
import org.lattejava.json.jte.TypeView;

import module java.base;
import module java.compiler;

/**
 * Annotation processor for {@link JSON @JSON}. Generates a serialization/deserialization companion for each annotated
 * record. Supported component types: primitives and their boxed forms, {@code String}, {@code BigInteger},
 * {@code BigDecimal}, any {@code enum}, {@code UUID}, and the ISO-8601 {@code java.time} types ({@code Instant},
 * {@code LocalDate}, {@code LocalDateTime}, {@code OffsetDateTime}, {@code ZonedDateTime}, {@code Duration},
 * {@code Period}). Emits the runtime helper set into the consumer's {@code <module>.internal} package and a per-record
 * observer companion into {@code <typePackage>.internal}.
 *
 * <p>Companion source is rendered by the build-time-precompiled JTE templates in {@code src/main/jte} (see
 * {@link JTEEngine}). The processor only builds the {@link CompanionView} model from {@link TypeView} facts and
 * validates component types; all serializer/observer code is assembled in the templates.
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
      String resource = "/org/lattejava/json/internal/" + helper + ".java";
      String body;
      try (InputStream in = JSONProcessor.class.getResourceAsStream(resource)) {
        if (in == null) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Missing helper source resource [" + resource + "]");
          return;
        }
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException ioe) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed reading helper source [" + resource + "]: " + ioe.getMessage());
        return;
      }

      String rewritten = body.replace("package org.lattejava.json;", "package " + pkg + ";");
      try {
        var file = processingEnv.getFiler().createSourceFile(pkg + "." + helper);
        try (Writer w = file.openWriter()) {
          w.write(rewritten);
        }
      } catch (IOException ioe) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed writing helper [" + pkg + "." + helper + "]: " + ioe.getMessage());
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

    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    for (RecordComponentElement c : record.getRecordComponents()) {
      components.add(new Component(processingEnv, c));
      collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
    }

    CompanionView view = new CompanionView(companionPkg, internalPkg, qualifiedType, simpleName, companion,
        readOmitNulls(record), readStrict(record), List.copyOf(enumImports), components);
    String source = JTEEngine.render("companion.jte", Map.of("view", view));

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
   * Collects the fully-qualified name of every enum reachable in {@code type} — itself or a {@code List}/{@code Set}
   * element or {@code Map} key/value — into {@code into}, so the companion can import each by simple name.
   */
  private void collectEnums(TypeView type, Set<String> into) {
    if (type == null) {
      return;
    }
    if (type.isCollection()) {
      if (type.isMap()) {
        collectEnums(type.key(), into);
        collectEnums(type.value(), into);
      } else {
        collectEnums(type.element(), into);
      }
      return;
    }
    if (type.isEnum()) {
      into.add(type.name());
    }
  }

  private void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
  }

  /**
   * Whether {@code type} is a component type the processor can serialize: a primitive/boxed/{@code BigInteger}/
   * {@code BigDecimal} number, a boolean, a string-form type (enum/{@code String}/{@code UUID}/{@code java.time}), or a
   * single-level {@code List}/{@code Set}/{@code Map} of those (Map keys must be string-form).
   */
  private boolean isSupportedComponentType(TypeView type) {
    if (type.isCollection()) {
      if (type.isMap()) {
        TypeView k = type.key();
        TypeView v = type.value();
        return k != null && v != null && k.isStringForm() && !v.isCollection() && isSupportedComponentType(v);
      }
      TypeView e = type.element();
      return e != null && !e.isCollection() && isSupportedComponentType(e);
    }
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm() || type.isNested();
  }

  private String notJSON(RecordComponentElement c, TypeView t) {
    return "@JSON component [" + c.getSimpleName() + "] references record type [" + t.name()
        + "] which is not @JSON-annotated; add @JSON to it or remove the component";
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
      TypeView type = new TypeView(processingEnv, c.asType());
      if (type.isCollection()) {
        if (type.isMap()) {
          TypeView k = type.key();
          TypeView v = type.value();
          if (k == null || !k.isStringForm()) {
            error(c, "@JSON component [" + c.getSimpleName() + "] has an unsupported Map key type ["
                + (k == null ? "?" : k.name()) + "] (Map key must be String, UUID, an enum, or a java.time type)");
            ok = false;
            continue;
          }

          if (v == null || v.isCollection()) {
            error(c, "@JSON component [" + c.getSimpleName()
                + "] uses a nested collection as a Map value [" + (v == null ? "?" : v.name())
                + "] which is not supported in this release");
            ok = false;
            continue;
          }

          if (!isSupportedComponentType(v)) {
            error(c, v.isRecord() && !v.isNested() ? notJSON(c, v)
                : "@JSON component [" + c.getSimpleName() + "] has an unsupported Map value type ["
                  + v.name() + "]");
            ok = false;
            continue;
          }

          continue;
        }

        TypeView e = type.element();
        if (e == null || e.isCollection()) {
          error(c, "@JSON component [" + c.getSimpleName() + "] uses a nested collection ["
              + (e == null ? "?" : e.name()) + "] which is not supported in this release");
          ok = false;
          continue;
        }

        if (!isSupportedComponentType(e)) {
          error(c, e.isRecord() && !e.isNested() ? notJSON(c, e)
              : "@JSON component [" + c.getSimpleName() + "] has an unsupported "
                + type.kind() + " element type [" + e.name() + "]");
          ok = false;
          continue;
        }

        continue;
      }

      if (!isSupportedComponentType(type)) {
        error(c, type.isRecord() && !type.isNested() ? notJSON(c, type)
            : "@JSON component [" + c.getSimpleName() + "] has unsupported type ["
              + type.name() + "] (supported: primitives, boxed primitives, String, "
              + "BigInteger, BigDecimal, enums, UUID, java.time types, and @JSON records)");
        ok = false;
      }
    }
    return ok;
  }
}
