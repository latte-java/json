/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;

import java.nio.file.Path;

/**
 * Build-time generator main: precompiles every {@code src/main/jte/*.jte} template into Java classes under
 * {@link org.lattejava.json.jte.generated} in {@code build/classes/main}. Invoked from {@code project.latte} via
 * {@code java.run} after {@code compileMain} and before {@code jar}. Never runs at consumer-build time.
 *
 * @author Brian Pontarelli
 */
public final class Generate {
  public static final String GENERATED_PACKAGE = "org.lattejava.json.jte.generated";

  private Generate() {
  }

  public static void main(String[] args) {
    Path source = Path.of("src/main/jte");
    Path target = Path.of("build/classes/main");
    TemplateEngine engine = TemplateEngine.create(
        new DirectoryCodeResolver(source), target, ContentType.Plain, Generate.class.getClassLoader(),
        GENERATED_PACKAGE);
    engine.setTrimControlStructures(true);
    engine.precompileAll();
    System.out.println("[jte] precompiled templates from [" + source + "] into [" + target + "/"
        + GENERATED_PACKAGE.replace('.', '/') + "]");
  }
}
