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
 * Sets the discriminator value for a subtype of an {@link JSONTypeInfo @JSONTypeInfo} hierarchy. Defaults
 * to the simple class name when {@link #value()} is empty.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface JSONSubtype {
  String value() default "";
}
