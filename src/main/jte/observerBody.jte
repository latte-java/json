@import org.lattejava.json.jte.CompanionView
@import org.lattejava.json.jte.Component
@param CompanionView view
  @Override public void string(String key, String value) {
    switch (key) {
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> { /* discriminator: ignore */ }
@endif
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.isFormatted())
      case "${c.wireKey()}" -> this.${c.name()} = ${c.formatterField()}.parse(value, ${c.formatType()}::from);
@elseif(c.deserialize() && c.type().isStringForm() && !c.isEpochInstant())
      case "${c.wireKey()}" -> this.${c.name()} = @template.fromString(type = c.type(), expr = "value");
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public void integer(String key, long value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.isEpochInstant())
      case "${c.wireKey()}" -> this.${c.name()} = Instant.${c.epochFactory()}(value);
@elseif(c.deserialize() && c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "integer");
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public void bigInteger(String key, BigInteger value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "bigInteger");
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public void decimal(String key, BigDecimal value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isNumeric())
      case "${c.wireKey()}" -> this.${c.name()} = @template.narrow(type = c.type(), source = "decimal");
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public void bool(String key, boolean value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isBool())
      case "${c.wireKey()}" -> this.${c.name()} = value;
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public void nullValue(String key) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isPrimitive())
      case "${c.wireKey()}" -> throw new JSONProcessingException("null for primitive field [${c.name()}]");
@elseif(c.deserialize())
      case "${c.wireKey()}" -> this.${c.name()} = null;
@endif
@endfor
      @template.defaultArm(view = view, value = "null")
    }
  }
  @Override public JSONObjectHandler beginObject(String key) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && c.type().isMap())
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())MapObserver(); }
@elseif(c.deserialize() && c.type().hasCompanion())
      case "${c.wireKey()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
@if(view.catchAll().isEmpty())
    throw new IllegalStateException("nested objects unsupported in this release");
@else
    return new AnyObjectObserver();
@endif
  }
  @SuppressWarnings("unchecked")
  @Override public void object(String key, Object value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && (c.type().isMap() || c.type().hasCompanion()))
      case "${c.wireKey()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public JSONArrayObserver<?> beginArray(String key) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && (c.type().isList() || c.type().isSet()))
      case "${c.wireKey()}" -> { return new @template.cap(name = c.name())ArrayObserver(); }
@endif
@endfor
    }
@if(view.catchAll().isEmpty())
    throw new IllegalStateException("arrays unsupported in this release");
@else
    return new AnyArrayObserver();
@endif
  }
  @SuppressWarnings("unchecked")
  @Override public void array(String key, Object value) {
    switch (key) {
@for(Component c : view.typedComponents())
@if(c.deserialize() && (c.type().isList() || c.type().isSet()))
      case "${c.wireKey()}" -> this.${c.name()} = (@template.declType(type = c.type())) value;
@endif
@endfor
      @template.defaultArm(view = view, value = "value")
    }
  }
  @Override public ${view.simpleName()} finish() {
@if(view.beanConstructed())
    ${view.simpleName()} value = new ${view.simpleName()}();
@for(Component c : view.components())
@if(c.deserialize())
@if(c.writeIsSetter())
    value.${c.write()}(this.${c.name()});
@else
    value.${c.write()} = this.${c.name()};
@endif
@endif
@endfor
    return value;
@else
    return new ${view.simpleName()}(@for(int i = 0; i < view.components().size(); i++)${i > 0 ? ", " : ""}this.${view.components().get(i).name()}@endfor);
@endif
  }
