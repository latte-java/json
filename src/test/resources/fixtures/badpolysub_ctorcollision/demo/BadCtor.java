package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("bc")
public final class BadCtor implements Box {
  private final String kind;

  @JSONConstructor
  public BadCtor(String kind) {
    this.kind = kind;
  }

  public String getKind() {
    return kind;
  }
}
