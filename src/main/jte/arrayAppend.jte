@param org.lattejava.json.jte.TypeView type
@param String expr
!{
  String call;
  if (type.isEnum()) {
    call = ".stringElement(" + expr + " == null ? null : " + expr + ".name())";
  } else {
    call = switch (type.name()) {
      case "java.lang.String" -> ".stringElement(" + expr + ")";
      case "boolean", "java.lang.Boolean" -> ".boolElement(" + expr + ")";
      case "byte", "short", "int", "long" -> ".integerElement(" + expr + ")";
      case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long" -> ".integerElement(" + expr + " == null ? null : " + expr + ".longValue())";
      case "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> ".decimalElement(" + expr + ")";
      case "java.math.BigInteger" -> ".bigIntegerElement(" + expr + ")";
      case "java.time.Instant" -> ".instantElement(" + expr + ")";
      case "java.util.UUID" -> ".uuidElement(" + expr + ")";
      default -> ".stringElement(" + expr + " == null ? null : " + expr + ".toString())";
    };
  }
}${call}