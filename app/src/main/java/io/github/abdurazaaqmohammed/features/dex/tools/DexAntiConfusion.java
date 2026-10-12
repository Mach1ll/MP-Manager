package io.github.abdurazaaqmohammed.features.dex.tools;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renames classes whose simple names are visually confusing (e.g. {@code O0o0},
 * {@code l1I|}, {@code ___}) to readable sequential names, rewriting every
 * reference consistently via dexlib2's {@code DexRewriter}.
 *
 * <p>Pure Java + dexlib2 (no Android dependencies).
 */
public final class DexAntiConfusion {

    private DexAntiConfusion() {}

    /**
     * @return number of classes renamed, 0 if nothing looked confusing.
     */
    public static int deconfuse(File inDex, File outDex) throws IOException {
        DexBackedDexFile dex = DexFileFactory.loadDexFile(inDex, null);

        List<String> confusing = new ArrayList<>();
        for (ClassDef classDef : dex.getClasses()) {
            String type = classDef.getType();
            if (isConfusingType(type)) confusing.add(type);
        }
        if (confusing.isEmpty()) return 0;
        java.util.Collections.sort(confusing);

        final Map<String, String> rename = new LinkedHashMap<>();
        int n = 0;
        for (String type : confusing) {
            rename.put(type, renamedType(type, n++));
        }

        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return new Rewriter<String>() {
                    @Override
                    public String rewrite(String type) {
                        String r = rename.get(type);
                        if (r != null) return r;
                        // Array of a renamed class: "[Lcom/foo/O0o0;"
                        int i = 0;
                        while (i < type.length() && type.charAt(i) == '[') i++;
                        if (i > 0) {
                            String base = rename.get(type.substring(i));
                            if (base != null) return type.substring(0, i) + base;
                        }
                        return type;
                    }
                };
            }
        });

        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(dex);
        DexFileFactory.writeDexFile(outDex.getAbsolutePath(), rewritten);
        return rename.size();
    }

    static boolean isConfusingType(String type) {
        if (!type.startsWith("L") || !type.endsWith(";")) return false;
        String name = type.substring(type.lastIndexOf('/') + 1, type.length() - 1);
        return isConfusingName(name);
    }

    static boolean isConfusingName(String name) {
        if (name.isEmpty()) return false;
        int len = name.length();
        String lower = name.toLowerCase(Locale.ROOT);
        if (len >= 2 && lower.matches("[o0]+")) return true;          // O, 0, o0o0...
        if (len >= 2 && name.matches("[lI1|]+")) return true;          // l, I, 1, |
        if (name.matches("_+")) return true;                          // ___
        if (len >= 4 && lower.matches("[o0li1|]+")) return true;       // mixed o/0/l/I/1/| soup
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c <= 0x1F || c == 0x7F || Character.isWhitespace(c)) return true;
        }
        return false;
    }

    private static String renamedType(String type, int n) {
        String simple = String.format(Locale.ROOT, "C%04d", n);
        int slash = type.lastIndexOf('/');
        return slash < 0 ? "L" + simple + ";" : type.substring(0, slash + 1) + simple + ";";
    }
}
