/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSON;
import org.lattejava.json.JSONField;
import org.lattejava.json.JSONTypeInfo;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.NamingStrategy;
import org.lattejava.json.jte.Component;

/** Validates an {@code @JSON}-annotated sealed {@code @JSONTypeInfo} interface and its permitted subtypes. */
public final class PolymorphicValidator extends AbstractValidator {
  private final ClassMemberDiscovery members;

  public PolymorphicValidator(ProcessingEnvironment processingEnv, ClassMemberDiscovery members) {
    super(processingEnv);
    this.members = members;
  }

  /** Reports (and returns true) if {@code wireKey} equals the discriminator {@code property}. */
  private boolean collides(TypeElement iface, String property, String memberName, String wireKey, TypeElement sub) {
    if (wireKey.equals(property)) {
      error(iface, "discriminator property [" + property + "] collides with the JSON key of member ["
          + memberName + "] on subtype [" + sub.getSimpleName() + "]");
      return true;
    }
    return false;
  }

  public boolean validate(TypeElement iface) {
    boolean ok = true;
    if (!iface.getModifiers().contains(javax.lang.model.element.Modifier.SEALED)) {
      error(iface, "@JSONTypeInfo type [" + iface.getQualifiedName() + "] must be a sealed interface");
      return false;
    }

    String property = iface.getAnnotation(JSONTypeInfo.class).property();
    Map<String, String> seenValues = new HashMap<>();
    for (TypeMirror permitted : iface.getPermittedSubclasses()) {
      TypeElement sub = ProcessorFacts.asTypeElement(permitted);
      if (sub.getAnnotation(JSON.class) == null) {
        error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
            + iface.getQualifiedName() + "] must be annotated @JSON");
        ok = false;
        continue;
      }

      if (sub.getKind() != ElementKind.RECORD && sub.getKind() != ElementKind.CLASS) {
        error(iface, "permitted subtype [" + sub.getQualifiedName() + "] of @JSONTypeInfo type ["
            + iface.getQualifiedName() + "] must be a record or class");
        ok = false;
        continue;
      }

      String value = ProcessorFacts.discriminatorValue(sub);
      String prior = seenValues.put(value, sub.getSimpleName().toString());
      if (prior != null) {
        error(iface, "duplicate discriminator value [" + value + "] on subtypes [" + prior + "] and ["
            + sub.getSimpleName() + "] of @JSONTypeInfo type [" + iface.getQualifiedName() + "]");
        ok = false;
      }

      NamingStrategy subNaming = ProcessorFacts.naming(sub);
      if (sub.getKind() == ElementKind.RECORD) {
        for (RecordComponentElement c : sub.getRecordComponents()) {
          ok &= !collides(iface, property, c.getSimpleName().toString(), Component.wireKey(c, subNaming), sub);
        }
      } else if (members.isBean(sub)) {
        for (ClassMemberDiscovery.BeanProperty bp : members.discoverProperties(sub)) {
          JSONField pf = bp.config() == null ? null : bp.config().getAnnotation(JSONField.class);
          String wireKey = pf != null && !pf.name().isEmpty() ? pf.name()
              : NamingStrategies.apply(subNaming, bp.name());
          ok &= !collides(iface, property, bp.name(), wireKey, sub);
        }
      } else {
        for (VariableElement p : members.jsonConstructors(sub).getFirst().getParameters()) {
          ok &= !collides(iface, property, p.getSimpleName().toString(), Component.wireKey(p, subNaming), sub);
        }
      }
    }
    return ok;
  }
}
