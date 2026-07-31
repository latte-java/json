/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * Codegen for {@code @JSONField(asString = true)} members — an arbitrary user type carried as a JSON string through
 * its public single-String constructor and its toString().
 *
 * @author Brian Pontarelli
 */
public class StringConvertibleCodegenTest {
  static ProcessorHarness.Result stringconv;

  @BeforeClass
  public void compileOnce() throws Exception {
    stringconv = ProcessorHarness.compile("stringconv");
    assertTrue(stringconv.success(), stringconv.diagnostics().toString());
  }

  @Test
  public void roundTripsRecordMember() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> release = loader.loadClass("demo.Release");
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      String json = "{\"name\":\"latte\",\"version\":\"1.2.3\",\"path\":\"build/out.txt\"}";
      Object obj = releaseJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(release.getMethod("version").invoke(obj).toString(), "1.2.3");
      assertEquals(releaseJson.getMethod("toJSON", release).invoke(null, obj), json);
    }
  }

  @Test
  public void deserializesIntoTheTargetTypeNotAString() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> release = loader.loadClass("demo.Release");
      Class<?> semVer = loader.loadClass("demo.SemVer");
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      Object obj = releaseJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"latte\",\"version\":\"1.2.3\"}");
      Object version = release.getMethod("version").invoke(obj);
      assertSame(version.getClass(), semVer, "member must bind as SemVer, not String");
      assertEquals(semVer.getMethod("major").invoke(version), 1);
      assertEquals(semVer.getMethod("minor").invoke(version), 2);
      assertEquals(semVer.getMethod("patch").invoke(version), 3);
    }
  }

  @Test
  public void roundTripsBeanProperty() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> build = loader.loadClass("demo.Build");
      Class<?> buildJson = loader.loadClass("demo.internal.BuildJSON");
      String json = "{\"version\":\"4.5.6\"}";
      Object obj = buildJson.getMethod("fromJSON", String.class).invoke(null, json);
      assertEquals(build.getMethod("getVersion").invoke(obj).toString(), "4.5.6");
      assertEquals(buildJson.getMethod("toJSON", build).invoke(null, obj), json);
    }
  }

  @Test
  public void malformedValueThrowsJSONProcessingException() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      try {
        releaseJson.getMethod("fromJSON", String.class)
            .invoke(null, "{\"name\":\"latte\",\"version\":\"not-a-version\"}");
        fail("malformed value must throw");
      } catch (InvocationTargetException e) {
        assertEquals(e.getCause().getClass().getSimpleName(), "JSONProcessingException",
            "the constructor's own exception must be wrapped, got: " + e.getCause());
        assertTrue(e.getCause().getMessage().contains("[not-a-version]"),
            "message must carry the offending value, got: " + e.getCause().getMessage());
      }
    }
  }

  @Test
  public void nullValueBindsNullWithoutCallingTheConstructor() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> release = loader.loadClass("demo.Release");
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      Object obj = releaseJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"latte\",\"version\":null}");
      assertNull(release.getMethod("version").invoke(obj),
          "null must bind directly; SemVer's constructor rejects null");
    }
  }

  @Test
  public void nullMemberOmittedByDefault() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> release = loader.loadClass("demo.Release");
      Class<?> semVer = loader.loadClass("demo.SemVer");
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      Object obj = release.getConstructor(String.class, semVer, File.class).newInstance("latte", null, null);
      assertEquals(releaseJson.getMethod("toJSON", release).invoke(null, obj), "{\"name\":\"latte\"}",
          "null must be omitted, not serialized as the string \"null\"");
    }
  }

  @Test
  public void bindsATypeFromACompiledDependency() throws Exception {
    try (var loader = (URLClassLoader) stringconv.loader()) {
      Class<?> release = loader.loadClass("demo.Release");
      Class<?> releaseJson = loader.loadClass("demo.internal.ReleaseJSON");
      Object obj = releaseJson.getMethod("fromJSON", String.class)
          .invoke(null, "{\"name\":\"latte\",\"path\":\"build/out.txt\"}");
      Object path = release.getMethod("path").invoke(obj);
      assertSame(path.getClass(), File.class,
          "java.io.File is read from a class file, not this compilation — the same shape as a dependency JAR");
      assertEquals(path, new File("build/out.txt"));
    }
  }

  @Test
  public void declaresTheMemberFullyQualified() throws Exception {
    Path source = stringconv.outputDir().resolve("demo/internal/ReleaseJSON.java");
    String generated = Files.readString(source);
    assertTrue(generated.contains("private demo.SemVer version;"),
        "an arbitrary user type has no import in the companion, so it must be fully qualified; got:\n" + generated);
  }
}
