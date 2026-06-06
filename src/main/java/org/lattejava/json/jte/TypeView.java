/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.base;
import module java.compiler;

import javax.lang.model.type.TypeKind;

/**
 * Template-facing facts about one declared type — the "type part" of companion generation. Exposes only what the JTE
 * templates cannot compute themselves: the things that require the processing environment ({@code Types}/{@code
 * Elements}) and a handful of category predicates. It deliberately holds <em>no</em> code-string construction — every
 * serializer/observer expression is assembled in the templates from these facts.
 *
 * @author Brian Pontarelli
 */
public final class TypeView {
  private static final Set<String> NUMERIC = Set.of(
      "byte", "short", "int", "long", "float", "double",
      "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
      "java.lang.Float", "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal");
  private final ProcessingEnvironment processingEnv;
  private final TypeMirror type;

  public TypeView(ProcessingEnvironment processingEnv, TypeMirror type) {
    this.processingEnv = processingEnv;
    this.type = type;
  }

  public TypeView element() {
    return arg(0);
  }

  public boolean isBool() {
    String n = name();
    return n.equals("boolean") || n.equals("java.lang.Boolean");
  }

  public boolean isCollection() {
    return !kind().isEmpty();
  }

  public boolean isEnum() {
    return type.getKind() == TypeKind.DECLARED
        && ((javax.lang.model.type.DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
  }

  public boolean isList() {
    return kind().equals("List");
  }

  public boolean isMap() {
    return kind().equals("Map");
  }

  public boolean isNumeric() {
    return NUMERIC.contains(name());
  }

  public boolean isPrimitive() {
    return type.getKind().isPrimitive();
  }

  public boolean isSet() {
    return kind().equals("Set");
  }

  public boolean isString() {
    return name().equals("java.lang.String");
  }

  /**
   * Whether this type is carried on the wire as a JSON string: an enum, {@code String}, {@code UUID}, or a
   * {@code java.time} type. These are exactly the legal {@code Map} key types and the {@code string(...)} observer
   * targets.
   */
  public boolean isStringForm() {
    return isEnum() || isString() || name().equals("java.util.UUID") || name().startsWith("java.time.");
  }

  public TypeView key() {
    return arg(0);
  }

  /**
   * "List" | "Set" | "Map" for a {@code java.util} collection, else the empty string (templates branch on this).
   */
  public String kind() {
    if (type.getKind() != TypeKind.DECLARED) {
      return "";
    }
    return switch (processingEnv.getTypeUtils().erasure(type).toString()) {
      case "java.util.List" -> "List";
      case "java.util.Map" -> "Map";
      case "java.util.Set" -> "Set";
      default -> "";
    };
  }

  /**
   * The type's fully-qualified name (e.g. {@code java.lang.String}, {@code int}, {@code java.util.List<...>}).
   */
  public String name() {
    return type.toString();
  }

  /**
   * The last dot-segment of {@link #name()} — the {@code import module java.base}-visible simple name for every
   * supported scalar/enum type ({@code java.math.BigDecimal} to {@code BigDecimal}, {@code int} to {@code int}).
   */
  public String simpleName() {
    String n = name();
    int dot = n.lastIndexOf('.');
    return dot < 0 ? n : n.substring(dot + 1);
  }

  public TypeView value() {
    return arg(1);
  }

  private TypeView arg(int i) {
    if (type.getKind() != TypeKind.DECLARED) {
      return null;
    }
    var args = ((javax.lang.model.type.DeclaredType) type).getTypeArguments();
    return i < args.size() ? new TypeView(processingEnv, args.get(i)) : null;
  }
}
