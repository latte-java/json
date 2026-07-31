package demo;

import module java.base;

/**
 * Stands in for a third-party string-form type (the motivating case is org.lattejava.version.Version): NOT
 * @JSON-annotated, carries a public single-String constructor and a declared toString() that is its inverse.
 */
public record SemVer(int major, int minor, int patch) {
  public SemVer(String value) {
    this(value.split("\\.", -1));
  }

  private SemVer(String[] parts) {
    this(part(parts, 0), part(parts, 1), part(parts, 2));
  }

  private static int part(String[] parts, int index) {
    if (parts.length != 3) {
      throw new IllegalArgumentException("Invalid version [" + String.join(".", parts) + "]");
    }
    return Integer.parseInt(parts[index]);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
