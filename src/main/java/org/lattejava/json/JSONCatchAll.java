/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json;

import module java.base;

/**
 * Marks a {@code Map<String, Object>} record component, or a {@code @JSONConstructor} parameter, as the catch-all
 * bucket for unknown JSON keys. Exactly one catch-all is permitted per {@link JSON @JSON} type.
 *
 * @author Brian Pontarelli
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface JSONCatchAll {
}
