/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSONTypeInfo;
import org.lattejava.json.jte.JTEEngine;
import org.lattejava.json.jte.PolymorphicView;

/** Renders the {@code polymorphic.jte} shell for a sealed {@code @JSONTypeInfo} interface into {@code <typePackage>.internal}. */
public final class PolymorphicWriter extends AbstractWriter {
  public PolymorphicWriter(ProcessingEnvironment processingEnv) {
    super(processingEnv);
  }

  public void write(TypeElement iface, ModuleElement module) {
    String internalPkg = module.getQualifiedName() + ".internal";
    String companionPkg = internalPackageOf(iface);
    String simpleName = iface.getSimpleName().toString();
    String companion = simpleName + "JSON";
    String qualifiedType = iface.getQualifiedName().toString();
    String discriminatorKey = iface.getAnnotation(JSONTypeInfo.class).property();

    List<PolymorphicView.Subtype> subtypes = new ArrayList<>();
    for (TypeMirror permitted : iface.getPermittedSubclasses()) {
      TypeElement sub = ProcessorFacts.asTypeElement(permitted);
      subtypes.add(new PolymorphicView.Subtype(
          ProcessorFacts.discriminatorValue(sub),
          sub.getQualifiedName().toString(),
          internalPackageOf(sub) + "." + sub.getSimpleName() + "JSON"));
    }

    PolymorphicView view = new PolymorphicView(companionPkg, internalPkg, qualifiedType, simpleName,
        companion, discriminatorKey, subtypes);
    String source = JTEEngine.render("polymorphic.jte", Map.of("view", view));

    writeSource(companionPkg, companion, source, iface);
  }
}
