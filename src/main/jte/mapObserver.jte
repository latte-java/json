@import org.lattejava.json.jte.Component
@param Component c
@param boolean omitNulls
!{var keyType = c.type().key();}
!{var valType = c.type().value();}
!{var mapKey = keyType.isEnum() ? "en.getKey().name()" : keyType.isString() ? "en.getKey()" : "en.getKey().toString()";}
!{var stub = "throw new JSONProcessingException(\"unexpected JSON value for Map value type [" + valType.name() + "]\");";}
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONBuilder(${omitNulls});
    for (var en : v.entrySet()) b.@template.memberCall(type = valType, key = mapKey, val = "en.getValue()");
    return b.build();
  }
  private static final class @template.cap(name = c.name())MapObserver implements JSONObserver<@template.declType(type = c.type())> {
    private final @template.declType(type = c.type()) map = new java.util.LinkedHashMap<>();
@if(valType.isStringForm())
    @Override public void string(String key, String value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.fromString(type = valType, expr = "value")); }
    @Override public void integer(String key, long value) { ${stub} }
    @Override public void bigInteger(String key, java.math.BigInteger value) { ${stub} }
    @Override public void decimal(String key, java.math.BigDecimal value) { ${stub} }
    @Override public void bool(String key, boolean value) { ${stub} }
@elseif(valType.isBool())
    @Override public void string(String key, String value) { ${stub} }
    @Override public void integer(String key, long value) { ${stub} }
    @Override public void bigInteger(String key, java.math.BigInteger value) { ${stub} }
    @Override public void decimal(String key, java.math.BigDecimal value) { ${stub} }
    @Override public void bool(String key, boolean value) { map.put(@template.fromString(type = keyType, expr = "key"), value); }
@else
    @Override public void string(String key, String value) { ${stub} }
    @Override public void integer(String key, long value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "integer")); }
    @Override public void bigInteger(String key, java.math.BigInteger value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "bigInteger")); }
    @Override public void decimal(String key, java.math.BigDecimal value) { map.put(@template.fromString(type = keyType, expr = "key"), @template.narrow(type = valType, source = "decimal")); }
    @Override public void bool(String key, boolean value) { ${stub} }
@endif
    @Override public void nullValue(String key) { map.put(@template.fromString(type = keyType, expr = "key"), null); }
    @Override public @template.declType(type = c.type()) finish() { return map; }
    @Override public JSONObjectHandler beginObject(String key) { throw new JSONProcessingException("nested objects in collections unsupported"); }
    @Override public JSONArrayObserver<?> beginArray(String key) { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(String key, Object value) {}
    @Override public void array(String key, Object value) {}
  }
