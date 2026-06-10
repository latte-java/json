/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSON;
import org.lattejava.json.JSONSubtype;
import org.lattejava.json.JSONTypeInfo;
import org.lattejava.json.NamingStrategy;

/** Pure reads of {@code @JSON}-family annotation facts and element conversions, shared by validators and writers. */
public final class ProcessorFacts {
  private ProcessorFacts() {
  }

  public static TypeElement asTypeElement(TypeMirror type) {
    return (TypeElement) ((javax.lang.model.type.DeclaredType) type).asElement();
  }

  /** The implemented {@code @JSONTypeInfo} interface (the polymorphic parent), or {@code null}. */
  public static TypeElement discriminatorInterface(TypeElement type) {
    for (TypeMirror itf : type.getInterfaces()) {
      TypeElement element = asTypeElement(itf);
      if (element.getAnnotation(JSONTypeInfo.class) != null) {
        return element;
      }
    }
    return null;
  }

  public static String discriminatorValue(TypeElement subtype) {
    JSONSubtype ann = subtype.getAnnotation(JSONSubtype.class);
    String v = ann == null ? "" : ann.value();
    return v.isEmpty() ? subtype.getSimpleName().toString() : v;
  }

  public static NamingStrategy naming(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann == null ? NamingStrategy.IDENTITY : ann.naming();
  }

  public static boolean omitNulls(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann == null || ann.omitNulls();
  }

  public static String qualified(Element e) {
    return e instanceof TypeElement t ? t.getQualifiedName().toString() : e.toString();
  }

  public static boolean strict(TypeElement record) {
    JSON ann = record.getAnnotation(JSON.class);
    return ann != null && ann.strict();
  }
}
