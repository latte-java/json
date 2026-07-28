package demo;

import module org.lattejava.json;

@JSON
public class SplitRaw {
  private String id;
  @JSONCatchAll
  private String extra;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  @JSONRaw
  public String getExtra() {
    return extra;
  }

  public void setExtra(String extra) {
    this.extra = extra;
  }
}
