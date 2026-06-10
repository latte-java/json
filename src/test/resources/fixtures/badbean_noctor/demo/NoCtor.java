package demo;

import module org.lattejava.json;

@JSON
public class NoCtor {
  private String id;
  private NoCtor() {}
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
}
