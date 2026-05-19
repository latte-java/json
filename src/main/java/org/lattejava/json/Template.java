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
