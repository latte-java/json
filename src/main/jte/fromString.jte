@param org.lattejava.json.jte.TypeView type
@param String expr
!{
  String call;
  if (type.isEnum()) {
    call = "Conversions.toEnum(" + type.simpleName() + ".class, " + expr + ")";
  } else if (type.isString()) {
    call = expr;
  } else {
    call = "Conversions.to" + type.simpleName() + "(" + expr + ")";
  }
}${call}