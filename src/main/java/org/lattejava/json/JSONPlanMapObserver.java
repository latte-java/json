/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONObserver} that interprets a {@link JSONPlan.MapNode}: keys are converted by the node's
 * {@code keyReader}; values by the node's child (scalar leaves convert inline; object leaves dispatch to
 * their generated companion; container children recurse into a fresh plan observer). Accumulates into a
 * {@link LinkedHashMap}, preserving JSON-object insertion order. One instance per JSON object; not
 * thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlanMapObserver<K, V> implements JSONObserver<Map<K, V>> {
  private final JSONPlan.Node<?> child;
  private final Function<String, K> keyReader;
  private final Map<K, V> map = new LinkedHashMap<>();

  public JSONPlanMapObserver(JSONPlan.MapNode<K, V> node) {
    this.child = node.child();
    this.keyReader = node.keyReader();
  }

  @Override
  public void array(String key, Object value) {
    put(key, value);
  }

  @Override
  public JSONArrayObserver<?> beginArray(String key) {
    return switch (child) {
      case JSONPlan.ListNode<?> n -> JSONPlanArrayObserver.of(n);
      case JSONPlan.SetNode<?> n -> JSONPlanArrayObserver.of(n);
      default -> throw unexpected("array");
    };
  }

  @Override
  public JSONObjectHandler beginObject(String key) {
    return switch (child) {
      case JSONPlan.MapNode<?, ?> n -> new JSONPlanMapObserver<>(n);
      case JSONPlan.ObjectLeaf<?> n -> n.observer().get();
      default -> throw unexpected("object");
    };
  }

  @Override
  public void bigInteger(String key, BigInteger value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromBigInteger() != null) {
      put(key, leaf.fromBigInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void bool(String key, boolean value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.acceptsBool()) {
      put(key, value);
      return;
    }
    throw unexpected("boolean");
  }

  @Override
  public void decimal(String key, BigDecimal value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromDecimal() != null) {
      put(key, leaf.fromDecimal().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public Map<K, V> finish() {
    return map;
  }

  @Override
  public void integer(String key, long value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromInteger() != null) {
      put(key, leaf.fromInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void nullValue(String key) {
    put(key, null);
  }

  @Override
  public void object(String key, Object value) {
    put(key, value);
  }

  @Override
  public void string(String key, String value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromString() != null) {
      put(key, leaf.fromString().apply(value));
      return;
    }
    throw unexpected("string");
  }

  @SuppressWarnings("unchecked")
  private void put(String key, Object value) {
    map.put(keyReader.apply(key), (V) value);
  }

  private JSONProcessingException unexpected(String kind) {
    return new JSONProcessingException("unexpected JSON " + kind + " for Map value type [" + JSONPlan.typeName(child) + "]");
  }
}
