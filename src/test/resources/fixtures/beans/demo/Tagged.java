package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class Tagged {
  public static final String KIND = "tagged";
  private transient int cacheHits;
  private String label;
  private Map<String, Object> extras = new java.util.LinkedHashMap<>();
  public int getCacheHits() { return cacheHits; }
  public void setCacheHits(int cacheHits) { this.cacheHits = cacheHits; }
  @JSONField(name = "tag") public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  @JSONCatchAll public Map<String, Object> getExtras() { return extras; }
  public void setExtras(Map<String, Object> extras) { this.extras = extras; }
}
