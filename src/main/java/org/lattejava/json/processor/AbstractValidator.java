/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONField;
import org.lattejava.json.JSONRaw;
import org.lattejava.json.JSONSubtype;
import org.lattejava.json.NamingStrategy;
import org.lattejava.json.jte.Component;
import org.lattejava.json.jte.TypeView;

/** Base for the validator hierarchy; holds the shared validation primitives over a type's members. */
public abstract class AbstractValidator {
  protected final ProcessingEnvironment processingEnv;

  protected AbstractValidator(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  /** Validates {@code type}, reporting diagnostics; returns whether generation may proceed. */
  public abstract boolean validate(TypeElement type);

  protected void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
  }

  /**
   * Whether {@code type} is a non-collection component type the processor can serialize: a primitive/boxed/
   * {@code BigInteger}/{@code BigDecimal} number, a boolean, a string-form type (enum/{@code String}/{@code UUID}/
   * {@code java.time}), or a type with a generated companion. Callers handle collections before calling this.
   */
  protected boolean isSupportedComponentType(TypeView type) {
    return type.isPrimitive() || type.isNumeric() || type.isBool() || type.isStringForm() || type.hasCompanion();
  }

  protected String notJSON(Element c, TypeView t) {
    return "@JSON member [" + c.getSimpleName() + "] references type [" + t.name()
        + "] which is not @JSON-annotated; add @JSON to it or remove the member";
  }

  /** Rejects a record/class carrying {@code @JSONSubtype} without an implemented {@code @JSONTypeInfo} interface. */
  protected boolean requireDiscriminatorInterface(TypeElement type) {
    if (type.getAnnotation(JSONSubtype.class) != null && ProcessorFacts.discriminatorInterface(type) == null) {
      error(type, "@JSONSubtype on [" + type.getQualifiedName() + "] requires an implemented @JSONTypeInfo interface");
      return false;
    }
    return true;
  }

  /**
   * Recursively validates a collection member's type tree: string-form keys at every Map level, no raw or
   * wildcard type arguments, and supported leaf types. {@code Map<String, Object>} (the dynamic-map shape)
   * is only legal as a member's direct type, never nested.
   */
  private boolean validateCollectionTree(Element at, CharSequence name, TypeView t) {
    if (t.isMap()) {
      TypeView k = t.key();
      if (k == null) {
        error(at, "@JSON member [" + name + "] uses a raw or wildcard Map which is not supported");
        return false;
      }
      if (!k.isStringForm()) {
        error(at, "@JSON member [" + name + "] has an unsupported Map key type [" + k.name()
            + "] (Map key must be String, UUID, an enum, or a java.time type)");
        return false;
      }
      TypeView v = t.value();
      if (v.isCollection()) {
        return validateCollectionTree(at, name, v);
      }
      if (v.isObject()) {
        error(at, "@JSON member [" + name + "] has an unsupported Map value type [java.lang.Object] "
            + "(Map<String, Object> is only supported as a member's direct type)");
        return false;
      }
      if (!isSupportedComponentType(v)) {
        error(at, v.isRecord() && !v.isNested() ? notJSON(at, v)
            : "@JSON member [" + name + "] has an unsupported Map value type [" + v.name() + "]");
        return false;
      }
      return true;
    }
    TypeView e = t.element();
    if (e == null) {
      error(at, "@JSON member [" + name + "] uses a raw or wildcard " + t.kind() + " which is not supported");
      return false;
    }
    if (e.isCollection()) {
      return validateCollectionTree(at, name, e);
    }
    if (!isSupportedComponentType(e)) {
      error(at, e.isRecord() && !e.isNested() ? notJSON(at, e)
          : "@JSON member [" + name + "] has an unsupported " + t.kind() + " element type [" + e.name() + "]");
      return false;
    }
    return true;
  }

