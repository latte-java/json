/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Declares a sealed interface or class polymorphic for JSON serialization. The discriminator property name
 * is required; OpenAPI semantics apply.
 * <p>
 * TODO: Not implemented yet.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONTypeInfo {
  String property();
}
