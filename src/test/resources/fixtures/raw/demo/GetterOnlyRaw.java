package demo;

import module org.lattejava.json;

@JSON
public class GetterOnlyRaw {
  private String id;
  private String raw;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  @JSONRaw
  public String getRaw() {
    return raw;
  }

  public void setRaw(String raw) {
    this.raw = raw;
  }
}
