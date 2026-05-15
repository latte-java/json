/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

/**
 * Strategy for converting Java field names to JSON wire-form keys. Applied by the annotation processor at
 * compile time; not consulted at runtime.
 *
 * @author Brian Pontarelli
 */
public enum NamingStrategy {
  CAMEL_CASE,
  IDENTITY,
  KEBAB_CASE,
  PASCAL_CASE,
  SNAKE_CASE
}
