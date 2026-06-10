package demo;

import module java.base;
import module org.lattejava.json;

@JSON
public class ParamAnnotated {
  private final String userId;
  private final LocalDate born;
  private final Instant seen;
  private final Map<String, Object> extras;

  @JSONConstructor
  public ParamAnnotated(@JSONField(name = "user_id") String userId,
                        @JSONField(format = "MM/dd/yyyy") LocalDate born,
                        @JSONField(instant = InstantFormat.EPOCH_SECONDS) Instant seen,
                        @JSONCatchAll Map<String, Object> extras) {
    this.userId = userId;
    this.born = born;
    this.seen = seen;
    this.extras = extras;
  }

  public String getUserId() {
    return userId;
  }

  public LocalDate getBorn() {
    return born;
  }

  public Instant getSeen() {
    return seen;
  }

  public Map<String, Object> getExtras() {
    return extras;
  }
}
