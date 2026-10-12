package io.github.abdurazaaqmohammed.features.axml;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

/**
 * Builds an {@link AxmlNode} tree from decoded AXML text (as produced by
 * {@code aXMLDecoder.decodeAsString()}).
 *
 * <p>Uses SAX so attributes keep their document order (the JDK DOM implementation
 * reorders namespaced attributes). Namespace declarations ({@code xmlns:*}) arrive via
 * {@code startPrefixMapping} and are collected separately, re-emitted on the root
 * element by {@link AxmlTreeSerializer}. Whitespace-only character data (pretty-print
 * indentation) is dropped so repeated parse/serialize cycles stay stable. Comments and
 * processing instructions are dropped. Pure Java (no Android dependencies).
 */
public final class AxmlTreeParser {

    public static final class Result {
        public final AxmlNode root;
        /** prefix -&gt; namespace URI, first-seen order. */
        public final Map<String, String> namespaces;

        Result(AxmlNode root, Map<String, String> namespaces) {
            this.root = root;
            this.namespaces = namespaces;
        }
    }

    private AxmlTreeParser() {}

    public static Result parse(String xml) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        // Decoded AXML never carries a DOCTYPE; forbid it outright (XXE hardening).
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        spf.setXIncludeAware(false);
        Handler handler = new Handler();
        spf.newSAXParser().parse(new InputSource(new StringReader(xml)), handler);
        if (handler.root == null) throw new IllegalArgumentException("No root element in XML");
        return new Result(handler.root, handler.namespaces);
    }

    private static final class Handler extends DefaultHandler {
        AxmlNode root;
        final Map<String, String> namespaces = new LinkedHashMap<>();
        final Deque<AxmlNode> stack = new ArrayDeque<>();
        final StringBuilder text = new StringBuilder();

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            if (uri == null || uri.isEmpty()
                    || "http://www.w3.org/XML/1998/namespace".equals(uri)) return;
            namespaces.putIfAbsent(prefix == null ? "" : prefix, uri);
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) throws SAXException {
            flushText();
            int colon = qName.indexOf(':');
            AxmlNode node = new AxmlNode(
                    colon < 0 ? "" : qName.substring(0, colon),
                    localName == null || localName.isEmpty() ? qName : localName);
            for (int i = 0; i < attributes.getLength(); i++) {
                String aqName = attributes.getQName(i);
                int c = aqName.indexOf(':');
                String aLocal = attributes.getLocalName(i);
                node.getAttributes().add(new AxmlNode.Attr(
                        c < 0 ? "" : aqName.substring(0, c),
                        (aLocal == null || aLocal.isEmpty()) ? aqName : aLocal,
                        attributes.getValue(i)));
            }
            if (stack.isEmpty()) {
                root = node;
            } else {
                stack.peek().addChild(node);
            }
            stack.push(node);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            stack.pop();
        }

        private void flushText() {
            if (text.length() > 0) {
                String v = text.toString();
                if (!v.trim().isEmpty() && !stack.isEmpty()) {
                    AxmlNode top = stack.peek();
                    top.setText(top.getText() + v);
                }
                text.setLength(0);
            }
        }
    }
}
