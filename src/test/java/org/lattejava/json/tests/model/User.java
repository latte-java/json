package org.lattejava.json.tests.model;

import module org.lattejava.json;

import org.lattejava.json.tests.model.internal.*;

@JSON
public record User() {
  public static User fromJSON(String json) {
    return UserJSON.fromJSON(json);
  }

  public static String toJSON(User user) {
    return UserJSON.toJSON(user);
  }
}
