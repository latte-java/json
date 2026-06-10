/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Build-time conversion of a Java identifier to a JSON wire key per a {@link NamingStrategy}. Splits the identifier
 * into words (acronym-aware), lowercases each, and rejoins per the strategy. Build-time only — never a runtime helper,
 * never added to {@code JSONProcessor.HELPERS}. Public so the {@code org.lattejava.json.tests} module can unit-test it.
 *
 * @author Brian Pontarelli
 */
public final class NamingStrategies {
  private NamingStrategies() {
  }

  /**
   * Converts {@code javaName} to its wire key under {@code strategy}. {@code IDENTITY} returns the input unchanged.
   */
  public static String apply(NamingStrategy strategy, String javaName) {
    if (strategy == NamingStrategy.IDENTITY) {
      return javaName;
    }
    List<String> words = splitWords(javaName);
    return switch (strategy) {
      case KEBAB_CASE -> joinLower(words, "-");
      case PASCAL_CASE -> joinCapitalized(words, true);
      case CAMEL_CASE -> joinCapitalized(words, false);
      default -> joinLower(words, "_"); // SNAKE_CASE (IDENTITY handled above)
    };
  }

  /**
   * Splits a Java identifier into words. A boundary precedes an uppercase letter that follows a lowercase letter or
   * digit ({@code userName} to {@code user|Name}), and the final uppercase of an acronym run when followed by a
   * lowercase ({@code HTTPStatus} to {@code HTTP|Status}). Digits attach to the preceding word.
   */
  static List<String> splitWords(String s) {
    List<String> words = new ArrayList<>();
    int start = 0;
    for (int i = 1; i < s.length(); i++) {
      char prev = s.charAt(i - 1);
      char cur = s.charAt(i);
      boolean camelBoundary = Character.isUpperCase(cur)
          && (Character.isLowerCase(prev) || Character.isDigit(prev));
      boolean acronymBoundary = Character.isUpperCase(cur) && Character.isUpperCase(prev)
          && i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
      if (camelBoundary || acronymBoundary) {
        words.add(s.substring(start, i));
        start = i;
      }
    }
    words.add(s.substring(start));
    return words;
  }

  private static String capitalize(String word) {
    if (word.isEmpty()) {
      return word;
    }
    String lower = word.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private static String joinCapitalized(List<String> words, boolean capitalizeFirst) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      sb.append(i == 0 && !capitalizeFirst ? words.get(i).toLowerCase(Locale.ROOT) : capitalize(words.get(i)));
    }
    return sb.toString();
  }

  private static String joinLower(List<String> words, String separator) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        sb.append(separator);
      }
      sb.append(words.get(i).toLowerCase(Locale.ROOT));
    }
    return sb.toString();
  }
}
