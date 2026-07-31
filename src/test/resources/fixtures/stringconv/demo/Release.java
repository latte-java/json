package demo;

import module java.base;
import module org.lattejava.json;

/**
 * {@code path} is deliberately a JDK type: java.io.File comes from a compiled class file rather than this
 * compilation, which is the shape of the real motivating case (a type from a dependency JAR). It proves the
 * constructor/toString() discovery reads class files, not just source.
 */
@JSON
public record Release(String name, @JSONField(asString = true) SemVer version,
                      @JSONField(asString = true) java.io.File path) {
}
