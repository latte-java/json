package demo;

import module org.lattejava.json;

@JSON
@JSONSubtype("bb")
public final class BadBean implements Crate {
  private String kind;

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }
}
