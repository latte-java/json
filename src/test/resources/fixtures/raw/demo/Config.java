package demo;

import module org.lattejava.json;

@JSON
public class Config {
  private final String name;
  private final String raw;

  @JSONConstructor
  public Config(String name, @JSONRaw String raw) {
    this.name = name;
    this.raw = raw;
  }

  public String getName() {
    return name;
  }

  public String rawText() {
    return raw;
  }
}
