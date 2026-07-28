/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class AnnotationDeclarationTest {
  @Test
  public void jsonAnnotationHasExpectedAttributes() throws Exception {
    var ann = JSON.class;
    assertEquals(ann.getDeclaredMethod("naming").getDefaultValue(), NamingStrategy.IDENTITY);
    assertEquals(ann.getDeclaredMethod("omitNulls").getDefaultValue(), Boolean.TRUE);
    assertEquals(ann.getDeclaredMethod("strict").getDefaultValue(), Boolean.FALSE);
  }

  @Test
  public void jsonFieldAnnotationHasExpectedAttributes() throws Exception {
    var ann = JSONField.class;
    assertEquals(ann.getDeclaredMethod("format").getDefaultValue(), "");
    assertEquals(ann.getDeclaredMethod("ignore").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("name").getDefaultValue(), "");
    assertEquals(ann.getDeclaredMethod("instant").getDefaultValue(), InstantFormat.ISO);
    assertEquals(ann.getDeclaredMethod("readOnly").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("writeOnly").getDefaultValue(), Boolean.FALSE);
  }

  @Test
  public void jsonTypeInfoRequiresProperty() throws Exception {
    var method = JSONTypeInfo.class.getDeclaredMethod("property");
    assertNull(method.getDefaultValue(), "property() must be required (no default)");
  }

  @Test
  public void jsonSubtypeValueDefaultsToEmpty() throws Exception {
    assertEquals(JSONSubtype.class.getDeclaredMethod("value").getDefaultValue(), "");
  }

  @Test
  public void jsonConstructorHasNoAttributes() {
    assertEquals(JSONConstructor.class.getDeclaredMethods().length, 0);
  }

  @Test
  public void jsonCatchAllHasNoAttributes() {
    assertEquals(JSONCatchAll.class.getDeclaredMethods().length, 0);
  }

  @Test
  public void jsonFieldTargetsMethod() {
    var target = JSONField.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD), "@JSONField must target METHOD");
  }

  @Test
  public void jsonCatchAllTargetsMethod() {
    var target = JSONCatchAll.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD), "@JSONCatchAll must target METHOD");
  }

  @Test
  public void jsonFieldTargetsParameter() {
    var target = JSONField.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.PARAMETER),
        "@JSONField must target PARAMETER");
  }

  @Test
  public void jsonCatchAllTargetsParameter() {
    var target = JSONCatchAll.class.getAnnotation(Target.class);
    assertTrue(Arrays.asList(target.value()).contains(ElementType.PARAMETER),
        "@JSONCatchAll must target PARAMETER");
  }

  @Test
  public void jsonRawHasNoAttributes() {
    assertEquals(JSONRaw.class.getDeclaredMethods().length, 0);
  }

  @Test
  public void jsonRawTargetsMembersAndParameters() {
    var target = JSONRaw.class.getAnnotation(Target.class);
    assertNotNull(target, "@JSONRaw must declare @Target");
    assertEquals(Set.of(target.value()),
        Set.of(ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT));
  }

  @Test
  public void jsonRawIsSourceRetained() {
    assertEquals(JSONRaw.class.getAnnotation(Retention.class).value(), RetentionPolicy.SOURCE);
  }
}
