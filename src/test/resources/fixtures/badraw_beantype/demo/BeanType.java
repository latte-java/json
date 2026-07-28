package demo;

import module org.lattejava.json;

@JSON
public class BeanType {
  private String id;
  @JSONRaw
  private int raw;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public int getRaw() {
    return raw;
  }

  public void setRaw(int raw) {
    this.raw = raw;
  }
}
