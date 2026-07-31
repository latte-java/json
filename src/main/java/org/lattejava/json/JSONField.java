/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Per-member configuration for a record component, or a {@code @JSONConstructor} parameter of an
 * {@link JSON @JSON}-annotated class.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface JSONField {
  /**
   * Whether to carry this member on the wire as a JSON string, converting through the member type's public
   * single-{@code String} constructor (deserialize) and its {@code toString()} (serialize). Opt-in: the processor
   * never infers this, because a single-{@code String} constructor is frequently not a parse constructor. The member
   * type must not already be a supported type, and must declare whichever half the member's direction actually uses
   * — a {@link #readOnly()} member needs only {@code toString()}, a {@link #writeOnly()} one only the constructor.
   */
  boolean asString() default false;

  /**
   * A {@link java.time.format.DateTimeFormatter} pattern giving this member's string form in place of the default
   * ISO-8601 one. Valid only on {@code LocalDate}, {@code LocalDateTime}, {@code OffsetDateTime},
   * {@code ZonedDateTime}, and {@code Instant}. The pattern is compiled during annotation processing, so an invalid
   * one is a compile error rather than a runtime failure, and an {@code Instant} pattern resolves against UTC. Cannot
   * be combined with {@link #instant()}, which selects an integer form instead.
   */
  String format() default "";

  /**
   * Whether to exclude this member from JSON entirely — neither serialized nor deserialized. Combining it with any
   * other attribute is a compile error, since the other attribute could have no effect.
   */
  boolean ignore() default false;

  /**
   * The wire form of an {@code Instant} member: the default {@link InstantFormat#ISO} string, or an epoch integer.
   * Valid only on {@code Instant}, and cannot be combined with {@link #format()} — one selects a string form, the
   * other an integer.
   */
  InstantFormat instant() default InstantFormat.ISO;

  /**
   * An explicit JSON key for this member, used verbatim in place of the Java name. It replaces the type's
   * {@link JSON#naming()} strategy for this member rather than feeding into it. Keys are checked during annotation
   * processing for quotes, backslashes, control characters, and collisions with another member's key.
   */
  String name() default "";

  /**
   * Whether this member is serialized but never populated from JSON — the OpenAPI {@code readOnly} sense, for a
   * server-assigned value a client must not set. The member owns no key on the deserialize side, so an incoming key
   * for it takes the unknown-key path: captured by a {@code @JSONCatchAll} bucket, rejected under
   * {@link JSON#strict()}, and otherwise skipped. Cannot be combined with {@link #writeOnly()}, which together would
   * only mean {@link #ignore()}.
   */
  boolean readOnly() default false;

  /**
   * Whether this member is populated from JSON but never serialized — the OpenAPI {@code writeOnly} sense, for a
   * value such as a password that is accepted but never echoed back. Cannot be combined with {@link #readOnly()},
   * which together would only mean {@link #ignore()}.
   */
  boolean writeOnly() default false;
}
