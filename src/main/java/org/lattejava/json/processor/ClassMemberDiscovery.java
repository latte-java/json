/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSONCatchAll;
import org.lattejava.json.JSONConstructor;
import org.lattejava.json.JSONField;

/** Resolves a class's members: {@code @JSONConstructor} parameters or JavaBean properties. Stateless query object. */
public final class ClassMemberDiscovery {
  private final ProcessingEnvironment processingEnv;

  public ClassMemberDiscovery(ProcessingEnvironment processingEnv) {
    this.processingEnv = processingEnv;
  }

  private static String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** JavaBeans-style decapitalize: leaves an all-caps run (URL, ID) alone, else lowercases the first letter. */
  private static String decapitalize(String s) {
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
      return s;
    }
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  /** Discovers a bean's properties (base-class first), excluding static and transient. */
  public List<BeanProperty> discoverProperties(TypeElement type) {
    List<TypeElement> chain = superclassChain(type);
    Map<String, VariableElement> fieldsByName = new LinkedHashMap<>();
    Set<String> transientNames = new HashSet<>();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (TypeElement c : chain) {
      for (VariableElement f : javax.lang.model.util.ElementFilter.fieldsIn(c.getEnclosedElements())) {
        var mods = f.getModifiers();
        if (mods.contains(javax.lang.model.element.Modifier.STATIC)) {
          continue;
        }
        String fn = f.getSimpleName().toString();
        fieldsByName.putIfAbsent(fn, f);
        if (mods.contains(javax.lang.model.element.Modifier.TRANSIENT)) {
          transientNames.add(fn);
          continue;
        }
        if (mods.contains(javax.lang.model.element.Modifier.PUBLIC) || f.getAnnotation(JSONField.class) != null
            || f.getAnnotation(JSONCatchAll.class) != null) {
          names.add(fn);
        }
      }
      for (ExecutableElement m : javax.lang.model.util.ElementFilter.methodsIn(c.getEnclosedElements())) {
        if (!m.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
            || m.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
          continue;
        }
        String prop = accessorProperty(m);
        if (prop != null) {
          names.add(prop);
        }
      }
    }
    names.removeAll(transientNames);
    List<BeanProperty> properties = new ArrayList<>();
    for (String name : names) {
      properties.add(beanProperty(type, name, fieldsByName.get(name)));
    }
    return properties;
  }

  public boolean isBean(TypeElement type) {
    return type.getKind() == ElementKind.CLASS && jsonConstructors(type).isEmpty();
  }

  public List<ExecutableElement> jsonConstructors(TypeElement type) {
    return javax.lang.model.util.ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
        .filter(c -> c.getAnnotation(JSONConstructor.class) != null)
        .toList();
  }

