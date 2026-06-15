@import org.lattejava.json.jte.CompanionView
@import org.lattejava.json.jte.Component
@param CompanionView view
!{var comps = view.typedComponents();}
!{int discField = comps.size();}
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize())
  private static final byte[] KEY_${comps.get(i).name()} = "${comps.get(i).wireKey()}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
@endif
@endfor
@if(!view.discriminatorKey().isEmpty())
  private static final byte[] KEY_DISCRIMINATOR = "${view.discriminatorKey()}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
@endif
  @Override public int field(byte[] src, int offset, int length) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize())
    if (length == KEY_${comps.get(i).name()}.length && java.util.Arrays.equals(src, offset, offset + length, KEY_${comps.get(i).name()}, 0, length)) return ${i};
@endif
@endfor
@if(!view.discriminatorKey().isEmpty())
    if (length == KEY_DISCRIMINATOR.length && java.util.Arrays.equals(src, offset, offset + length, KEY_DISCRIMINATOR, 0, length)) return ${discField};
@endif
    return -1;
  }
  @Override public int fieldOf(String key) {
    return switch (key) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize())
      case "${comps.get(i).wireKey()}" -> ${i};
@endif
@endfor
@if(!view.discriminatorKey().isEmpty())
      case "${view.discriminatorKey()}" -> ${discField};
@endif
      default -> -1;
    };
  }
  @Override public boolean dispatchUnknown() {
    return ${!view.catchAll().isEmpty() || view.strict()};
  }
  private static String keyName(int field) {
    return switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize())
      case ${i} -> "${comps.get(i).wireKey()}";
@endif
@endfor
@if(!view.discriminatorKey().isEmpty())
      case ${discField} -> "${view.discriminatorKey()}";
@endif
      default -> "?";
    };
  }
  @Override public void string(int field, String value) {
    switch (field) {
@if(!view.discriminatorKey().isEmpty())
      case ${discField} -> { /* discriminator: ignore */ }
@endif
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).isFormatted())
      case ${i} -> this.${comps.get(i).name()} = ${comps.get(i).formatterField()}.parse(value, ${comps.get(i).formatType()}::from);
@elseif(comps.get(i).deserialize() && comps.get(i).type().isStringForm() && !comps.get(i).isEpochInstant())
      case ${i} -> this.${comps.get(i).name()} = @template.fromString(type = comps.get(i).type(), expr = "value");
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public void integer(int field, long value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).isEpochInstant())
      case ${i} -> this.${comps.get(i).name()} = Instant.${comps.get(i).epochFactory()}(value);
@elseif(comps.get(i).deserialize() && comps.get(i).type().isNumeric())
      case ${i} -> this.${comps.get(i).name()} = @template.narrow(type = comps.get(i).type(), source = "integer");
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public void bigInteger(int field, BigInteger value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).type().isNumeric())
      case ${i} -> this.${comps.get(i).name()} = @template.narrow(type = comps.get(i).type(), source = "bigInteger");
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public void decimal(int field, BigDecimal value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).type().isNumeric())
      case ${i} -> this.${comps.get(i).name()} = @template.narrow(type = comps.get(i).type(), source = "decimal");
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public void bool(int field, boolean value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).type().isBool())
      case ${i} -> this.${comps.get(i).name()} = value;
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public void nullValue(int field) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).type().isPrimitive())
      case ${i} -> throw new JSONProcessingException("null for primitive field [${comps.get(i).name()}]");
@elseif(comps.get(i).deserialize())
      case ${i} -> this.${comps.get(i).name()} = null;
@endif
@endfor
      @template.defaultArmInt(view = view, value = "null")
    }
  }
  @Override public JSONObjectHandler beginObject(int field) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && comps.get(i).type().isDynamicMap())
      case ${i} -> { return new AnyObjectObserver(); }
@elseif(comps.get(i).deserialize() && comps.get(i).type().isMap())
      case ${i} -> { return new JSONPlanMapObserver<>(${comps.get(i).name()}$plan); }
@elseif(comps.get(i).deserialize() && comps.get(i).type().hasCompanion())
      case ${i} -> { return new ${comps.get(i).type().nestedCompanion()}(); }
@endif
@endfor
    }
@if(!view.catchAll().isEmpty())
    return new AnyObjectObserver();
@elseif(view.strict())
    throw new JSONProcessingException("Unknown JSON key [" + keyName(field) + "] for type [${view.simpleName()}]");
@else
    return SkipObserver.INSTANCE;
@endif
  }
  @SuppressWarnings("unchecked")
  @Override public void object(int field, Object value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && (comps.get(i).type().isMap() || comps.get(i).type().hasCompanion()))
      case ${i} -> this.${comps.get(i).name()} = (@template.declType(type = comps.get(i).type())) value;
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
  @Override public JSONArrayObserver<?> beginArray(int field) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && (comps.get(i).type().isList() || comps.get(i).type().isSet()))
      case ${i} -> { return JSONPlanArrayObserver.of(${comps.get(i).name()}$plan); }
@endif
@endfor
    }
@if(!view.catchAll().isEmpty())
    return new AnyArrayObserver();
@elseif(view.strict())
    throw new JSONProcessingException("Unknown JSON key [" + keyName(field) + "] for type [${view.simpleName()}]");
@else
    return SkipArrayObserver.INSTANCE;
@endif
  }
  @SuppressWarnings("unchecked")
  @Override public void array(int field, Object value) {
    switch (field) {
@for(int i = 0; i < comps.size(); i++)
@if(comps.get(i).deserialize() && (comps.get(i).type().isList() || comps.get(i).type().isSet()))
      case ${i} -> this.${comps.get(i).name()} = (@template.declType(type = comps.get(i).type())) value;
@endif
@endfor
      @template.defaultArmInt(view = view, value = "value")
    }
  }
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
@if(c.deserialize() && c.type().isDynamicMap())
      case "${c.wireKey()}" -> { return new AnyObjectObserver(); }
@elseif(c.deserialize() && c.type().isMap())
      case "${c.wireKey()}" -> { return new JSONPlanMapObserver<>(${c.name()}$plan); }
@elseif(c.deserialize() && c.type().hasCompanion())
      case "${c.wireKey()}" -> { return new ${c.type().nestedCompanion()}(); }
@endif
@endfor
    }
@if(!view.catchAll().isEmpty())
    return new AnyObjectObserver();
@elseif(view.strict())
    throw new JSONProcessingException("Unknown JSON key [" + key + "] for type [${view.simpleName()}]");
@else
    return SkipObserver.INSTANCE;
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
      case "${c.wireKey()}" -> { return JSONPlanArrayObserver.of(${c.name()}$plan); }
@endif
@endfor
    }
@if(!view.catchAll().isEmpty())
    return new AnyArrayObserver();
@elseif(view.strict())
    throw new JSONProcessingException("Unknown JSON key [" + key + "] for type [${view.simpleName()}]");
@else
    return SkipArrayObserver.INSTANCE;
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