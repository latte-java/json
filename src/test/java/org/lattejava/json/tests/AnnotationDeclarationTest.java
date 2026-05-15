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
    assertEquals(ann.getDeclaredMethod("readOnly").getDefaultValue(), Boolean.FALSE);
    assertEquals(ann.getDeclaredMethod("required").getDefaultValue(), Boolean.FALSE);
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
}
