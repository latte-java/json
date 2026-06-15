@import org.lattejava.json.jte.Component
@param Component c
  private static void ${c.name()}Write(JSONWriter w, @template.declType(type = c.type()) v) {
    w.beginObject();
    for (var en : v.entrySet()) w.any(en.getKey(), en.getValue());
    w.endObject();
  }
