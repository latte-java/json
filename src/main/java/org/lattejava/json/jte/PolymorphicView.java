/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.base;

/**
 * Top-level template model for one generated polymorphic dispatcher companion ({@code <Type>JSON implements
 * JSONPolymorphicObserver}). Carries the discriminator key plus the ordered permitted subtypes, each with its
 * discriminator value and fully-qualified type/companion names. Built by {@code JSONProcessor.generatePolymorphic};
 * consumed by {@code polymorphic.jte}. Holds no code-string logic.
 *
 * @author Brian Pontarelli
 */
public final class PolymorphicView {
  private final String companionName;
  private final String companionPackage;
  private final String discriminatorKey;
  private final String internalPackage;
  private final String qualifiedType;
  private final String simpleName;
  private final List<Subtype> subtypes;

  public PolymorphicView(String companionPackage, String internalPackage, String qualifiedType, String simpleName,
                         String companionName, String discriminatorKey, List<Subtype> subtypes) {
    this.companionPackage = companionPackage;
    this.internalPackage = internalPackage;
    this.qualifiedType = qualifiedType;
    this.simpleName = simpleName;
    this.companionName = companionName;
    this.discriminatorKey = discriminatorKey;
    this.subtypes = subtypes;
  }

  public String companionName() {
    return companionName;
  }

  public String companionPackage() {
    return companionPackage;
  }

  public String discriminatorKey() {
    return discriminatorKey;
  }

  public String internalPackage() {
    return internalPackage;
  }

  public String qualifiedType() {
    return qualifiedType;
  }

  public String simpleName() {
    return simpleName;
  }

  public List<Subtype> subtypes() {
    return subtypes;
  }

  /** One permitted subtype: its discriminator value plus fully-qualified type and companion names. */
  public record Subtype(String value, String typeFqn, String companionFqn) {
  }
}
