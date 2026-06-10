/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class JSONParserPolymorphismTest {

  /** Hand-written polymorphic observer simulating what codegen will emit for a sealed Pet/Dog/Cat. */
  static final class PetPoly implements JSONPolymorphicObserver<Object> {
    @Override public String discriminatorKey() { return "petType"; }
    @Override public JSONObserver<?> observerFor(String value) {
      return switch (value) {
        case "Dog" -> new RecordingChild("Dog");
        case "Cat" -> new RecordingChild("Cat");
        default -> throw new JSONProcessingException(
            "Unknown discriminator value [" + value + "] for [petType]");
      };
    }
  }

  /** Concrete subtype observer that records its key/value pairs without expecting a discriminator key. */
  static final class RecordingChild implements JSONObserver<Map<String, Object>> {
    final String typeName;
    final Map<String, Object> data = new LinkedHashMap<>();
    RecordingChild(String typeName) { this.typeName = typeName; }

    @Override public JSONArrayObserver<?> beginArray(String key) { return new AnyArrayObserver(); }
    @Override public JSONObjectHandler beginObject(String key) { return new AnyObjectObserver(); }
    @Override public void bigInteger(String key, BigInteger value) { data.put(key, value); }
    @Override public void bool(String key, boolean value) { data.put(key, value); }
    @Override public void decimal(String key, BigDecimal value) { data.put(key, value); }
    @Override public Map<String, Object> finish() { data.put("__type", typeName); return data; }
    @Override public void integer(String key, long value) { data.put(key, value); }
    @Override public void nullValue(String key) { data.put(key, null); }
    @Override public void object(String key, Object value) { data.put(key, value); }
    @Override public void string(String key, String value) { data.put(key, value); }
    @Override public void array(String key, Object value) { data.put(key, value); }
  }

  /** Parent observer that returns the polymorphic observer for a single key. */
  static final class Parent implements JSONObserver<Map<String, Object>> {
    final Map<String, Object> data = new LinkedHashMap<>();
    @Override public JSONArrayObserver<?> beginArray(String key) { throw new AssertionError(); }
    @Override public JSONObjectHandler beginObject(String key) {
      return "pet".equals(key) ? new PetPoly() : SkipObserver.INSTANCE;
    }
    @Override public void bigInteger(String key, BigInteger value) {}
    @Override public void bool(String key, boolean value) {}
    @Override public void decimal(String key, BigDecimal value) {}
    @Override public Map<String, Object> finish() { return data; }
    @Override public void integer(String key, long value) {}
    @Override public void nullValue(String key) {}
    @Override public void object(String key, Object value) { data.put(key, value); }
    @Override public void string(String key, String value) {}
    @Override public void array(String key, Object value) {}
  }

  @Test
  public void discriminatorFirstDispatchesToDog() {
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}}", parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Dog");
    assertEquals(pet.get("name"), "Rex");
    assertEquals(pet.get("packSize"), 3L);
    assertFalse(pet.containsKey("petType"), "discriminator key must not be delivered as a field callback");
  }

  @Test
  public void discriminatorLastAlsoDispatches() {
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"name\":\"Whiskers\",\"lives\":9,\"petType\":\"Cat\"}}", parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Cat");
    assertEquals(pet.get("name"), "Whiskers");
    assertEquals(pet.get("lives"), 9L);
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*Unknown discriminator value \\[Bird\\].*")
  public void unknownDiscriminatorValueThrows() {
    new JSONParser().parse(
        "{\"pet\":{\"petType\":\"Bird\",\"name\":\"Tweety\"}}", new Parent());
  }

  @Test(expectedExceptions = JSONProcessingException.class,
        expectedExceptionsMessageRegExp = ".*Discriminator key \\[petType\\] missing.*")
  public void missingDiscriminatorThrows() {
    new JSONParser().parse(
        "{\"pet\":{\"name\":\"Anonymous\"}}", new Parent());
  }

  @Test
  public void discriminatorInsideNestedObjectIsIgnored() {
    var parent = new Parent();
    new JSONParser().parse(
        "{\"pet\":{\"meta\":{\"petType\":\"InnerNoise\"},\"petType\":\"Dog\",\"name\":\"Rex\"}}",
        parent);
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) parent.finish().get("pet");
    assertEquals(pet.get("__type"), "Dog");
  }

  @Test
  public void parsePolymorphicAtRootDispatchesToDog() {
    Object result = new JSONParser().parsePolymorphic(
        "{\"petType\":\"Dog\",\"name\":\"Rex\",\"packSize\":3}", new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Dog");
    assertEquals(pet.get("name"), "Rex");
    assertEquals(pet.get("packSize"), 3L);
  }

  @Test
  public void parsePolymorphicAtRootDispatchesToCatWithDiscriminatorLast() {
    Object result = new JSONParser().parsePolymorphic(
        "{\"name\":\"Whiskers\",\"lives\":9,\"petType\":\"Cat\"}", new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Cat");
  }

  @Test
  public void parsePolymorphicRejectsTopLevelArray() {
    var e = expectThrows(JSONProcessingException.class,
        () -> new JSONParser().parsePolymorphic("[1,2,3]", new PetPoly()));
    assertTrue(e.getMessage().contains("top-level JSON object"));
  }

  @Test
  public void parsePolymorphicFromBytes() {
    byte[] bytes = "{\"petType\":\"Dog\",\"name\":\"Rex\"}"
        .getBytes(StandardCharsets.UTF_8);
    Object result = new JSONParser().parsePolymorphic(bytes, new PetPoly());
    @SuppressWarnings("unchecked")
    Map<String, Object> pet = (Map<String, Object>) result;
    assertEquals(pet.get("__type"), "Dog");
  }
}
