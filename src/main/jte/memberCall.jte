@param org.lattejava.json.jte.TypeView type
@param String key
@param String val
${type.isEnum() ? "string(" + key + ", " + val + " == null ? null : " + val + ".name())" : switch (type.name()) {
  case "java.lang.String" -> "string(" + key + ", " + val + ")";
  case "boolean", "java.lang.Boolean" -> "bool(" + key + ", " + val + ")";
  case "byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> "integer(" + key + ", " + val + ")";
  case "float", "double" -> "decimal(" + key + ", java.math.BigDecimal.valueOf(" + val + "))";
  case "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "decimal(" + key + ", " + val + ")";
  case "java.math.BigInteger" -> "bigInteger(" + key + ", " + val + ")";
  default -> "string(" + key + ", " + val + " == null ? null : " + val + ".toString())";
}}