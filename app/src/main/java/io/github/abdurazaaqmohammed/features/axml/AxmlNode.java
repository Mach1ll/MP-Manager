package io.github.abdurazaaqmohammed.features.axml;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable in-memory model of a single XML element for the AXML tree editor.
 *
 * <p>Built from the text produced by {@code aXMLDecoder}; serialized back to text with
 * {@link AxmlTreeSerializer} and re-encoded to binary AXML with {@code aXMLEncoder}.
 * Pure Java (no Android dependencies) so it can be unit-tested on the JVM.
 */
public class AxmlNode {

    /** A single attribute: qualified name split into prefix + local name. */
    public static final class Attr {
        public String prefix; // empty = no prefix
        public String name;   // local name, never null
        public String value;  // never null

        public Attr(String prefix, String name, String value) {
            this.prefix = prefix == null ? "" : prefix;
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }

        public String qualifiedName() {
            return prefix.isEmpty() ? name : prefix + ':' + name;
        }

        public Attr copy() {
            return new Attr(prefix, name, value);
        }
    }

    private String tag;
    private String prefix;
    private final List<Attr> attributes = new ArrayList<>();
    private final List<AxmlNode> children = new ArrayList<>();
    private AxmlNode parent;
    private String text = "";
    private boolean expanded = true;

    public AxmlNode(String prefix, String tag) {
        this.prefix = prefix == null ? "" : prefix;
        this.tag = tag == null ? "" : tag;
    }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag == null ? "" : tag; }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix == null ? "" : prefix; }

    public String qualifiedName() {
        return prefix.isEmpty() ? tag : prefix + ':' + tag;
    }

    public List<Attr> getAttributes() { return attributes; }
    public List<AxmlNode> getChildren() { return children; }
    public AxmlNode getParent() { return parent; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text == null ? "" : text; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }

    public void addChild(AxmlNode child) {
        child.parent = this;
        children.add(child);
    }

    public void removeChild(AxmlNode child) {
        if (children.remove(child)) child.parent = null;
    }

    public int depth() {
        int d = 0;
        for (AxmlNode p = parent; p != null; p = p.parent) d++;
        return d;
    }

    /** Short one-line summary used by the tree rows, e.g. {@code <activity android:name=".Main">}. */
    public String summary() {
        StringBuilder sb = new StringBuilder("<").append(qualifiedName());
        int shown = Math.min(attributes.size(), 2);
        for (int i = 0; i < shown; i++) {
            Attr a = attributes.get(i);
            sb.append(' ').append(a.qualifiedName()).append("=\"").append(a.value).append('"');
        }
        if (attributes.size() > shown) sb.append(" (+").append(attributes.size() - shown).append(" more)");
        if (children.isEmpty() && text.isEmpty()) sb.append('/');
        return sb.append('>').toString();
    }
}
