/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.processor;

import module java.base;
import module java.compiler;

import org.lattejava.json.JSONTypeInfo;
import org.lattejava.json.NamingStrategy;
import org.lattejava.json.jte.CompanionView;
import org.lattejava.json.jte.Component;
import org.lattejava.json.jte.JTEEngine;
import org.lattejava.json.jte.TypeView;

/** Renders the per-type {@code companion.jte} shell into {@code <typePackage>.internal}. */
public final class CompanionWriter extends AbstractWriter {
  private final ClassMemberDiscovery members;

  public CompanionWriter(ProcessingEnvironment processingEnv, ClassMemberDiscovery members) {
    super(processingEnv);
    this.members = members;
  }

  public void write(TypeElement type, ModuleElement module) {
    String internalPkg = module.getQualifiedName() + ".internal";
    String companionPkg = internalPackageOf(type);
    String simpleName = type.getSimpleName().toString();
    String companion = simpleName + "JSON";
    String qualifiedType = type.getQualifiedName().toString();

    NamingStrategy naming = ProcessorFacts.naming(type);
    List<Component> components = new ArrayList<>();
    Set<String> enumImports = new TreeSet<>();
    boolean bean = members.isBean(type);
    if (bean) {
      for (ClassMemberDiscovery.BeanProperty p : members.discoverProperties(type)) {
        Component c = new Component(processingEnv, p.name(), p.type(), p.config(), naming, p.read(), p.write(), p.writeSetter());
        components.add(c);
        collectEnums(c.type(), enumImports);
      }
    } else if (type.getKind() == ElementKind.CLASS) {
      for (VariableElement p : members.jsonConstructors(type).getFirst().getParameters()) {
        components.add(new Component(processingEnv, p, naming, members.resolveRead(type, p)));
        collectEnums(new TypeView(processingEnv, p.asType()), enumImports);
      }
    } else {
      for (RecordComponentElement c : type.getRecordComponents()) {
        components.add(new Component(processingEnv, c, naming));
        collectEnums(new TypeView(processingEnv, c.asType()), enumImports);
      }
    }

    String discriminatorKey = "";
    String discriminatorValue = "";
    TypeElement itf = ProcessorFacts.discriminatorInterface(type);
    if (itf != null) {
      discriminatorKey = itf.getAnnotation(JSONTypeInfo.class).property();
      discriminatorValue = ProcessorFacts.discriminatorValue(type);
    }

    CompanionView view = new CompanionView(companionPkg, internalPkg, qualifiedType, simpleName, companion,
        ProcessorFacts.omitNulls(type), ProcessorFacts.strict(type), List.copyOf(enumImports), components,
        discriminatorKey, discriminatorValue, bean);
    String source = JTEEngine.render("companion.jte", Map.of("view", view));

    writeSource(companionPkg, companion, source, type);
  }

  /**
   * Collects the fully-qualified name of every enum reachable in {@code type} — itself or a {@code List}/{@code Set}
   * element or {@code Map} key/value — into {@code into}, so the companion can import each by simple name.
   */
  private void collectEnums(TypeView type, Set<String> into) {
    if (type == null) {
      return;
    }
    if (type.isCollection()) {
      if (type.isMap()) {
        collectEnums(type.key(), into);
        collectEnums(type.value(), into);
      } else {
        collectEnums(type.element(), into);
      }
      return;
    }
    if (type.isEnum()) {
      into.add(type.name());
    }
  }
}
