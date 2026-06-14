/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import org.lattejava.json.processor.ClassMemberDiscovery;
import org.lattejava.json.processor.ClassValidator;
import org.lattejava.json.processor.CompanionWriter;
import org.lattejava.json.processor.HelperEmitter;
import org.lattejava.json.processor.PolymorphicValidator;
import org.lattejava.json.processor.PolymorphicWriter;
import org.lattejava.json.processor.ProcessorFacts;
import org.lattejava.json.processor.RecordValidator;

import module java.base;
import module java.compiler;

/**
 * Annotation processor for {@link JSON @JSON}. Once per compilation it emits the runtime helper set into the consumer's
 * {@code <module>.internal} package. It then dispatches each annotated element to a validator and, if validation
 * succeeds, to the matching writer: a {@code @JSONTypeInfo} interface routes to the polymorphic validator and writer,
 * a class routes to the class validator and companion writer, and a record routes to the record validator and companion
 * writer.
 *
 * <p>This class owns only the round-level guards (element kind and named-module checks) and the dispatch table. Member
 * discovery, annotation-fact reads, validation, helper emission, and companion source generation all live in the
 * collaborators under {@code org.lattejava.json.processor}, each built once in {@link #init(ProcessingEnvironment)}.
 *
 * @author Brian Pontarelli
 */
@SupportedAnnotationTypes("org.lattejava.json.JSON")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class JSONProcessor extends AbstractProcessor {
  /** Re-exports {@link HelperEmitter#HELPERS} through this exported class so the test module can read the list. */
  public static final List<String> HELPERS = HelperEmitter.HELPERS;
  private ClassValidator classValidator;
  private CompanionWriter companionWriter;
  private HelperEmitter helperEmitter;
  private PolymorphicValidator polymorphicValidator;
  private PolymorphicWriter polymorphicWriter;
  private RecordValidator recordValidator;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);

    ClassMemberDiscovery members = new ClassMemberDiscovery(processingEnv);
    this.helperEmitter = new HelperEmitter(processingEnv);
    this.companionWriter = new CompanionWriter(processingEnv, members);
    this.polymorphicWriter = new PolymorphicWriter(processingEnv);
    this.recordValidator = new RecordValidator(processingEnv, members);
    this.classValidator = new ClassValidator(processingEnv, members);
    this.polymorphicValidator = new PolymorphicValidator(processingEnv, members);
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    TypeElement jsonAnno = processingEnv.getElementUtils().getTypeElement("org.lattejava.json.JSON");
    if (jsonAnno == null) {
      return false;
    }

    Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(jsonAnno);

    // All @JSON types in a compilation share one module (cross-module @JSON references are unsupported), so the helper
    // set is emitted once into that module's .internal, before any companion is generated.
    if (!annotated.isEmpty()) {
      helperEmitter.emit(processingEnv.getElementUtils().getModuleOf(annotated.iterator().next()));
    }

    for (Element e : annotated) {
      TypeElement type = (TypeElement) e;
      boolean polyParent = e.getKind() == ElementKind.INTERFACE && type.getAnnotation(JSONTypeInfo.class) != null;
      if (e.getKind() == ElementKind.INTERFACE && type.getAnnotation(JSONTypeInfo.class) == null) {
        error(e, "@JSON interface [" + type.getQualifiedName() + "] requires @JSONTypeInfo to declare its discriminator");
        continue;
      }
      if (e.getKind() != ElementKind.RECORD && e.getKind() != ElementKind.CLASS && !polyParent) {
        error(e, "@JSON supports records, classes, and sealed @JSONTypeInfo interfaces; ["
            + ProcessorFacts.qualified(e) + "] is a [" + e.getKind() + "]");
        continue;
      }

      ModuleElement module = processingEnv.getElementUtils().getModuleOf(type);
      if (module == null || module.isUnnamed()) {
        error(e, "@JSON requires a named module (module-info.java); type [" + type.getQualifiedName() + "] is in the unnamed module");
        continue;
      }

      if (polyParent) {
        if (polymorphicValidator.validate(type)) {
          polymorphicWriter.write(type, module);
        }
        continue;
      }
      var validator = type.getKind() == ElementKind.CLASS ? classValidator : recordValidator;
      if (validator.validate(type)) {
        companionWriter.write(type, module);
      }
    }
    return false;
  }

  private void error(Element e, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
  }
}