  /**
   * The serialize read-accessor suffix for a class member named after constructor parameter {@code param}: the first
   * public match of {@code getFoo()}, {@code isFoo()} (boolean only), {@code foo()}, or public field {@code foo}; or
   * {@code ""} when none (the member must then be write-only/ignored).
   */
  public String resolveRead(TypeElement clazz, VariableElement param) {
    String name = param.getSimpleName().toString();
    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    String typeName = param.asType().toString();
    boolean booleanType = typeName.equals("boolean") || typeName.equals("java.lang.Boolean");
    boolean getter = false;
    boolean isGetter = false;
    boolean bare = false;
    boolean field = false;
    for (Element m : processingEnv.getElementUtils().getAllMembers(clazz)) {
      if (!m.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)) {
        continue;
      }
      if (m.getKind() == ElementKind.METHOD && ((ExecutableElement) m).getParameters().isEmpty()) {
        String mn = m.getSimpleName().toString();
        if (mn.equals("get" + cap)) {
          getter = true;
        } else if (booleanType && mn.equals("is" + cap)) {
          isGetter = true;
        } else if (mn.equals(name)) {
          bare = true;
        }
      } else if (m.getKind() == ElementKind.FIELD && m.getSimpleName().toString().equals(name)) {
        field = true;
      }
    }
    if (getter) {
      return "get" + cap + "()";
    }
    if (isGetter) {
      return "is" + cap + "()";
    }
    if (bare) {
      return name + "()";
    }
    if (field) {
      return name;
    }
    return "";
  }

  /** The property name a method exposes as a prefixed accessor, or {@code null} if it is not one. */
  private String accessorProperty(ExecutableElement m) {
    String n = m.getSimpleName().toString();
    if (m.getParameters().isEmpty() && n.length() > 3 && n.startsWith("get")
        && m.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID) {
      return decapitalize(n.substring(3));
    }
    if (m.getParameters().isEmpty() && n.length() > 2 && n.startsWith("is")
        && m.getReturnType().getKind() == javax.lang.model.type.TypeKind.BOOLEAN) {
      return decapitalize(n.substring(2));
    }
    if (m.getParameters().size() == 1 && n.length() > 3 && n.startsWith("set")) {
      return decapitalize(n.substring(3));
    }
    return null;
  }

  /** Resolves one property's accessors, type, and config element. */
  private BeanProperty beanProperty(TypeElement type, String name, VariableElement backingField) {
    String cap = capitalize(name);
    ExecutableElement getter = null;
    ExecutableElement isGetter = null;
    ExecutableElement setter = null;
    VariableElement publicField = null;
    for (Element m : processingEnv.getElementUtils().getAllMembers(type)) {
      if (!m.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
          || m.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
        continue;
      }
      if (m.getKind() == ElementKind.METHOD) {
        ExecutableElement em = (ExecutableElement) m;
        String mn = em.getSimpleName().toString();
        if (getter == null && em.getParameters().isEmpty() && mn.equals("get" + cap)
            && em.getReturnType().getKind() != javax.lang.model.type.TypeKind.VOID) {
          getter = em;
        } else if (isGetter == null && em.getParameters().isEmpty() && mn.equals("is" + cap)
            && em.getReturnType().getKind() == javax.lang.model.type.TypeKind.BOOLEAN) {
          isGetter = em;
        } else if (setter == null && em.getParameters().size() == 1 && mn.equals("set" + cap)) {
          setter = em;
        }
      } else if (publicField == null && m.getKind() == ElementKind.FIELD
          && m.getSimpleName().toString().equals(name)) {
        publicField = (VariableElement) m;
      }
    }
    String read = getter != null ? "get" + cap + "()"
        : isGetter != null ? "is" + cap + "()"
        : publicField != null ? name : "";
    String write;
    boolean writeSetter;
    if (setter != null) {
      write = "set" + cap;
      writeSetter = true;
    } else if (publicField != null) {
      write = name;
      writeSetter = false;
    } else {
      write = "";
      writeSetter = false;
    }
    TypeMirror tm = getter != null ? getter.getReturnType()
        : isGetter != null ? isGetter.getReturnType()
        : setter != null ? setter.getParameters().getFirst().asType()
        : publicField != null ? publicField.asType()
        : backingField.asType();
    Element config = configElement(backingField, getter, isGetter, setter);
    Element at = config != null ? config
        : backingField != null ? backingField
        : getter != null ? getter : setter != null ? setter : type;
    return new BeanProperty(name, tm, config, at, read, write, writeSetter);
  }

  /** The first of {@code candidates} bearing @JSONField or @JSONCatchAll, else null. */
  private Element configElement(Element... candidates) {
    for (Element e : candidates) {
      if (e != null && (e.getAnnotation(JSONField.class) != null || e.getAnnotation(JSONCatchAll.class) != null)) {
        return e;
      }
    }
    return null;
  }

  /** The class + its superclasses up to (excluding) Object, ordered base-class first. */
  private List<TypeElement> superclassChain(TypeElement type) {
    List<TypeElement> chain = new ArrayList<>();
    TypeElement t = type;
    while (t != null && !t.getQualifiedName().contentEquals("java.lang.Object")) {
      chain.add(t);
      TypeMirror sup = t.getSuperclass();
      t = sup.getKind() == javax.lang.model.type.TypeKind.DECLARED
          ? (TypeElement) ((javax.lang.model.type.DeclaredType) sup).asElement() : null;
    }
    Collections.reverse(chain);
    return chain;
  }

  /** A resolved JavaBean property: its name, type, the @JSONField/@JSONCatchAll-bearing element (or null), an element
   *  to attach errors to, and the read/write accessor facts. */
  public record BeanProperty(String name, TypeMirror type, Element config, Element at,
                             String read, String write, boolean writeSetter) {}
}
