/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.json.tests.processor;

import module java.base;
import module java.compiler;
import module org.lattejava.json;

import javax.tools.ToolProvider;

/**
 * Compiles a fixture set under src/test/resources/fixtures/&lt;name&gt; with {@link JSONProcessor} attached, writing
 * generated sources and classes to build/test/generated/&lt;name&gt;. Used only by processor tests; the reflection here
 * is test scaffolding.
 *
 * @author Brian Pontarelli
 */
public final class ProcessorHarness {
  private ProcessorHarness() {
  }

  public static Result compile(String fixture) throws Exception {
    Path fixtureRoot = Path.of("src/test/resources/fixtures", fixture);
    Path out = Path.of("build/test/generated", fixture);
    if (Files.exists(out)) {
      try (var walk = Files.walk(out)) {
        walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
    Files.createDirectories(out);

    List<Path> sources;
    try (var walk = Files.walk(fixtureRoot)) {
      sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }

    // Locate the org.lattejava.json module jar so fixtures can resolve it on the module-path.
    Module jsonModule = JSONProcessor.class.getModule();
    URI jsonModuleUri = jsonModule.getLayer()
                                  .configuration()
                                  .findModule(jsonModule.getName())
                                  .flatMap(rm -> rm.reference().location())
                                  .orElseThrow(() -> new IllegalStateException("Cannot locate module [" + jsonModule.getName() + "]"));
    Path jsonJar = Path.of(jsonModuleUri);

    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    var diagCollector = new DiagnosticCollector<JavaFileObject>();
    try (StandardJavaFileManager fm =
             javac.getStandardFileManager(diagCollector, null, StandardCharsets.UTF_8)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
      fm.setLocation(StandardLocation.MODULE_PATH, List.of(jsonJar.toFile()));
      fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(out.toFile()));

      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
      JavaCompiler.CompilationTask task = javac.getTask(
          null, fm, diagCollector,
          List.of("--release", "25"),
          null, units);
      task.setProcessors(List.of(new JSONProcessor()));
      boolean ok = task.call();

      List<String> diags = new ArrayList<>();
      for (Diagnostic<? extends JavaFileObject> d : diagCollector.getDiagnostics()) {
        diags.add(d.getKind() + ": " + d.getMessage(null));
      }
      return new Result(ok, diags, out);
    }
  }

  public record Result(boolean success, List<String> diagnostics, Path outputDir) {
    public ClassLoader loader() throws Exception {
      return new URLClassLoader(new URL[]{outputDir.toUri().toURL()},
          ProcessorHarness.class.getClassLoader());
    }
  }
}
