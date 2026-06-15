@param org.lattejava.json.jte.CompanionView view
@param String value
!{
  String arm;
  if (!view.catchAll().isEmpty()) {
    arm = "default -> this." + view.catchAll() + ".put(keyName(field), " + value + ");";
  } else if (view.strict()) {
    arm = "default -> throw new JSONProcessingException(\"Unknown JSON key [\" + keyName(field) + \"] for type [" + view.simpleName() + "]\");";
  } else {
    arm = "default -> { /* known field, mismatched JSON shape: lenient ignore */ }";
  }
}${arm}