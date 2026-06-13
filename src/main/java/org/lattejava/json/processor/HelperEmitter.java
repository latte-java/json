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
      "JSONParser", "JSONPlan", "JSONPlanArrayObserver", "JSONPlanMapObserver",
      "JSONPolymorphicObserver", "JSONProcessingException", "Numbers",
      "SkipArrayObserver", "SkipObserver");
  private boolean emitted = false;
  private final ProcessingEnvironment processingEnv;

  public HelperEmitter(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  /** Emits the helpers into {@code module}'s {@code .internal} once; subsequent calls no-op. */
  public void emit(ModuleElement module) {
    if (emitted || module == null || module.isUnnamed()) {
      return;
    }
    // Latch before emitting: as in the original, a mid-loop failure still reports its error but does not retry.
    emitted = true;

    String pkg = module.getQualifiedName() + ".internal";
    for (String helper : HELPERS) {
      String resource = "/org/lattejava/json/internal/" + helper + ".java";
      String body;
      try (InputStream in = HelperEmitter.class.getResourceAsStream(resource)) {
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
}
