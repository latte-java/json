/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Marks a record, class, or sealed interface for JSON serialization and deserialization. The annotation
 * processor generates a companion {@code *JSON} class for every type carrying this annotation.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSON {
  NamingStrategy naming() default NamingStrategy.IDENTITY;

  boolean omitNulls() default true;

  boolean strict() default false;
}
