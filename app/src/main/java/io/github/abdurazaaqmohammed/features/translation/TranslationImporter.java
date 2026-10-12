package io.github.abdurazaaqmohammed.features.translation;

import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Imports translated strings back into {@code resources.arsc} as a new locale.
 *
 * <p>This closes the loop opened by {@link TranslationExporter}: export the APK's
 * strings, translate the file, then import it with a target locale (e.g. {@code "vi"}).
 * Every translated entry is written into a locale-specific config, so the source
 * language stays untouched and Android picks the new language automatically.
 *
 * <p>Two file formats are accepted (mirroring the exporter):
 * <ul>
 *   <li>{@code .json} — array of {@code {id, name, source, target}}; matched by
 *       resource id first, then by name. This is the reliable path.</li>
 *   <li>{@code .mtd} — {@code { "source" "target" }} blocks; matched by source text
 *       against the default-config values (best effort).</li>
 * </ul>
 *
 * <p>Pure Java + REAndroid arsc (no Android dependencies).
 */
public final class TranslationImporter {

    /** One translated string. */
    public static final class Translation {
        /** Resource name, e.g. "app_name"; null for .mtd files. */
        public final String name;
        /** Resource id, 0 when unknown. */
        public final int resId;
        /** Source text (used for .mtd matching). */
        public final String source;
        /** Translated text; empty means "not translated yet". */
        public final String target;

        public Translation(String name, int resId, String source, String target) {
            this.name = name;
            this.resId = resId;
            this.source = source;
            this.target = target == null ? "" : target;
        }
    }

    /** Outcome of an import. */
    public static final class ImportResult {
        public final int imported;
        public final int skipped;

        public ImportResult(int imported, int skipped) {
            this.imported = imported;
            this.skipped = skipped;
        }
    }

    private TranslationImporter() {}

    /** Parses a {@code .json} or {@code .mtd} translation file. */
    public static List<Translation> parse(File file) throws IOException {
        String lower = file.getName().toLowerCase();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        // Strip BOM if present.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        if (lower.endsWith(".json")) return parseJson(text);
        if (lower.endsWith(".mtd")) return parseMtd(text);
        throw new IOException("Unsupported translation file (expected .json or .mtd): "
                + file.getName());
    }

    private static List<Translation> parseJson(String text) throws IOException {
        try {
            List<Translation> out = new ArrayList<>();
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", null);
                if (name != null && name.isEmpty()) name = null;
                int resId = 0;
                String idStr = o.optString("id", "");
                if (!idStr.isEmpty()) {
                    try {
                        String hex = idStr.startsWith("0x") || idStr.startsWith("0X")
                                ? idStr.substring(2) : idStr;
                        resId = (int) Long.parseLong(hex, 16);
                    } catch (NumberFormatException ignored) {
                    }
                }
                out.add(new Translation(name, resId,
                        o.optString("source", null), o.optString("target", null)));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Invalid translation JSON: " + e.getMessage());
        }
    }

