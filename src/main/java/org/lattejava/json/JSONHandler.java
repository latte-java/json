package org.lattejava.json;

public interface JSONHandler<T> {
  T fromJSON();

  String toJSON(T object);
}
