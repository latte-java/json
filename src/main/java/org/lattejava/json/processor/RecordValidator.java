/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

/** Validates an {@code @JSON} record's canonical components. */
public final class RecordValidator extends AbstractValidator {
  private final ClassMemberDiscovery members;

  public RecordValidator(ProcessingEnvironment processingEnv, ClassMemberDiscovery members) {
    super(processingEnv);
    this.members = members;
  }

  public boolean validate(TypeElement type) {
    if (!members.jsonConstructors(type).isEmpty()) {
      error(type, "@JSONConstructor on record [" + type.getQualifiedName()
          + "] is redundant; records use their canonical constructor");
      return false;
    }
    if (!validateMembers(type, type.getRecordComponents())) {
      return false;
    }
    return requireDiscriminatorInterface(type);
  }
}
