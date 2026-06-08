/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} record component: its Java name, its wire key, the {@link TypeView} facts
 * about its declared type, and its {@code @JSONField} policy facts (direction, format, instant). All serializer/observer
 * code is assembled from these facts in the JTE templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final String format;
  private final boolean ignore;
  private final InstantFormat instant;
  private final String name;
  private final boolean readOnly;
  private final TypeView type;
  private final String wireKey;
  private final boolean writeOnly;

  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
    this.ignore = field != null && field.ignore();
    this.readOnly = field != null && field.readOnly();
    this.writeOnly = field != null && field.writeOnly();
    this.format = field == null ? "" : field.format();
    this.instant = field == null ? InstantFormat.ISO : field.instant();
  }

  /**
   * Resolves the JSON wire key for {@code element}: an explicit {@code @JSONField(name)} verbatim, else {@code naming}
   * applied to the Java name.
   */
  public static String wireKey(RecordComponentElement element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    return override.isEmpty() ? NamingStrategies.apply(naming, element.getSimpleName().toString()) : override;
  }

  /** Whether this component is deserialized (appears in the observer): not ignored and not read-only. */
  public boolean deserialize() {
    return !ignore && !readOnly;
  }

  /** The {@code Instant.ofEpoch*} factory for an epoch-instant component (deserialize). */
  public String epochFactory() {
    return instant == InstantFormat.EPOCH_MILLIS ? "ofEpochMilli" : "ofEpochSecond";
  }

  /** The {@code Instant} accessor (e.g. {@code toEpochMilli}) for an epoch-instant component (serialize). */
  public String epochMethod() {
    return instant == InstantFormat.EPOCH_MILLIS ? "toEpochMilli" : "getEpochSecond";
  }

  public String format() {
    return format;
  }

  /** Whether the format pattern's {@code DateTimeFormatter} needs a zone to resolve (true only for {@code Instant}). */
  public boolean formatNeedsZone() {
    return type.simpleName().equals("Instant");
  }

  /** The simple type name used for the formatter's {@code parse(value, <Type>::from)} query and field declaration. */
  public String formatType() {
    return type.simpleName();
  }

  /** The generated static formatter field name for a formatted component. */
  public String formatterField() {
    return name + "Formatter";
  }

  public boolean isEpochInstant() {
    return instant != InstantFormat.ISO;
  }

  public boolean isFormatted() {
    return !format.isEmpty();
  }

  public String name() {
    return name;
  }

  /** Whether this component is serialized (appears in {@code toJSON}): not ignored and not write-only. */
  public boolean serialize() {
    return !ignore && !writeOnly;
  }

  public TypeView type() {
    return type;
  }

  public String wireKey() {
    return wireKey;
  }
}
