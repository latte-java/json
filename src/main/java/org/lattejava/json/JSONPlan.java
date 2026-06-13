/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Type plan for a collection-typed {@code @JSON} member: a static, generic description of the member's
 * collection type tree, built by generated code from the typed factories below and interpreted at runtime —
 * by {@link #write} on the serialize side and by {@link JSONPlanMapObserver}/{@link JSONPlanArrayObserver}
 * on the deserialize side. {@code @JSON} object leaves dispatch to their generated companions; scalar
 * leaves carry the generated conversion lambdas. Plans are immutable and shared (one static instance per
 * member); interpretation allocates per parse only.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlan {
  private JSONPlan() {
  }

  /** One node of a member's collection type tree, generic in the Java value type the node produces. */
  public sealed interface Node<T> permits ListNode, MapNode, ObjectLeaf, ScalarLeaf, SetNode {
  }

  /** Keyed scalar write into a {@link JSONBuilder} (the map-value position of a scalar leaf). */
  @FunctionalInterface
  public interface KeyedWrite<T> {
    void write(JSONBuilder builder, String key, T value);
  }

  public record ListNode<E>(Node<E> child) implements Node<List<E>> {
  }

  public record MapNode<K, V>(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) implements Node<Map<K, V>> {
  }

  public record ObjectLeaf<T>(String typeName, Supplier<JSONObjectHandler> observer, Function<T, String> writer) implements Node<T> {
  }

  /**
   * A scalar leaf's conversion lambdas. A null read converter ({@code fromString}/{@code fromInteger}/
   * {@code fromBigInteger}/{@code fromDecimal}) means the corresponding JSON form is illegal for this leaf;
   * {@code acceptsBool} marks the boolean leaf. The write lambdas must tolerate a null value ({@code append}
   * writes a JSON null; {@code write} defers to the builder's omit-nulls handling).
   */
  public record ScalarLeaf<T>(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                              Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                              boolean acceptsBool, BiConsumer<JSONArrayBuilder, T> append, KeyedWrite<T> write) implements Node<T> {
  }

  public record SetNode<E>(Node<E> child) implements Node<Set<E>> {
  }

  public static <E> ListNode<E> list(Node<E> child) {
    return new ListNode<>(child);
  }

  public static <K, V> MapNode<K, V> map(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) {
    return new MapNode<>(keyReader, keyWriter, child);
  }

  public static <T> Node<T> object(String typeName, Supplier<JSONObjectHandler> observer, Function<T, String> writer) {
    return new ObjectLeaf<>(typeName, observer, writer);
  }

  public static <T> Node<T> scalar(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                                   Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                                   boolean acceptsBool, BiConsumer<JSONArrayBuilder, T> append, KeyedWrite<T> write) {
    return new ScalarLeaf<>(typeName, fromString, fromInteger, fromBigInteger, fromDecimal, acceptsBool, append, write);
  }

  public static <E> SetNode<E> set(Node<E> child) {
    return new SetNode<>(child);
  }

  /** The display name of {@code node}'s value type for error messages. */
  public static String typeName(Node<?> node) {
    return switch (node) {
      case ListNode<?> n -> "List<" + typeName(n.child()) + ">";
      case MapNode<?, ?> n -> "Map<?, " + typeName(n.child()) + ">";
      case ObjectLeaf<?> n -> n.typeName();
      case ScalarLeaf<?> n -> n.typeName();
      case SetNode<?> n -> "Set<" + typeName(n.child()) + ">";
    };
  }

  /** Serializes {@code value} (a collection member) as raw JSON by walking {@code node}; a null member yields null. */
  public static <T> String write(Node<T> node, T value, boolean omitNulls) {
    if (value == null) {
      return null;
    }

    return switch (node) {
      case ListNode<?> n -> writeArray(n.child(), (Collection<?>) value, omitNulls);
      case MapNode<?, ?> n -> writeMap(n, (Map<?, ?>) value, omitNulls);
      case ObjectLeaf<?> n -> writeObject(n, value);
      case ScalarLeaf<?> n -> throw new JSONProcessingException("Plan root for type [" + n.typeName() + "] must be a collection node");
      case SetNode<?> n -> writeArray(n.child(), (Collection<?>) value, omitNulls);
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> void appendScalar(ScalarLeaf<T> leaf, JSONArrayBuilder builder, Object value) {
    leaf.append().accept(builder, (T) value);
  }

  private static <E> String writeArray(Node<E> child, Collection<?> value, boolean omitNulls) {
    var b = new JSONArrayBuilder(omitNulls);
    for (Object e : value) {
      switch (child) {
        case ListNode<?> n -> b.raw(e == null ? null : writeArray(n.child(), (Collection<?>) e, omitNulls));
        case MapNode<?, ?> n -> b.raw(e == null ? null : writeMap(n, (Map<?, ?>) e, omitNulls));
        case ObjectLeaf<?> n -> b.raw(e == null ? null : writeObject(n, e));
        case ScalarLeaf<?> n -> appendScalar(n, b, e);
        case SetNode<?> n -> b.raw(e == null ? null : writeArray(n.child(), (Collection<?>) e, omitNulls));
      }
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static <K> String writeKey(MapNode<K, ?> node, Object key) {
    return node.keyWriter().apply((K) key);
  }

  private static <K, V> String writeMap(MapNode<K, V> node, Map<?, ?> value, boolean omitNulls) {
    var b = new JSONBuilder(omitNulls);
    Node<V> child = node.child();
    for (var en : value.entrySet()) {
      String key = writeKey(node, en.getKey());
      Object v = en.getValue();
      switch (child) {
        case ListNode<?> n -> b.array(key, v == null ? null : writeArray(n.child(), (Collection<?>) v, omitNulls));
        case MapNode<?, ?> n -> b.object(key, v == null ? null : writeMap(n, (Map<?, ?>) v, omitNulls));
        case ObjectLeaf<?> n -> b.object(key, v == null ? null : writeObject(n, v));
        case ScalarLeaf<?> n -> writeScalar(n, b, key, v);
        case SetNode<?> n -> b.array(key, v == null ? null : writeArray(n.child(), (Collection<?>) v, omitNulls));
      }
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static <T> String writeObject(ObjectLeaf<T> leaf, Object value) {
    return leaf.writer().apply((T) value);
  }

  @SuppressWarnings("unchecked")
  private static <T> void writeScalar(ScalarLeaf<T> leaf, JSONBuilder builder, String key, Object value) {
    leaf.write().write(builder, key, (T) value);
  }
}
