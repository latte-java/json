/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json.tests {
  requires java.compiler;
  requires org.lattejava.json;
  requires org.testng;

  opens org.lattejava.json.tests to org.testng;
  opens org.lattejava.json.tests.processor to org.testng;
}
