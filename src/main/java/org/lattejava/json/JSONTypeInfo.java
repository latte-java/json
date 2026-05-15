/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a sealed interface or class polymorphic for JSON serialization. The discriminator property name
 * is required; OpenAPI semantics apply.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONTypeInfo {
  String property();
}
