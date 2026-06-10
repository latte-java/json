/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.base;
import module java.compiler;

import javax.lang.model.type.TypeKind;

import org.lattejava.json.JSON;
import org.lattejava.json.JSONTypeInfo;

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

  /**
   * The reference form to write for this type in generated source: the fully-qualified name for a type with a
   * generated companion (a nested {@code @JSON} record or a polymorphic {@code @JSON} interface), so no import is
   * needed and same-simple-name collisions cannot occur, else the simple name.
   */
  public String decl() {
    return hasCompanion() ? name() : simpleName();
  }

  public TypeView element() {
    return arg(0);
  }

  /**
   * Whether this type has a generated {@code <X>JSON} companion to dispatch to — a nested {@code @JSON} record
   * (an object companion) or a polymorphic {@code @JSON} sealed interface (a {@code JSONPolymorphicObserver}). Both
   * are serialized via {@code <X>JSON.toJSON} and deserialized by returning {@code new <X>JSON()} from a
   * {@code beginObject}.
   */
  public boolean hasCompanion() {
    return isNested() || isPolymorphic();
  }

  public boolean isBool() {
    String n = name();
    return n.equals("boolean") || n.equals("java.lang.Boolean");
  }

  public boolean isCollection() {
    return !kind().isEmpty();
  }

  /**
   * Whether this type is a dynamic map — {@code Map<String, Object>} — whose values are arbitrary JSON captured at
   * their natural Java shapes. Read through {@code AnyObjectObserver}, written through {@code JSONBuilder.any}.
   */
  public boolean isDynamicMap() {
    return isMap() && key() != null && key().isString() && value() != null && value().isObject();
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

  /**
   * Whether this type is a record or class annotated with {@code @JSON} — a nested type the processor can recurse into.
   * Note: {@code @JSON} is {@code SOURCE}-retained, so a type from a compiled dependency reports {@code false} here and
   * is rejected as un-annotated, which is also the cross-module restriction.
   */
  public boolean isNested() {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    return (element.getKind() == ElementKind.RECORD || element.getKind() == ElementKind.CLASS)
        && element.getAnnotation(JSON.class) != null;
  }

  public boolean isNumeric() {
    return NUMERIC.contains(name());
  }

  /** Whether this type is exactly {@code java.lang.Object}. */
  public boolean isObject() {
    return name().equals("java.lang.Object");
  }

  /**
   * Whether this type is a polymorphic {@code @JSON} hierarchy: an interface carrying both {@code @JSON} and
   * {@code @JSONTypeInfo}. Its companion is a {@code JSONPolymorphicObserver}.
   */
  public boolean isPolymorphic() {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    return element.getKind() == ElementKind.INTERFACE
        && element.getAnnotation(JSON.class) != null
        && element.getAnnotation(JSONTypeInfo.class) != null;
  }

  public boolean isPrimitive() {
    return type.getKind().isPrimitive();
  }

  /**
   * Whether this type is a record (annotated or not) — used to give a precise "not @JSON-annotated" diagnostic.
   */
  public boolean isRecord() {
    return type.getKind() == TypeKind.DECLARED
        && ((javax.lang.model.type.DeclaredType) type).asElement().getKind() == ElementKind.RECORD;
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
   * The fully-qualified name of the generated companion for this type, e.g. {@code demo.internal.AddressJSON} (a nested
   * record's object companion) or {@code demo.internal.PetJSON} (a polymorphic interface's dispatcher). Only meaningful
   * when {@link #hasCompanion()} is true.
   */
  public String nestedCompanion() {
    Element element = ((javax.lang.model.type.DeclaredType) type).asElement();
    String pkg = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
    return (pkg.isEmpty() ? "internal" : pkg + ".internal") + "." + simpleName() + "JSON";
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
