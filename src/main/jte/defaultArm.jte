@param org.lattejava.json.jte.CompanionView view
@param String value
!{
  String arm;
  if (!view.catchAll().isEmpty()) {
    arm = "default -> this." + view.catchAll() + ".put(key, " + value + ");";
  } else if (view.strict()) {
    arm = "default -> throw new JSONProcessingException(\"Unknown JSON key [\" + key + \"] for type [" + view.simpleName() + "]\");";
  } else {
    arm = "default -> { /* lenient: ignore unknown key */ }";
  }
}${arm}