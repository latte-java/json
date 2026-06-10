/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.InstantFormat;
import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} member — a record component or an {@code @JSONConstructor} parameter: its
 * Java name, its wire key, the serialize read-accessor ({@link #read()}), the {@link TypeView} facts, and its
 * {@code @JSONField}/{@code @JSONCatchAll} facts. All serializer/observer code is assembled from these facts in the JTE
 * templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final boolean catchAll;
  private final String format;
  private final boolean hasReader;
  private final boolean hasWriter;
  private final boolean ignore;
  private final InstantFormat instant;
  private final String name;
  private final String read;
  private final boolean readOnly;
  private final TypeView type;
  private final String wireKey;
  private final String write;
  private final boolean writeOnly;
  private final boolean writeSetter;

  /** A record component: the serialize read-accessor is the bare {@code name()} accessor. */
  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    this(processingEnv, element, naming, element.getSimpleName() + "()");
  }

  /**
   * A general member (a record component or a constructor parameter) with an explicit serialize read-accessor suffix
   * (e.g. {@code "getFoo()"}, {@code "foo"}).
   */
  public Component(ProcessingEnvironment processingEnv, Element element, NamingStrategy naming, String read) {
    JSONField field = element.getAnnotation(JSONField.class);
    this.catchAll = element.getAnnotation(JSONCatchAll.class) != null;
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
    this.read = read;
    this.ignore = field != null && field.ignore();
    this.readOnly = field != null && field.readOnly();
    this.writeOnly = field != null && field.writeOnly();
    this.format = field == null ? "" : field.format();
    this.instant = field == null ? InstantFormat.ISO : field.instant();
    this.hasReader = !read.isEmpty();
    this.hasWriter = true;
    this.write = "";
    this.writeSetter = false;
  }

  /**
   * A JavaBean property. The {@code @JSONField}/{@code @JSONCatchAll} facts come from {@code config} (the field or an
   * accessor, resolved field-first by the processor); the wire key, type, and read/write accessors are passed
   * explicitly. {@code read}/{@code write} are empty when the property has no getter/setter+field — folded into
   * {@code hasReader}/{@code hasWriter} so a getter-only property is read-only and a setter-only property write-only.
   */
  public Component(ProcessingEnvironment processingEnv, String name, TypeMirror type, Element config,
                   NamingStrategy naming, String read, String write, boolean writeSetter) {
    JSONField field = config == null ? null : config.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    this.catchAll = config != null && config.getAnnotation(JSONCatchAll.class) != null;
    this.name = name;
    this.type = new TypeView(processingEnv, type);
    this.wireKey = override.isEmpty() ? NamingStrategies.apply(naming, name) : override;
    this.read = read;
    this.write = write;
    this.writeSetter = writeSetter;
    this.hasReader = !read.isEmpty();
    this.hasWriter = !write.isEmpty();
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
  public static String wireKey(Element element, NamingStrategy naming) {
    JSONField field = element.getAnnotation(JSONField.class);
    String override = field == null ? "" : field.name();
    return override.isEmpty() ? NamingStrategies.apply(naming, element.getSimpleName().toString()) : override;
  }

  /** Whether this member is deserialized (appears in the observer): not ignored, not read-only, and writable. */
  public boolean deserialize() {
    return !ignore && !readOnly && hasWriter;
  }

  /** The {@code Instant.ofEpoch*} factory for an epoch-instant member (deserialize). */
  public String epochFactory() {
    return instant == InstantFormat.EPOCH_MILLIS ? "ofEpochMilli" : "ofEpochSecond";
  }

  /** The {@code Instant} accessor (e.g. {@code toEpochMilli}) for an epoch-instant member (serialize). */
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

  /** The generated static formatter field name for a formatted member. */
  public String formatterField() {
    return name + "Formatter";
  }

  public boolean isCatchAll() {
    return catchAll;
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

  /** The serialize read-accessor suffix, such that {@code value.<read()>} reads the member (e.g. {@code getFoo()}). */
  public String read() {
    return read;
  }

  /** Whether this member is serialized (appears in {@code toJSON}): not ignored, not write-only, and readable. */
  public boolean serialize() {
    return !ignore && !writeOnly && hasReader;
  }

  public TypeView type() {
    return type;
  }

  public String wireKey() {
    return wireKey;
  }

  /** The deserialize write target's Java name — a setter (e.g. {@code setFoo}) or a public field name. */
  public String write() {
    return write;
  }

  /** Whether {@link #write()} is a setter method (vs. a public field). */
  public boolean writeIsSetter() {
    return writeSetter;
  }
}
