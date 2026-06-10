/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Sets the discriminator value for a subtype of an {@link JSONTypeInfo @JSONTypeInfo} hierarchy. Defaults
 * to the simple class name when {@link #value()} is empty.
 * <p>
 * TODO: Not implemented yet.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONSubtype {
  String value() default "";
}