  protected boolean validateMembers(TypeElement type, List<? extends Element> members) {
    boolean ok = true;
    int catchAllCount = 0;
    int rawCount = 0;
    NamingStrategy naming = ProcessorFacts.naming(type);
    Map<String, String> wireKeys = new HashMap<>();
    for (Element c : members) {
      if (c.getAnnotation(JSONCatchAll.class) != null) {
        catchAllCount++;
        TypeView ca = new TypeView(processingEnv, c.asType());
        if (!ca.isMap() || ca.key() == null || !ca.key().name().equals("java.lang.String")
            || ca.value() == null || !ca.value().name().equals("java.lang.Object")) {
          error(c, "@JSONCatchAll member [" + c.getSimpleName() + "] must be of type Map<String, Object>");
          ok = false;
        }
        if (c.getAnnotation(JSONField.class) != null) {
          error(c, "@JSONCatchAll member [" + c.getSimpleName() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        if (c.getAnnotation(JSONRaw.class) != null) {
          error(c, "@JSONRaw member [" + c.getSimpleName() + "] cannot also be annotated @JSONCatchAll");
          ok = false;
        }
        continue;
      }
      if (c.getAnnotation(JSONRaw.class) != null) {
        rawCount++;
        TypeView rt = new TypeView(processingEnv, c.asType());
        if (!rt.isString()) {
          error(c, "@JSONRaw member [" + c.getSimpleName() + "] must be of type String but found [" + rt.name() + "]");
          ok = false;
        }
        if (c.getAnnotation(JSONField.class) != null) {
          error(c, "@JSONRaw member [" + c.getSimpleName() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        if (c.getAnnotation(JSONCatchAll.class) != null) {
          error(c, "@JSONRaw member [" + c.getSimpleName() + "] cannot also be annotated @JSONCatchAll");
          ok = false;
        }
        continue;
      }
      String wireKey = Component.wireKey(c, naming);
      if (wireKey.chars().anyMatch(ch -> ch == '"' || ch == '\\' || ch < 0x20)) {
        error(c, "JSON key [" + wireKey + "] for member [" + c.getSimpleName()
            + "] contains an invalid character (quote, backslash, or control character)");
        ok = false;
        continue;
      }
      String prior = wireKeys.put(wireKey, c.getSimpleName().toString());
      if (prior != null) {
        error(c, "duplicate JSON key [" + wireKey + "] on members [" + prior + "] and [" + c.getSimpleName() + "]");
        ok = false;
      }
      JSONField policy = c.getAnnotation(JSONField.class);
      TypeView mt = new TypeView(processingEnv, c.asType());
      if (policy != null && !validatePolicy(c, c.getSimpleName(), policy, mt)) {
        ok = false;
        continue;
      }
      // Mirrors Component.serialize()/deserialize() minus their reader/writer terms; no Component is built yet at
      // validation time. A record component always has both, and a @JSONConstructor parameter's reader is checked
      // separately by ClassValidator.validateClass. ignore() cannot reach here alongside asString() — validatePolicy
      // rejects that pairing above.
      Direction direction = new Direction(policy == null || !policy.writeOnly(),
          policy == null || !policy.readOnly());
      if (!validateType(c, c.getSimpleName(), mt, policy != null && policy.asString(), direction)) {
        ok = false;
      }
    }
    if (catchAllCount > 1) {
      error(type, "type [" + type.getQualifiedName() + "] declares [" + catchAllCount
          + "] @JSONCatchAll members; at most one is allowed");
      ok = false;
    }
    if (rawCount > 1) {
      error(type, "type [" + type.getQualifiedName() + "] declares [" + rawCount
          + "] @JSONRaw members; at most one is allowed");
      ok = false;
    }
    return ok;
  }

  /** Validates a member's {@code @JSONField} policy (direction/format/instant conflicts) against its type. */
  protected boolean validatePolicy(Element at, CharSequence name, JSONField policy, TypeView mt) {
    if (policy.readOnly() && policy.writeOnly()) {
      error(at, "@JSONField member [" + name + "] is both readOnly and writeOnly (equivalent to ignore)");
      return false;
    }
    if (policy.ignore() && (!policy.name().isEmpty() || !policy.format().isEmpty()
        || policy.readOnly() || policy.writeOnly() || policy.instant() != InstantFormat.ISO || policy.asString())) {
      error(at, "@JSONField member [" + name + "] combines ignore with another attribute, which has no effect");
      return false;
    }
    if (policy.asString() && (!policy.format().isEmpty() || policy.instant() != InstantFormat.ISO)) {
      error(at, "@JSONField member [" + name + "] sets asString with format or instant; those apply to java.time "
          + "types, which asString cannot be used on");
      return false;
    }
    String typeName = mt.name();
    boolean formatType = typeName.equals("java.time.LocalDate") || typeName.equals("java.time.LocalDateTime")
        || typeName.equals("java.time.OffsetDateTime") || typeName.equals("java.time.ZonedDateTime")
        || typeName.equals("java.time.Instant");
    if (!policy.format().isEmpty()) {
      if (!formatType) {
        error(at, "@JSONField(format) on member [" + name + "] requires a LocalDate, LocalDateTime, "
            + "OffsetDateTime, ZonedDateTime, or Instant type, not [" + typeName + "]");
        return false;
      }
      if (policy.format().indexOf('"') >= 0 || policy.format().indexOf('\\') >= 0) {
        error(at, "@JSONField(format) pattern [" + policy.format() + "] on member [" + name
            + "] contains a quote or backslash");
        return false;
      }
      try {
        DateTimeFormatter.ofPattern(policy.format());
      } catch (IllegalArgumentException iae) {
        error(at, "@JSONField(format) pattern [" + policy.format() + "] on member [" + name
            + "] is not a valid DateTimeFormatter pattern: " + iae.getMessage());
        return false;
      }
    }
    if (policy.instant() != InstantFormat.ISO) {
      if (!typeName.equals("java.time.Instant")) {
        error(at, "@JSONField(instant) on member [" + name + "] requires an Instant type, not [" + typeName + "]");
        return false;
      }
      if (!policy.format().isEmpty()) {
        error(at, "@JSONField member [" + name + "] sets both instant and format (integer vs string)");
        return false;
      }
    }
    return true;
  }

  /**
   * Validates the {@code @JSONField(asString)} contract on {@code mt}: an unsupported, non-collection type declaring
   * the half of the conversion each direction actually uses. The requirement is per-direction because the generated
   * code is — a member that is never deserialized emits no constructor call, and one that is never serialized emits
   * no {@code toString()} call, so demanding the unused half would reject types that would have worked.
   * <p>
   * This is the only place the opt-in is enforced — {@code TypeView.isStringConvertible()} is structural and would
   * amount to auto-detection on its own, which is wrong because a single-{@code String} constructor is often not a
   * parse constructor.
   */
  protected boolean validateStringConvertible(Element at, CharSequence name, TypeView mt, Direction direction) {
    if (mt.isCollection()) {
      error(at, "@JSONField(asString) on member [" + name + "] cannot be used on collection type [" + mt.name()
          + "]; it converts the member's own type, not its elements");
      return false;
    }
    if (isSupportedComponentType(mt)) {
      error(at, "@JSONField(asString) on member [" + name + "] has no effect on type [" + mt.name()
          + "], which is already supported");
      return false;
    }
    boolean ok = true;
    if (direction.deserialized() && !mt.hasStringConstructor()) {
      error(at, "@JSONField(asString) on member [" + name + "] requires type [" + mt.name()
          + "] to declare a public constructor taking a single String");
      ok = false;
    }
    if (direction.serialized() && !mt.hasDeclaredToString()) {
      error(at, "@JSONField(asString) on member [" + name + "] requires type [" + mt.name()
          + "] to declare toString(); the inherited Object.toString() is not a JSON representation");
      ok = false;
    }
    return ok;
  }

  /**
   * Validates that a member's type is serializable (collection/map/element constraints + scalar support).
   * {@code asString} carries the member's {@code @JSONField(asString)} opt-in, which swaps the closed supported-type
   * list for the string-convertible contract; {@code direction} then decides which half of that contract applies.
   */
  protected boolean validateType(Element at, CharSequence name, TypeView mt, boolean asString, Direction direction) {
    if (asString) {
      return validateStringConvertible(at, name, mt, direction);
    }
    if (mt.isCollection()) {
      // dynamic map: Map<String, Object> carries arbitrary JSON values, read/written via the Any* helpers.
      // Only legal as the member's direct type, so it is recognized here, before the recursive walk.
      if (mt.isDynamicMap()) {
        return true;
      }
      return validateCollectionTree(at, name, mt);
    }
    if (!isSupportedComponentType(mt)) {
      error(at, mt.isRecord() && !mt.isNested() ? notJSON(at, mt)
          : "@JSON member [" + name + "] has unsupported type [" + mt.name() + "] (supported: primitives, "
            + "boxed primitives, String, BigInteger, BigDecimal, enums, UUID, java.time types, and @JSON records "
            + "and classes)");
      return false;
    }
    return true;
  }

  /**
   * Which directions a member participates in, which decides which half of a per-direction contract it must satisfy.
   * Derived from {@code @JSONField(readOnly/writeOnly)} and, for a JavaBean property, from which accessors exist.
   */
  protected record Direction(boolean serialized, boolean deserialized) {
  }
}
