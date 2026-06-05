/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Per-field configuration for a record component or class field of an {@link JSON @JSON}-annotated type.
 * <p>
 * TODO: Not implemented yet.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface JSONField {
  String format() default "";

  boolean ignore() default false;

  String name() default "";

  boolean readOnly() default false;

  boolean required() default false;

  boolean writeOnly() default false;
}
