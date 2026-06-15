/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.benchmarks.model;

import module java.base;

import org.lattejava.json.JSON;

/**
 * The strings scenario root: escape-heavy and multibyte-unicode string content.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Document(String title, String author, String language, List<String> paragraphs) {
}
