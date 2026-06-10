@param org.lattejava.json.jte.TypeView type
!{
  String decl;
  if (type.isMap()) {
    decl = "Map<" + type.key().decl() + ", " + type.value().decl() + ">";
  } else if (type.isCollection()) {
    decl = type.kind() + "<" + type.element().decl() + ">";
  } else {
    decl = type.decl();
  }
}${decl}