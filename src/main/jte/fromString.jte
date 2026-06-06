@param org.lattejava.json.jte.TypeView type
@param String expr
${type.isEnum() ? "Conversions.toEnum(" + type.simpleName() + ".class, " + expr + ")" : type.isString() ? expr : "Conversions.to" + type.simpleName() + "(" + expr + ")"}