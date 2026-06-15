@param org.lattejava.json.jte.TypeView type
@param String key
@param String val
!{
  String call;
  if (type.isEnum()) {
    call = "string(" + key + ", " + val + " == null ? null : " + val + ".name())";
  } else {
    call = switch (type.name()) {
      case "java.lang.String" -> "string(" + key + ", " + val + ")";
      case "boolean", "java.lang.Boolean" -> "bool(" + key + ", " + val + ")";
      case "byte", "short", "int", "long",
           "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> "integer(" + key + ", " + val + ")";
      case "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "decimal(" + key + ", " + val + ")";
      case "java.math.BigInteger" -> "bigInteger(" + key + ", " + val + ")";
      case "java.time.Instant" -> "instant(" + key + ", " + val + ")";
      case "java.util.UUID" -> "uuid(" + key + ", " + val + ")";
      default -> "string(" + key + ", " + val + " == null ? null : " + val + ".toString())";
    };
  }
}${call}