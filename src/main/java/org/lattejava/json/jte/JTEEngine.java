/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;

import module java.base;

/**
 * Renders the build-time-precompiled JTE templates at consumer-build time (inside the annotation processor). Uses
 * {@link TemplateEngine#createPrecompiled} — pure classloading of the baked-in {@link org.lattejava.json.jte.generated}
 * classes, no compiler invoked. Never emits JTE into the consumer; only the rendered Java source string escapes.
 *
 * @author Brian Pontarelli
 */
public final class JTEEngine {
  private static TemplateEngine engine;

  private JTEEngine() {
  }

  public static String render(String template, Map<String, Object> model) {
    // JTE's precompiled RuntimeTemplateLoader resolves template classes through the *context* classloader (captured
    // when the engine is built), not the one passed to createPrecompiled. Under javac the processing thread's context
    // loader cannot see this module's baked-in template classes, so pin it to this class's loader while the engine is
    // created and used, then restore it.
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try {
      thread.setContextClassLoader(JTEEngine.class.getClassLoader());
      StringOutput output = new StringOutput();
      engine().render(template, model, output);
      return reindent(output.toString());
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private static synchronized TemplateEngine engine() {
    if (engine == null) {
      engine = TemplateEngine.createPrecompiled(
          null, ContentType.Plain, JTEEngine.class.getClassLoader(), Generate.GENERATED_PACKAGE);
    }
    return engine;
  }

  /**
   * Re-indents rendered Java by brace depth (two spaces per level). JTE's {@code trimControlStructures} flattens the
   * lines emitted inside {@code @for}/{@code @if} blocks to column 0; this restores uniform indentation independent of
   * how the templates author whitespace. Pure cosmetics — indentation never affects {@code javac} (the generated
   * source has no text blocks), so this cannot change behavior. Block comments pass through with their star alignment.
   */
  static String reindent(String source) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    boolean inBlockComment = false;
    for (String raw : source.split("\n", -1)) {
      String line = raw.strip();
      if (line.isEmpty()) {
        out.append('\n');
        continue;
      }
      if (inBlockComment) {
        out.append("  ".repeat(depth)).append(line.startsWith("*") ? " " : "").append(line).append('\n');
        if (line.contains("*/")) {
          inBlockComment = false;
        }
        continue;
      }
      if (line.startsWith("/*")) {
        out.append("  ".repeat(depth)).append(line).append('\n');
        if (!line.contains("*/")) {
          inBlockComment = true;
        }
        continue;
      }

      int opens = 0;
      int closes = 0;
      int leadingCloses = 0;
      boolean seenNonClose = false;
      boolean inString = false;
      boolean inChar = false;
      char prev = 0;
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (inString) {
          if (ch == '"' && prev != '\\') {
            inString = false;
          }
        } else if (inChar) {
          if (ch == '\'' && prev != '\\') {
            inChar = false;
          }
        } else if (ch == '"') {
          inString = true;
        } else if (ch == '\'') {
          inChar = true;
        } else if (ch == '{') {
          opens++;
          seenNonClose = true;
        } else if (ch == '}') {
          closes++;
          if (!seenNonClose && opens == 0) {
            leadingCloses++;
          }
        } else if (!Character.isWhitespace(ch)) {
          seenNonClose = true;
        }
        prev = ch;
      }

      out.append("  ".repeat(Math.max(0, depth - leadingCloses))).append(line).append('\n');
      depth = Math.max(0, depth + opens - closes);
    }
    return out.toString().stripTrailing() + "\n";
  }
}
