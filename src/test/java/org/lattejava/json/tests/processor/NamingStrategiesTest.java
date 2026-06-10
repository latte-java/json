/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class NamingStrategiesTest {
  @Test
  public void identityReturnsInputUnchanged() {
    assertEquals(NamingStrategies.apply(NamingStrategy.IDENTITY, "userName"), "userName");
    assertEquals(NamingStrategies.apply(NamingStrategy.IDENTITY, "HTTPStatus"), "HTTPStatus");
  }

  @Test
  public void snakeCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "userName"), "user_name");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "name"), "name");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "userID"), "user_id");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "parseHTTPResponse"), "parse_http_response");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "packSize2"), "pack_size2");
  }

  @Test
  public void kebabCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.KEBAB_CASE, "userName"), "user-name");
    assertEquals(NamingStrategies.apply(NamingStrategy.KEBAB_CASE, "parseHTTPResponse"), "parse-http-response");
  }

  @Test
  public void pascalCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "userName"), "UserName");
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "userID"), "UserId");
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "parseHTTPResponse"), "ParseHttpResponse");
  }

  @Test
  public void camelCase() {
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "userName"), "userName");
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "userID"), "userId");
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "HTTPStatus"), "httpStatus");
  }

  @Test
  public void defensiveEdges() {
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, ""), "");
    assertEquals(NamingStrategies.apply(NamingStrategy.SNAKE_CASE, "URL"), "url");
    assertEquals(NamingStrategies.apply(NamingStrategy.PASCAL_CASE, "URL"), "Url");
    assertEquals(NamingStrategies.apply(NamingStrategy.CAMEL_CASE, "IDToken"), "idToken");
  }
}
