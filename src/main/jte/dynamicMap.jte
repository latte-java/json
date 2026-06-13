@import org.lattejava.json.jte.Component
@param Component c
@param boolean omitNulls
  private static String ${c.name()}ToJSON(@template.declType(type = c.type()) v) {
    var b = new JSONBuilder(${omitNulls});
    for (var en : v.entrySet()) b.any(en.getKey(), en.getValue());
    return b.build();
  }
