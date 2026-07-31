/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONField;
import org.lattejava.json.JSONRaw;
import org.lattejava.json.NamingStrategies;
import org.lattejava.json.jte.TypeView;

/** Validates an {@code @JSON} class — a JavaBean or a {@code @JSONConstructor}-bearing class. */
public final class ClassValidator extends AbstractValidator {
  private final ClassMemberDiscovery members;

  public ClassValidator(ProcessingEnvironment processingEnv, ClassMemberDiscovery members) {
    super(processingEnv);
    this.members = members;
  }

  public boolean validate(TypeElement type) {
    boolean ok = members.isBean(type) ? validateBean(type) : validateClass(type);
    return ok && requireDiscriminatorInterface(type);
  }

  private boolean validateBean(TypeElement type) {
    boolean hasNoArg = javax.lang.model.util.ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
        .anyMatch(c -> c.getParameters().isEmpty()
            && c.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC));
    if (!hasNoArg) {
      error(type, "@JSON class [" + type.getQualifiedName()
          + "] requires a public no-arg constructor, or a @JSONConstructor");
      return false;
    }
    List<ClassMemberDiscovery.BeanProperty> properties = members.discoverProperties(type);
    if (properties.isEmpty()) {
      error(type, "@JSON class [" + type.getQualifiedName() + "] has no serializable properties");
      return false;
    }
    boolean ok = true;
    Map<String, String> wireKeys = new HashMap<>();
    int catchAllCount = 0;
    int rawCount = 0;
    for (ClassMemberDiscovery.BeanProperty p : properties) {
      if (p.read().isEmpty() && p.write().isEmpty()) {
        error(p.at(), "member [" + p.name() + "] on [" + type.getQualifiedName()
            + "] has neither a usable reader nor writer; add a getter/setter/public field");
        ok = false;
        continue;
      }
      TypeView mt = new TypeView(processingEnv, p.type());
      JSONField policy = p.config() == null ? null : p.config().getAnnotation(JSONField.class);
      boolean isCatchAll = p.config() != null && p.config().getAnnotation(JSONCatchAll.class) != null;
      if (isCatchAll) {
        catchAllCount++;
        if (!mt.isMap() || mt.key() == null || !mt.key().name().equals("java.lang.String")
            || mt.value() == null || !mt.value().name().equals("java.lang.Object")) {
          error(p.at(), "@JSONCatchAll member [" + p.name() + "] must be of type Map<String, Object>");
          ok = false;
        }
        if (policy != null) {
          error(p.at(), "@JSONCatchAll member [" + p.name() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        if (p.raw() != null) {
          error(p.at(), "@JSONRaw member [" + p.name() + "] cannot also be annotated @JSONCatchAll");
          ok = false;
        }
        continue;
      }
      // p.raw()/p.catchAll()/p.field() are resolved independently across every candidate element (field, getter,
      // isGetter, setter), unlike p.config() above which stops at the first annotated candidate. That independence
      // is what catches a conflict even when @JSONRaw and @JSONCatchAll/@JSONField sit on different physical
      // elements of the same property (e.g. @JSONCatchAll on the field, @JSONRaw on the getter) — p.config() alone
      // would only ever see one of the two annotations.
      boolean isRaw = p.raw() != null;
      if (isRaw) {
        rawCount++;
        if (!mt.isString()) {
          error(p.at(), "@JSONRaw member [" + p.name() + "] must be of type String but found [" + mt.name() + "]");
          ok = false;
        }
        if (p.field() != null) {
          error(p.at(), "@JSONRaw member [" + p.name() + "] cannot also be annotated @JSONField");
          ok = false;
        }
        if (p.catchAll() != null) {
          error(p.at(), "@JSONRaw member [" + p.name() + "] cannot also be annotated @JSONCatchAll");
          ok = false;
        }
        if (p.write().isEmpty()) {
          error(p.at(), "@JSONRaw member [" + p.name() + "] on [" + type.getQualifiedName()
              + "] has no usable writer; add a setter or make the field public");
          ok = false;
        }
        continue;
      }
      boolean ignore = policy != null && policy.ignore();
      boolean serialized = !ignore && !(policy != null && policy.writeOnly()) && !p.read().isEmpty();
      boolean deserialized = !ignore && !(policy != null && policy.readOnly()) && !p.write().isEmpty();
      if (!ignore && !serialized && !deserialized) {
        error(p.at(), "@JSONField direction on member [" + p.name() + "] on [" + type.getQualifiedName()
            + "] leaves it neither serialized nor deserialized (writeOnly with no setter, or readOnly with no getter)");
        ok = false;
        continue;
      }
      String wireKey = policy != null && !policy.name().isEmpty() ? policy.name()
          : NamingStrategies.apply(ProcessorFacts.naming(type), p.name());
      if (wireKey.chars().anyMatch(ch -> ch == '"' || ch == '\\' || ch < 0x20)) {
        error(p.at(), "JSON key [" + wireKey + "] for member [" + p.name()
            + "] contains an invalid character (quote, backslash, or control character)");
        ok = false;
        continue;
      }
      String prior = wireKeys.put(wireKey, p.name());
      if (prior != null) {
        error(p.at(), "duplicate JSON key [" + wireKey + "] on members [" + prior + "] and [" + p.name() + "]");
        ok = false;
      }
      if (policy != null && !validatePolicy(p.at(), p.name(), policy, mt)) {
        ok = false;
        continue;
      }
      if (!validateType(p.at(), p.name(), mt, policy != null && policy.asString(),
          new Direction(serialized, deserialized))) {
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

  private boolean validateClass(TypeElement type) {
    // Only reached for a class WITH @JSONConstructor; a no-@JSONConstructor class is a bean (validateBean).
    List<ExecutableElement> ctors = members.jsonConstructors(type);
    if (ctors.size() > 1) {
      error(type, "@JSON class [" + type.getQualifiedName()
          + "] has [" + ctors.size() + "] @JSONConstructor constructors; exactly one is allowed");
      return false;
    }
    ExecutableElement ctor = ctors.getFirst();
    if (!ctor.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) {
      error(ctor, "@JSONConstructor on [" + type.getQualifiedName()
          + "] must be public; the generated companion calls it from a separate package");
      return false;
    }
    List<? extends VariableElement> params = ctor.getParameters();
    boolean ok = validateMembers(type, params);
    for (VariableElement p : params) {
      JSONField pf = p.getAnnotation(JSONField.class);
      // Mirrors Component.serialize() (!ignore && !writeOnly); no Component is built yet at validation time.
      boolean serialized = pf == null || (!pf.ignore() && !pf.writeOnly());
      if (serialized && p.getAnnotation(JSONCatchAll.class) == null && p.getAnnotation(JSONRaw.class) == null
          && members.resolveRead(type, p).isEmpty()) {
        error(p, "no usable reader for member [" + p.getSimpleName() + "] on [" + type.getQualifiedName()
            + "]; add a getFoo()/isFoo()/foo()/public field, or mark the parameter @JSONField(writeOnly = true)");
        ok = false;
      }
    }
    return ok;
  }
}
