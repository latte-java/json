/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.jte;

import module java.compiler;

/**
 * Template-facing view of one {@code @JSON} record component: its name plus the {@link TypeView} facts about its
 * declared type. All serializer/observer code is assembled from these facts in the JTE templates — there is no
 * code-string logic here.
 *
 * @author Brian Pontarelli
 */
public final class Component {
  private final String name;
  private final TypeView type;

  public Component(ProcessingEnvironment processingEnv, RecordComponentElement element) {
    this.name = element.getSimpleName().toString();
    this.type = new TypeView(processingEnv, element.asType());
  }

  public String name() {
    return name;
  }

  public TypeView type() {
    return type;
  }
}
