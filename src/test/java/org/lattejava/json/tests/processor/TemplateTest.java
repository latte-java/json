/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.lattejava.json;
import module org.testng;

import static org.testng.Assert.*;

public class TemplateTest {
  @Test
  public void substitutesNamedHoles() {
    String out = Template.of("class {{name}} {}").render(Map.of("name", "Foo"));
    assertEquals(out, "class Foo {}");
  }

  @Test
  public void substitutesRepeatedHole() {
    String out = Template.of("{{x}}+{{x}}").render(Map.of("x", "a"));
    assertEquals(out, "a+a");
  }

  @Test
  public void literalNotRegex_replacementWithDollarAndBackslashIsLiteral() {
    String out = Template.of("v={{e}}").render(Map.of("e", "a -> b$c\\d"));
    assertEquals(out, "v=a -> b$c\\d");
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void unboundHoleThrows() {
    Template.of("{{a}} {{b}}").render(Map.of("a", "1"));
  }

  @Test
  public void joinMapsAndJoins() {
    String out = Template.join(List.of("a", "b", "c"), s -> "[" + s + "]", "\n");
    assertEquals(out, "[a]\n[b]\n[c]");
  }

  @Test
  public void joinEmptyIsEmptyString() {
    assertEquals(Template.join(List.of(), Object::toString, "\n"), "");
  }

  @Test
  public void reindentsMultilineFragmentToHoleColumn() {
    String body = "class Foo {\n  {{fields}}\n}\n";
    String out = Template.of(body).render(Map.of("fields", "int a;\nint b;"));
    assertEquals(out, "class Foo {\n  int a;\n  int b;\n}\n");
  }

  @Test
  public void reindentSkipsBlankInteriorLines() {
    String body = "class Foo {\n  {{methods}}\n}\n";
    String out = Template.of(body).render(Map.of("methods", "void a() {}\n\nvoid b() {}"));
    assertEquals(out, "class Foo {\n  void a() {}\n\n  void b() {}\n}\n");
  }

  @Test
  public void emptyFragmentDropsTheEntireHoleLine() {
    String body = "class Foo {\n  {{fields}}\n}\n";
    String out = Template.of(body).render(Map.of("fields", ""));
    assertEquals(out, "class Foo {\n}\n");
  }

  @Test
  public void injectedValueIsInert_notRescannedForOtherTokens() {
    String out = Template.of("{{a}} {{b}}").render(new LinkedHashMap<>() {{
      put("a", "X{{b}}Y");
      put("b", "Z");
    }});
    assertEquals(out, "X{{b}}Y Z");
  }

  @Test
  public void literalBracesInValueArePreserved() {
    String out = Template.of("s = {{v}};").render(Map.of("v", "\"{{x}}\""));
    assertEquals(out, "s = \"{{x}}\";");
  }

  @Test
  public void standaloneHoleAtStartOfFile() {
    String out = Template.of("{{x}}\n").render(Map.of("x", "a\nb"));
    assertEquals(out, "a\nb\n");
  }

  @Test
  public void inlineHoleAtEndOfFileNoTrailingNewline() {
    String out = Template.of("x={{v}}").render(Map.of("v", "1"));
    assertEquals(out, "x=1");
  }

  @Test
  public void tabIndentReindentsWithTab() {
    String out = Template.of("c {\n\t{{m}}\n}").render(Map.of("m", "a();\nb();"));
    assertEquals(out, "c {\n\ta();\n\tb();\n}");
  }

  @Test
  public void inlineHoleSharingLineIsNotStandalone() {
    String out = Template.of("  pre {{h}} post").render(Map.of("h", "A\nB"));
    assertEquals(out, "  pre A\nB post");
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void unterminatedHoleThrows() {
    Template.of("a {{b").render(Map.of("b", "x"));
  }

  @Test
  public void joinSkipsNullResults() {
    String out = Template.join(List.of("a", "b", "c"),
        s -> s.equals("b") ? null : "[" + s + "]", "\n");
    assertEquals(out, "[a]\n[c]");
  }

  @Test
  public void joinSkipsLeadingAndTrailingNulls() {
    String out = Template.join(Arrays.asList(null, "a", null, "b", null),
        s -> s == null ? null : s, "\n");
    assertEquals(out, "a\nb");
  }
}
