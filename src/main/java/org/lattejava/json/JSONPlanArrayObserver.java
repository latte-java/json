/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * {@link JSONArrayObserver} that interprets a {@link JSONPlan.ListNode}/{@link JSONPlan.SetNode}: elements
 * are converted by the node's child (scalar leaves convert inline; object leaves dispatch to their generated
 * companion; container children recurse into a fresh plan observer). Accumulates into an {@link ArrayList}
 * or {@link LinkedHashSet} per the node kind. One instance per JSON array; not thread-safe.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlanArrayObserver<C> implements JSONArrayObserver<C> {
  private final Collection<Object> acc;
  private final JSONPlan.Node<?> child;

  private JSONPlanArrayObserver(Collection<Object> acc, JSONPlan.Node<?> child) {
    this.acc = acc;
    this.child = child;
  }

  public static <E> JSONPlanArrayObserver<List<E>> of(JSONPlan.ListNode<E> node) {
    return new JSONPlanArrayObserver<>(new ArrayList<>(), node.child());
  }

  public static <E> JSONPlanArrayObserver<Set<E>> of(JSONPlan.SetNode<E> node) {
    return new JSONPlanArrayObserver<>(new LinkedHashSet<>(), node.child());
  }

  @Override
  public void array(Object value) {
    acc.add(value);
  }

  @Override
  public JSONArrayObserver<?> beginArray() {
    return switch (child) {
      case JSONPlan.ListNode<?> n -> of(n);
      case JSONPlan.SetNode<?> n -> of(n);
      default -> throw unexpected("array");
    };
  }

  @Override
  public JSONObjectHandler beginObject() {
    return switch (child) {
      case JSONPlan.MapNode<?, ?> n -> new JSONPlanMapObserver<>(n);
      case JSONPlan.ObjectLeaf<?> n -> n.observer().get();
      default -> throw unexpected("object");
    };
  }

  @Override
  public void bigInteger(BigInteger value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromBigInteger() != null) {
      acc.add(leaf.fromBigInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void bool(boolean value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.acceptsBool()) {
      acc.add(value);
      return;
    }
    throw unexpected("boolean");
  }

  @Override
  public void decimal(BigDecimal value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromDecimal() != null) {
      acc.add(leaf.fromDecimal().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @SuppressWarnings("unchecked")
  @Override
  public C finish() {
    return (C) acc;
  }

  @Override
  public void integer(long value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromInteger() != null) {
      acc.add(leaf.fromInteger().apply(value));
      return;
    }
    throw unexpected("number");
  }

  @Override
  public void nullValue() {
    acc.add(null);
  }

  @Override
  public void object(Object value) {
    acc.add(value);
  }

  @Override
  public void string(String value) {
    if (child instanceof JSONPlan.ScalarLeaf<?> leaf && leaf.fromString() != null) {
      acc.add(leaf.fromString().apply(value));
      return;
    }
    throw unexpected("string");
  }

  private JSONProcessingException unexpected(String kind) {
    return new JSONProcessingException("unexpected JSON " + kind + " for element type [" + JSONPlan.typeName(child) + "]");
  }
}
