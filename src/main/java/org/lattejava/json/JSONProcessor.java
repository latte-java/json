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
  static final List<String> HELPERS = List.of(
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
    // Filled in by Task 3.
  }

  void generateCompanion(TypeElement record, ModuleElement module) {
    // Filled in by Task 4 and Task 5.
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
