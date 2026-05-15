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
 * Marks the constructor the annotation processor should use to deserialize a non-record class. JSON-key
 * mapping is taken from the constructor's parameter names. Not used on records; records have a canonical
 * constructor.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.CONSTRUCTOR)
public @interface JSONConstructor {
}
