@param org.lattejava.json.jte.TypeView type
!{
  String writer;
  if (type.isEnum()) {
    writer = type.simpleName() + "::name";
  } else if (type.isString()) {
    writer = "k -> k";
  } else {
    writer = "k -> k.toString()";
  }
}${writer}