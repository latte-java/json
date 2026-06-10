package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Caught {
  private final String id;
  private final Map<String, Object> extras;

  @JSONConstructor
  public Caught(String id, @JSONCatchAll Map<String, Object> extras) {
    this.id = id;
    this.extras = extras;
  }

  public String getId() {
    return id;
  }

  public Map<String, Object> getExtras() {
    return extras;
  }
}
