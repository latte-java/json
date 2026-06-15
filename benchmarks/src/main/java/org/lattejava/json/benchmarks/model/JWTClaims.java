/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * The jwt scenario: a small, flat JWT claims payload — the library's origin use case.
 *
 * @author Brian Pontarelli
 */
@JSON
public record JWTClaims(String iss, String sub, String aud, long exp, long iat, String jti, List<String> roles) {
}
