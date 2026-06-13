/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.base;

/**
 * Top-level template model for one generated {@code <Type>JSON} companion. Carries the fixed scaffolding facts plus the
 * {@link Component} list the JTE templates iterate. Built by {@code JSONProcessor.generateCompanion}; consumed by
 * {@code companion.jte} and its partials. Holds no code-string logic — even the default switch arm and constructor
 * argument list are assembled in the templates from {@link #strict()} and {@link #components()}.
 *
 * @author Brian Pontarelli
 */
public final class CompanionView {
  private final boolean beanConstructed;
  private final String companionName;
  private final String companionPackage;
  private final List<Component> components;
  private final String discriminatorKey;
  private final String discriminatorValue;
  private final List<String> enumImports;
  private final String internalPackage;
  private final boolean omitNulls;
  private final String qualifiedType;
  private final String simpleName;
  private final boolean strict;

  public CompanionView(String companionPackage, String internalPackage, String qualifiedType, String simpleName,
                       String companionName, boolean omitNulls, boolean strict, List<String> enumImports,
                       List<Component> components, String discriminatorKey, String discriminatorValue,
                       boolean beanConstructed) {
    this.companionPackage = companionPackage;
    this.internalPackage = internalPackage;
    this.qualifiedType = qualifiedType;
    this.simpleName = simpleName;
    this.companionName = companionName;
    this.omitNulls = omitNulls;
    this.strict = strict;
    this.enumImports = enumImports;
    this.components = components;
    this.discriminatorKey = discriminatorKey;
    this.discriminatorValue = discriminatorValue;
    this.beanConstructed = beanConstructed;
  }

  /** Whether {@code finish()} constructs via a no-arg constructor + setters/fields (a JavaBean) rather than a constructor call. */
  public boolean beanConstructed() {
    return beanConstructed;
  }

  /** The Java name of the {@code @JSONCatchAll} component, or {@code ""} when the type has none. */
  public String catchAll() {
    return components.stream().filter(Component::isCatchAll).findFirst().map(Component::name).orElse("");
  }

  /** The serialize read-accessor of the catch-all member (e.g. {@code extras()}/{@code getExtras()}), or {@code ""}. */
  public String catchAllRead() {
    return components.stream().filter(Component::isCatchAll).findFirst().map(Component::read).orElse("");
  }

  public List<Component> collectionComponents() {
    return components.stream().filter(c -> c.type().isCollection() && !c.isCatchAll()).toList();
  }

  public String companionName() {
    return companionName;
  }

  public String companionPackage() {
    return companionPackage;
  }

  public List<Component> components() {
    return components;
  }

  public String discriminatorKey() {
    return discriminatorKey;
  }

  public String discriminatorValue() {
    return discriminatorValue;
  }

  public List<String> enumImports() {
    return enumImports;
  }

  /** Whether any non-catch-all member is a dynamic {@code Map<String, Object>} (gates the {@code Any*Observer} imports). */
  public boolean hasDynamicMap() {
    return components.stream().anyMatch(c -> c.type().isDynamicMap());
  }

  /** Whether any member uses a typed collection plan (a non-dynamic-map collection member). */
  public boolean hasPlan() {
    return components.stream().anyMatch(c -> c.type().isCollection() && !c.isCatchAll() && !c.type().isDynamicMap());
  }

  public String internalPackage() {
    return internalPackage;
  }

  public boolean omitNulls() {
    return omitNulls;
  }

  public String qualifiedType() {
    return qualifiedType;
  }

  public String simpleName() {
    return simpleName;
  }

  public boolean strict() {
    return strict;
  }

  /** The components that participate in typed serialize/deserialize codegen — everything except the catch-all. */
  public List<Component> typedComponents() {
    return components.stream().filter(c -> !c.isCatchAll()).toList();
  }
}
