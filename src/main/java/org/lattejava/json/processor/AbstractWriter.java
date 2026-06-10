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
