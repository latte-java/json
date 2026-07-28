/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import static org.testng.Assert.*;

public class RawCodegenTest {
  static ProcessorHarness.Result raw;

  @BeforeClass
  public void compileOnce() throws Exception {
    raw = ProcessorHarness.compile("raw");
    assertTrue(raw.success(), raw.diagnostics().toString());
  }

  @Test
  public void capturesWholeObjectVerbatim() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Token");
      Class<?> j = loader.loadClass("demo.internal.TokenJSON");
      String json = "{ \"sub\" : \"bob\", \"exp\": 123 }";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("sub").invoke(o), "bob");
      assertEquals(t.getMethod("exp").invoke(o), 123L);
      assertEquals(t.getMethod("raw").invoke(o), json, "raw must be the verbatim input object");
    }
  }

  @Test
  public void byteOverloadCapturesSameSpanAsStringOverload() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Token");
      Class<?> j = loader.loadClass("demo.internal.TokenJSON");
      String json = "{ \"sub\" : \"bob\", \"exp\": 123 }";
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      Object viaString = j.getMethod("fromJSON", String.class).invoke(null, json);
      Object viaBytes = j.getMethod("fromJSON", byte[].class).invoke(null, (Object) bytes);
      assertEquals(t.getMethod("raw").invoke(viaBytes), json, "the byte[] entry point must capture the same span");
      assertEquals(t.getMethod("raw").invoke(viaBytes), t.getMethod("raw").invoke(viaString),
          "both entry points must agree on the captured raw text");
    }
  }

  @Test
  public void rawIsNeverSerialized() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Token");
      Class<?> j = loader.loadClass("demo.internal.TokenJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{ \"sub\" : \"bob\", \"exp\": 123 }");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"sub\":\"bob\",\"exp\":123}");
    }
  }

  @Test
  public void rawKeyInInputIsAnUnknownKeyNotABinding() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Token");
      Class<?> j = loader.loadClass("demo.internal.TokenJSON");
      String json = "{\"sub\":\"bob\",\"exp\":1,\"raw\":\"ignored\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("raw").invoke(o), json, "the \"raw\" key must not bind; the whole object wins");
    }
  }

  @Test
  public void nestedTypeCapturesItsOwnSlice() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> env = loader.loadClass("demo.Envelope");
      Class<?> inner = loader.loadClass("demo.Inner");
      Class<?> j = loader.loadClass("demo.internal.EnvelopeJSON");
      String json = "{\"kind\":\"k\",\"inner\":{\"y\":7}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(env.getMethod("raw").invoke(o), json);
      Object in = env.getMethod("inner").invoke(o);
      assertEquals(inner.getMethod("raw").invoke(in), "{\"y\":7}", "nested type gets only its own object");
    }
  }

  @Test
  public void arrayElementsEachCaptureTheirOwnSlice() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> batch = loader.loadClass("demo.Batch");
      Class<?> inner = loader.loadClass("demo.Inner");
      Class<?> j = loader.loadClass("demo.internal.BatchJSON");
      String json = "{\"items\":[{\"y\":1},{ \"y\" : 2 }]}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(batch.getMethod("raw").invoke(o), json);
      var items = (List<?>) batch.getMethod("items").invoke(o);
      assertEquals(items.size(), 2);
      assertEquals(inner.getMethod("raw").invoke(items.get(0)), "{\"y\":1}");
      assertEquals(inner.getMethod("raw").invoke(items.get(1)), "{ \"y\" : 2 }");
    }
  }

  @Test
  public void emptyObjectCapturesBraces() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.OnlyRaw");
      Class<?> j = loader.loadClass("demo.internal.OnlyRawJSON");
      Object o = j.getMethod("fromJSON", String.class).invoke(null, "{}");
      assertEquals(t.getMethod("raw").invoke(o), "{}");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{}", "a raw-only type serializes to an empty object");
    }
  }

  @Test
  public void multiByteContentDoesNotShiftTheSlice() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> env = loader.loadClass("demo.Envelope");
      Class<?> inner = loader.loadClass("demo.Inner");
      Class<?> j = loader.loadClass("demo.internal.EnvelopeJSON");
      String json = "{\"kind\":\"é€漢\",\"inner\":{\"y\":7}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(env.getMethod("raw").invoke(o), json);
      assertEquals(inner.getMethod("raw").invoke(env.getMethod("inner").invoke(o)), "{\"y\":7}");
    }
  }

  @Test
  public void constructorClassCapturesRaw() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Config");
      Class<?> j = loader.loadClass("demo.internal.ConfigJSON");
      String json = "{ \"name\" : \"n\" }";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getName").invoke(o), "n");
      assertEquals(t.getMethod("rawText").invoke(o), json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"name\":\"n\"}");
    }
  }

  @Test
  public void beanCapturesRawThroughItsSetter() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Session");
      Class<?> j = loader.loadClass("demo.internal.SessionJSON");
      String json = "{ \"id\" : \"s1\" }";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getId").invoke(o), "s1");
      assertEquals(t.getMethod("rawText").invoke(o), json);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"s1\"}");
    }
  }

  @Test
  public void beanCapturesRawWhenTheAnnotationIsOnlyOnTheGetter() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.GetterOnlyRaw");
      Class<?> j = loader.loadClass("demo.internal.GetterOnlyRawJSON");
      String json = "{ \"id\" : \"g1\" }";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("getId").invoke(o), "g1");
      assertEquals(t.getMethod("getRaw").invoke(o), json, "@JSONRaw on the getter alone must still be honored");
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"g1\"}");
    }
  }

  @Test
  public void polymorphicSubtypeCaptureIncludesTheDiscriminator() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> kennel = loader.loadClass("demo.Kennel");
      Class<?> dog = loader.loadClass("demo.Dog");
      Class<?> j = loader.loadClass("demo.internal.KennelJSON");
      String json = "{\"pet\":{\"petType\":\"Dog\",\"name\":\"rex\"}}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(kennel.getMethod("raw").invoke(o), json);
      Object pet = kennel.getMethod("pet").invoke(o);
      assertEquals(dog.getMethod("raw").invoke(pet), "{\"petType\":\"Dog\",\"name\":\"rex\"}",
          "the subtype slice includes the discriminator key");
    }
  }

  @Test
  public void catchAllAndRawCoexist() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Loose");
      Class<?> j = loader.loadClass("demo.internal.LooseJSON");
      String json = "{\"id\":\"a\",\"raw\":\"x\",\"n\":1}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("raw").invoke(o), json, "raw gets the whole object");
      var extras = (Map<?, ?>) t.getMethod("extras").invoke(o);
      assertEquals(extras.get("raw"), "x", "the \"raw\" key is unknown, so the catch-all takes it");
      assertEquals(extras.get("n"), 1L);
      assertEquals(j.getMethod("toJSON", t).invoke(null, o), "{\"id\":\"a\",\"raw\":\"x\",\"n\":1}");
    }
  }

  @Test
  public void strictModeRejectsAKeyMatchingTheRawMemberName() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> j = loader.loadClass("demo.internal.StrictJSON");
      var m = j.getMethod("fromJSON", String.class);
      var e = expectThrows(InvocationTargetException.class,
          () -> m.invoke(null, "{\"id\":\"a\",\"raw\":\"x\"}"));
      assertTrue(e.getCause().getMessage().contains("[raw]"),
          "expected an unknown-key error naming [raw], got: " + e.getCause().getMessage());
    }
  }

  @Test
  public void strictModeStillCapturesRaw() throws Exception {
    try (var loader = (URLClassLoader) raw.loader()) {
      Class<?> t = loader.loadClass("demo.Strict");
      Class<?> j = loader.loadClass("demo.internal.StrictJSON");
      String json = "{\"id\":\"a\"}";
      Object o = j.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(t.getMethod("raw").invoke(o), json);
    }
  }
}
