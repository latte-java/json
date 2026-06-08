/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

import org.lattejava.json.JSONField;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;

/**
 * Template-facing view of one {@code @JSON} record component: its Java name, its wire key (JSON key), and the
 * {@link TypeView} facts about its declared type. All serializer/observer code is assembled from these facts in the
 * JTE templates — there is no code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final String name;
  private final TypeView type;
  private final String wireKey;

  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element, NamingStrategy naming) {
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
    this.wireKey = wireKey(element, naming);
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

  public String name() {
    return name;
  }

  public TypeView type() {
    return type;
  }

  public String wireKey() {
    return wireKey;
  }
}
