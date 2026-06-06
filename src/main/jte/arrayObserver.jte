@import org.lattejava.json.jte.Component
@param Component c
!{var elem = c.type().element();}
!{var impl = c.type().isSet() ? "java.util.LinkedHashSet" : "java.util.ArrayList";}
!{var stub = "throw new JSONProcessingException(\"unexpected JSON value for element type [" + elem.name() + "]\");";}
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONArrayBuilder();
    for (var e : v) b@template.arrayAppend(type = elem, expr = "e");
    return b.build();
  }
  private static final class @template.cap(name = c.name())ArrayObserver implements JSONArrayObserver<@template.declType(type = c.type())> {
    private final @template.declType(type = c.type()) acc = new ${impl}<>();
@if(elem.isNested())
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { ${stub} }
@elseif(elem.isStringForm())
    @Override public void string(String value) { acc.add(@template.fromString(type = elem, expr = "value")); }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { ${stub} }
@elseif(elem.isBool())
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { ${stub} }
    @Override public void bigInteger(java.math.BigInteger value) { ${stub} }
    @Override public void decimal(java.math.BigDecimal value) { ${stub} }
    @Override public void bool(boolean value) { acc.add(value); }
@else
    @Override public void string(String value) { ${stub} }
    @Override public void integer(long value) { acc.add(@template.narrow(type = elem, source = "integer")); }
    @Override public void bigInteger(java.math.BigInteger value) { acc.add(@template.narrow(type = elem, source = "bigInteger")); }
    @Override public void decimal(java.math.BigDecimal value) { acc.add(@template.narrow(type = elem, source = "decimal")); }
    @Override public void bool(boolean value) { ${stub} }
@endif
    @Override public void nullValue() { acc.add(null); }
    @Override public @template.declType(type = c.type()) finish() { return acc; }
@if(elem.isNested())
    @Override public JSONObjectHandler beginObject() { return new ${elem.nestedCompanion()}(); }
    @Override public JSONArrayObserver<?> beginArray() { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(Object value) { acc.add((${elem.decl()}) value); }
    @Override public void array(Object value) {}
@else
    @Override public JSONObjectHandler beginObject() { throw new JSONProcessingException("nested objects in collections unsupported"); }
    @Override public JSONArrayObserver<?> beginArray() { throw new JSONProcessingException("nested collections unsupported"); }
    @Override public void object(Object value) {}
    @Override public void array(Object value) {}
@endif
  }
