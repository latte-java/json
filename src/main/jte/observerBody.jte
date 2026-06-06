@import org.lattejava.json.jte.CompanionView
@import org.lattejava.json.jte.Component
@param CompanionView view
  @Override public void string(String key, String value) {
    switch (key) {
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> { /* discriminator: ignore */ }
@endif
@for(Component c : view.components())
@if(c.type().isStringForm())
      case "${c.name()}" -> this.${c.name()} = @template.fromString(type = c.type(), expr = "value");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void integer(String key, long value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.name()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "integer");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void bigInteger(String key, BigInteger value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.name()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "bigInteger");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void decimal(String key, BigDecimal value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isNumeric())
      case "${c.name()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "decimal");
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void bool(String key, boolean value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isBool())
      case "${c.name()}" -> this.${c.name()} = value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public void nullValue(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isPrimitive())
      case "${c.name()}" -> throw new JSONProcessingException("null for primitive field [${c.name()}]");
@else
      case "${c.name()}" -> this.${c.name()} = null;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public JSONObjectHandler beginObject(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap())
      case "${c.name()}" -> { return new @template.cap(name = c.name())MapObserver(); }
@elseif(c.type().hasCompanion())
      case "${c.name()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
    throw new IllegalStateException("nested objects unsupported in this release");
  }
  @SuppressWarnings("unchecked")
  @Override public void object(String key, Object value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isMap() || c.type().hasCompanion())
      case "${c.name()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public JSONArrayObserver<?> beginArray(String key) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isList() || c.type().isSet())
      case "${c.name()}" -> { return new @template.cap(name = c.name())ArrayObserver(); }
@endif
@endfor
    }
    throw new IllegalStateException("arrays unsupported in this release");
  }
  @SuppressWarnings("unchecked")
  @Override public void array(String key, Object value) {
    switch (key) {
@for(Component c : view.components())
@if(c.type().isList() || c.type().isSet())
      case "${c.name()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view)
    }
  }
  @Override public ${view.simpleName()} finish() {
    return new ${view.simpleName()}(@for(int i = 0; i < view.components().size(); i++)${i > 0 ? ", " : ""}this.${view.components().get(i).name()}@endfor);
  }
