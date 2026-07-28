package demo;

import module org.lattejava.json;

@JSON
public class NoWriter {
  private String id;
  @JSONRaw
  private String raw;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getRaw() {
    return raw;
  }
}
