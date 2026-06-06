@param org.lattejava.json.jte.TypeView type
@param String expr
!{
  String call;
  if (type.isNested()) {
    call = ".raw(" + expr + " == null ? null : " + type.nestedCompanion() + ".toJSON(" + expr + "))";
  } else if (type.isEnum()) {
    call = ".string(" + expr + " == null ? null : " + expr + ".name())";
  } else {
    call = switch (type.name()) {
      case "java.lang.String" -> ".string(" + expr + ")";
      case "boolean", "java.lang.Boolean" -> ".bool(" + expr + ")";
      case "byte", "short", "int", "long" -> ".integer(" + expr + ")";
      case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> ".integer(" + expr + " == null ? null : " + expr + ".longValue())";
      case "float", "double" -> ".decimal(java.math.BigDecimal.valueOf(" + expr + "))";
      case "java.lang.Float", "java.lang.Double" -> ".decimal(" + expr + " == null ? null : java.math.BigDecimal.valueOf(" + expr + "))";
      case "java.math.BigInteger" -> ".bigInteger(" + expr + ")";
      case "java.math.BigDecimal" -> ".decimal(" + expr + ")";
      default -> ".string(" + expr + " == null ? null : " + expr + ".toString())";
    };
  }
}${call}