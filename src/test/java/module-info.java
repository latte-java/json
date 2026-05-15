/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.json.tests {
  requires org.lattejava.json;
  requires org.testng;

  opens org.lattejava.json.tests to org.testng;
  opens org.lattejava.json.tests.model to org.testng;
  opens org.lattejava.json.tests.model.internal to org.testng;
}
