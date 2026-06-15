/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Type plan for a collection-typed {@code @JSON} member: a static, generic description of the member's
 * collection type tree, built by generated code from the typed factories below and interpreted at runtime —
 * by {@link #writeInto} on the serialize side and by {@link JSONPlanMapObserver}/{@link JSONPlanArrayObserver}
 * on the deserialize side. {@code @JSON} object leaves dispatch to their generated companions; scalar
 * leaves carry the generated conversion lambdas. Plans are immutable and shared (one static instance per
 * member); interpretation allocates per parse only.
 *
 * <p>Serialization streams every nesting level into a single {@link JSONWriter} — no intermediate strings
 * are produced at any depth.
 *
 * @author Brian Pontarelli
 */
public final class JSONPlan {
  private JSONPlan() {
  }

  /** One node of a member's collection type tree, generic in the Java value type the node produces. */
  public sealed interface Node<T> permits ListNode, MapNode, ObjectLeaf, ScalarLeaf, SetNode {
  }

  /** Keyed scalar write into a {@link JSONWriter} (the map-value position of a scalar leaf). */
  @FunctionalInterface
  public interface KeyedWrite<T> {
    void write(JSONWriter writer, String key, T value);
  }

  public record ListNode<E>(Node<E> child) implements Node<List<E>> {
  }

  public record MapNode<K, V>(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) implements Node<Map<K, V>> {
  }

  public record ObjectLeaf<T>(String typeName, Supplier<JSONObjectHandler> observer, BiConsumer<JSONWriter, T> writer) implements Node<T> {
  }

  /**
   * A scalar leaf's conversion lambdas. A null read converter ({@code fromString}/{@code fromInteger}/
   * {@code fromBigInteger}/{@code fromDecimal}) means the corresponding JSON form is illegal for this leaf;
   * {@code acceptsBool} marks the boolean leaf. The write lambdas must tolerate a null value ({@code append}
   * writes a JSON null; {@code write} defers to the writer's omit-nulls handling).
   */
  public record ScalarLeaf<T>(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                              Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                              boolean acceptsBool, BiConsumer<JSONWriter, T> append, KeyedWrite<T> write) implements Node<T> {
  }

  public record SetNode<E>(Node<E> child) implements Node<Set<E>> {
  }

  public static <E> ListNode<E> list(Node<E> child) {
    return new ListNode<>(child);
  }

  public static <K, V> MapNode<K, V> map(Function<String, K> keyReader, Function<K, String> keyWriter, Node<V> child) {
    return new MapNode<>(keyReader, keyWriter, child);
  }

  public static <T> Node<T> object(String typeName, Supplier<JSONObjectHandler> observer, BiConsumer<JSONWriter, T> writer) {
    return new ObjectLeaf<>(typeName, observer, writer);
  }

  public static <T> Node<T> scalar(String typeName, Function<String, T> fromString, LongFunction<T> fromInteger,
                                   Function<BigInteger, T> fromBigInteger, Function<BigDecimal, T> fromDecimal,
                                   boolean acceptsBool, BiConsumer<JSONWriter, T> append, KeyedWrite<T> write) {
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

  /**
   * Convenience: serializes {@code value} (a collection member) to a String by streaming through a fresh
   * {@link JSONWriter}; a null member yields {@code null}.
   */
  public static <T> String write(Node<T> node, T value, boolean omitNulls) {
    if (value == null) {
      return null;
    }
    JSONWriter w = JSONWriter.acquire(omitNulls);
    writeInto(w, node, value);
    return w.finishString();
  }

  /**
   * Streams {@code value} (a collection member) into {@code w} by walking {@code node}, honoring the
   * writer's current omit-nulls setting. The caller has already verified {@code value} is non-null and
   * written its member key (or array/element separator) into {@code w}.
   */
  public static <T> void writeInto(JSONWriter w, Node<T> node, T value) {
    switch (node) {
      case ListNode<?> n -> writeArray(w, n.child(), (Collection<?>) value);
      case SetNode<?> n -> writeArray(w, n.child(), (Collection<?>) value);
      case MapNode<?, ?> n -> writeMap(w, n, (Map<?, ?>) value);
      case ObjectLeaf<?> n -> writeObject(n, w, value);
      case ScalarLeaf<?> n -> throw new JSONProcessingException("Plan root for type [" + n.typeName() + "] must be a collection node");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void appendScalar(ScalarLeaf<T> leaf, JSONWriter w, Object value) {
    leaf.append().accept(w, (T) value);
  }

  private static <E> void writeArray(JSONWriter w, Node<E> child, Collection<?> value) {
    w.beginArray();
    for (Object e : value) {
      writeElement(w, child, e);
    }
    w.endArray();
  }

  private static void writeElement(JSONWriter w, Node<?> child, Object e) {
    switch (child) {
      case ListNode<?> n -> {
        if (e == null) {
          w.nullElement();
        } else {
          writeArray(w, n.child(), (Collection<?>) e);
        }
      }
      case MapNode<?, ?> n -> {
        if (e == null) {
          w.nullElement();
        } else {
          writeMap(w, n, (Map<?, ?>) e);
        }
      }
      case ObjectLeaf<?> n -> {
        if (e == null) {
          w.nullElement();
        } else {
          writeObject(n, w, e);
        }
      }
      case ScalarLeaf<?> n -> appendScalar(n, w, e);
      case SetNode<?> n -> {
        if (e == null) {
          w.nullElement();
        } else {
          writeArray(w, n.child(), (Collection<?>) e);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <K> String writeKey(MapNode<K, ?> node, Object key) {
    return node.keyWriter().apply((K) key);
  }

  private static <K, V> void writeMap(JSONWriter w, MapNode<K, V> node, Map<?, ?> value) {
    w.beginObject();
    Node<V> child = node.child();
    for (var en : value.entrySet()) {
      writeMember(w, child, writeKey(node, en.getKey()), en.getValue());
    }
    w.endObject();
  }

  private static void writeMember(JSONWriter w, Node<?> child, String key, Object v) {
    switch (child) {
      case ListNode<?> n -> {
        if (v == null) {
          w.nullValue(key);
        } else {
          w.key(key);
          writeArray(w, n.child(), (Collection<?>) v);
        }
      }
      case MapNode<?, ?> n -> {
        if (v == null) {
          w.nullValue(key);
        } else {
          w.key(key);
          writeMap(w, n, (Map<?, ?>) v);
        }
      }
      case ObjectLeaf<?> n -> {
        if (v == null) {
          w.nullValue(key);
        } else {
          w.key(key);
          writeObject(n, w, v);
        }
      }
      case ScalarLeaf<?> n -> writeScalar(n, w, key, v);
      case SetNode<?> n -> {
        if (v == null) {
          w.nullValue(key);
        } else {
          w.key(key);
          writeArray(w, n.child(), (Collection<?>) v);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void writeObject(ObjectLeaf<T> leaf, JSONWriter w, Object value) {
    leaf.writer().accept(w, (T) value);
  }

  @SuppressWarnings("unchecked")
  private static <T> void writeScalar(ScalarLeaf<T> leaf, JSONWriter w, String key, Object value) {
    leaf.write().write(w, key, (T) value);
  }
}
