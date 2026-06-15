/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json.benchmarks {
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires com.google.gson;
  requires jmh.core;

  requires static org.lattejava.json;
}
