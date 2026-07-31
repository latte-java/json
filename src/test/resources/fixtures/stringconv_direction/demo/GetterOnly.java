package demo;

import module java.base;
import module org.lattejava.json;

/**
 * A bean whose direction comes from its accessors rather than from readOnly/writeOnly: no setter means the property
 * is never deserialized, so it too should only need toString().
 */
@JSON
public class GetterOnly {
  @JSONField(asString = true)
  private OnlyToString label = new OnlyToString(7);

  public GetterOnly() {
  }

  public OnlyToString getLabel() {
    return label;
  }
}
