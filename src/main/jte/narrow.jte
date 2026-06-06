@param org.lattejava.json.jte.TypeView type
@param String source
${switch (source) {
  case "integer" -> switch (type.name()) {
    case "java.math.BigDecimal" -> "java.math.BigDecimal.valueOf(value)";
    case "java.math.BigInteger" -> "java.math.BigInteger.valueOf(value)";
    case "byte", "java.lang.Byte" -> "Numbers.toByteExact(value)";
    case "double", "java.lang.Double" -> "(double) value";
    case "float", "java.lang.Float" -> "(float) value";
    case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
    case "long", "java.lang.Long" -> "value";
    case "short", "java.lang.Short" -> "Numbers.toShortExact(value)";
    default -> "";
  };
  case "bigInteger" -> switch (type.name()) {
    case "java.math.BigDecimal" -> "new java.math.BigDecimal(value)";
    case "java.math.BigInteger" -> "value";
    case "byte", "java.lang.Byte" -> "Numbers.toByteExact(Numbers.toLongExact(value))";
    case "double", "java.lang.Double" -> "value.doubleValue()";
    case "float", "java.lang.Float" -> "value.floatValue()";
    case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
    case "long", "java.lang.Long" -> "Numbers.toLongExact(value)";
    case "short", "java.lang.Short" -> "Numbers.toShortExact(Numbers.toLongExact(value))";
    default -> "";
  };
  default -> switch (type.name()) {
    case "java.math.BigDecimal" -> "value";
    case "java.math.BigInteger" -> "Numbers.toBigIntegerExact(value)";
    case "byte", "java.lang.Byte" -> "Numbers.toByteExact(Numbers.toLongExact(value))";
    case "double", "java.lang.Double" -> "value.doubleValue()";
    case "float", "java.lang.Float" -> "value.floatValue()";
    case "int", "java.lang.Integer" -> "Numbers.toIntExact(value)";
    case "long", "java.lang.Long" -> "Numbers.toLongExact(value)";
    case "short", "java.lang.Short" -> "Numbers.toShortExact(Numbers.toLongExact(value))";
    default -> "";
  };
}}