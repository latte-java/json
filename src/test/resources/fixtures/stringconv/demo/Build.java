package demo;

import module java.base;
import module org.lattejava.json;

/** The JavaBean path through ClassValidator.validateBean, which reaches validateType separately from records. */
@JSON
public class Build {
  @JSONField(asString = true)
  private SemVer version;

  public Build() {
  }

  public SemVer getVersion() {
    return version;
  }

  public void setVersion(SemVer version) {
    this.version = version;
  }
}
