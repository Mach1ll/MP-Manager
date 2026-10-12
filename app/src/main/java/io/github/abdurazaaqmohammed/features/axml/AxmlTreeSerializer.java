package io.github.abdurazaaqmohammed.features.axml;

import java.util.Map;

/**
 * Serializes an {@link AxmlNode} tree back to XML text, ready to be re-encoded to
 * binary AXML with {@code aXMLEncoder.encodeString(String, Context, List)}.
 *
 * <p>All collected namespace declarations are emitted on the root element (mirroring what
 * {@code aXMLDecoder} produces), attributes and text are escaped, and childless,
 * textless elements are written self-closing. Pure Java (no Android dependencies).
 */
public final class AxmlTreeSerializer {

    private AxmlTreeSerializer() {}

    public static String serialize(AxmlNode root, Map<String, String> namespaces) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        writeNode(sb, root, namespaces, true);
        return sb.toString();
    }

    private static void writeNode(StringBuilder sb, AxmlNode node,
                                  Map<String, String> namespaces, boolean isRoot) {
        sb.append('<').append(node.qualifiedName());
        if (isRoot && namespaces != null) {
            for (Map.Entry<String, String> e : namespaces.entrySet()) {
                sb.append(" xmlns");
                if (!e.getKey().isEmpty()) sb.append(':').append(e.getKey());
                sb.append("=\"").append(escapeAttr(e.getValue())).append('"');
            }
        }
        for (AxmlNode.Attr a : node.getAttributes()) {
            if (a.name.isEmpty()) continue;
            sb.append(' ').append(a.qualifiedName())
                    .append("=\"").append(escapeAttr(a.value)).append('"');
        }
        boolean hasKids = !node.getChildren().isEmpty();
        boolean hasText = !node.getText().isEmpty();
        if (!hasKids && !hasText) {
            sb.append("/>\n");
            return;
        }
        sb.append('>');
        if (hasKids) {
            sb.append('\n');
            for (AxmlNode c : node.getChildren()) writeNode(sb, c, namespaces, false);
        } else {
            sb.append(escapeText(node.getText()));
        }
        sb.append("</").append(node.qualifiedName()).append(">\n");
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