    /**
     * Parses the {@code .mtd} format written by {@link TranslationExporter}:
     * {@code { "source" "target" }} blocks with \" \\ \n \r \t escapes.
     */
    static List<Translation> parseMtd(String text) throws IOException {
        List<Translation> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            // Find block start.
            while (i < n && text.charAt(i) != '{') i++;
            if (i >= n) break;
            i++; // skip '{'
            String source = readQuoted(text, i);
            if (source == null) throw new IOException("Malformed .mtd block (source)");
            i = skipQuoted(text, i);
            String target = readQuoted(text, i);
            if (target == null) throw new IOException("Malformed .mtd block (target)");
            i = skipQuoted(text, i);
            out.add(new Translation(null, 0, source, target == null ? "" : target));
        }
        return out;
    }

    /** Reads a quoted string starting at/after pos (skips whitespace); null if none. */
    private static String readQuoted(String text, int pos) {
        int i = pos;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= n || text.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = text.charAt(i + 1);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(e); break;
                }
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return null; // unterminated
    }

    /** Returns the index just past the quoted string starting at/after pos. */
    private static int skipQuoted(String text, int pos) {
        int i = pos;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= n || text.charAt(i) != '"') return i;
        i++;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }

    /**
     * Writes translations into the resource table under the given locale.
     *
     * @param table        loaded resource table (caller saves it afterwards)
     * @param translations parsed translations
     * @param locale       e.g. "vi", "en-rUS"
     */
    public static ImportResult importTranslations(TableBlock table,
                                                 List<Translation> translations,
                                                 String locale) {
        String qualifiers = normalizeLocale(locale);
        if (qualifiers == null) {
            return new ImportResult(0, translations.size());
        }

        Map<Integer, ResourceEntry> byId = new HashMap<>();
        Map<String, ResourceEntry> byName = new HashMap<>();
        Iterator<ResourceEntry> all = table.getResources();
        while (all.hasNext()) {
            ResourceEntry re;
            try {
                re = all.next();
            } catch (Exception e) {
                continue;
            }
            if (re == null || !"string".equals(re.getType())) continue;
            byId.putIfAbsent(re.getResourceId(), re);
            if (re.getName() != null) byName.putIfAbsent(re.getName(), re);
        }
        // Best-effort source-text index for .mtd files.
        Map<String, ResourceEntry> byValue = new HashMap<>();
        for (ResourceEntry re : byName.values()) {
            String v = defaultValue(table, re);
            if (v != null && !v.isEmpty()) byValue.putIfAbsent(v, re);
        }

        int imported = 0;
        int skipped = 0;
        for (Translation t : translations) {
            if (t.target.isEmpty()) {
                skipped++;
                continue;
            }
            ResourceEntry re = t.resId != 0 ? byId.get(t.resId) : null;
            if (re == null && t.name != null) re = byName.get(t.name);
            if (re == null && t.source != null) re = byValue.get(t.source);
            if (re == null) {
                skipped++;
                continue;
            }
            try {
                Entry e;
                if (t.resId != 0) {
                    // ID-precise: creates the locale entry on this exact resource,
                    // immune to duplicate (package, type, name) entries.
                    e = re.getOrCreate(qualifiers);
                } else {
                    PackageBlock pkg = findPackage(table, re.getPackageName());
                    if (pkg == null) {
                        skipped++;
                        continue;
                    }
                    e = pkg.getOrCreate(qualifiers, "string", re.getName());
                }
                if (e == null) {
                    skipped++;
                    continue;
                }
                e.setValueAsString(t.target);
                imported++;
            } catch (Exception ex) {
                skipped++;
            }
        }
        return new ImportResult(imported, skipped);
    }

    private static PackageBlock findPackage(TableBlock table, String packageName) {
        if (packageName == null) return null;
        for (PackageBlock pkg : table.listPackages()) {
            if (packageName.equals(pkg.getName())) return pkg;
        }
        return null;
    }

    private static String defaultValue(TableBlock table, ResourceEntry re) {
        try {
            Iterator<Entry> it = re.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e == null || e.isNull()) continue;
                try {
                    if (e.getResConfig() != null && !e.getResConfig().isDefault()) continue;
                } catch (Exception ignored) {
                }
                return e.getValueAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Normalizes a locale tag to an aapt qualifier string ("vi", "en-rUS").
     * Returns null when the tag is unusable.
     */
    static String normalizeLocale(String locale) {
        if (locale == null) return null;
        String q = locale.trim().replace('_', '-');
        if (q.isEmpty()) return null;
        // Keep it conservative: ll or ll-rCC (case-insensitive input).
        if (!q.matches("[a-zA-Z]{2,3}(-r[a-zA-Z]{2})?")) return null;
        String[] parts = q.split("-r", 2);
        String lang = parts[0].toLowerCase();
        if (parts.length == 2) return lang + "-r" + parts[1].toUpperCase();
        return lang;
    }
}
