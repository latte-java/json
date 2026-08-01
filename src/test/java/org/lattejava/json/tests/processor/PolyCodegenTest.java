/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class PolyCodegenTest {
  static ProcessorHarness.Result poly;

  @BeforeClass
  public void compileOnce() throws Exception {
    poly = ProcessorHarness.compile("poly");
    assertTrue(poly.success(), poly.diagnostics().toString());
  }

  @Test
  public void rootRoundTripsDogDiscriminatorFirst() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      String json = "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}";
      Object dog = petJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Dog").getMethod("name").invoke(dog), "Rex");
      assertEquals(petJson.getMethod("toJSON", pet).invoke(null, dog), json);
    }
  }

  @Test
  public void customDiscriminatorValueRoundTrips() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      String json = "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}";
      Object cat = petJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(loader.loadClass("demo.Cat").getMethod("lives").invoke(cat), 9);
      assertEquals(petJson.getMethod("toJSON", pet).invoke(null, cat), json);
    }
  }

  @Test
  public void discriminatorLastOnInputStillDispatches() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Object dog = petJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"Rex\",\"packSize\":3,\"petType\":\"Dog\"}");
      assertEquals(loader.loadClass("demo.Dog").getMethod("packSize").invoke(dog), 3);
    }
  }

  @Test
  public void toPrettyStringDelegatesToTheSubtypeCompanion() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      Object dog = petJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}");
      assertEquals(petJson.getMethod("toPrettyString", pet).invoke(null, dog), """
          {
            "petType": "Dog",
            "name": "Rex",
            "packSize": 3
          }""");
    }
  }

  @Test
  public void toJSONBytesMatchesToJSON() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      Class<?> pet = loader.loadClass("demo.Pet");
      Object dog = petJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}");
      String s = (String) petJson.getMethod("toJSON", pet).invoke(null, dog);
      byte[] b = (byte[]) petJson.getMethod("toJSONBytes", pet).invoke(null, dog);
      assertEquals(new String(b, StandardCharsets.UTF_8), s);
    }
  }

  @Test
  public void unknownDiscriminatorThrows() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      try {
        petJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"petType\":\"Fish\",\"name\":\"Nemo\"}");
        fail("expected unknown discriminator to throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertTrue(e.getCause().getMessage().contains("Unknown discriminator value [Fish]"),
            "got: " + e.getCause().getMessage());
      }
    }
  }

  @Test
  public void missingDiscriminatorThrows() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> petJson = loader.loadClass("demo.internal.PetJSON");
      try {
        petJson.getMethod("fromJSON", String.class).invoke(null, "{\"name\":\"Anon\"}");
        fail("expected missing discriminator to throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertNotNull(e.getCause());
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException");
      }
    }
  }

  @Test
  public void strictSubtypeIgnoresDiscriminatorOnDirectParse() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> birdJson = loader.loadClass("demo.internal.BirdJSON");
      Object bird = birdJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"Bird\",\"name\":\"Tweety\"}");
      assertEquals(loader.loadClass("demo.Bird").getMethod("name").invoke(bird), "Tweety");
    }
  }

  @Test
  public void subtypeToJSONEmitsDiscriminatorFirst() throws Exception {
    try (var loader = (URLClassLoader) poly.loader()) {
      Class<?> catJson = loader.loadClass("demo.internal.CatJSON");
      Class<?> cat = loader.loadClass("demo.Cat");
      Object c = catJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"petType\":\"kitty\",\"name\":\"Whiskers\",\"lives\":9}");
      String json = (String) catJson.getMethod("toJSON", cat).invoke(null, c);
      assertTrue(json.startsWith("{\"petType\":\"kitty\""), "got: " + json);
    }
  }
}
