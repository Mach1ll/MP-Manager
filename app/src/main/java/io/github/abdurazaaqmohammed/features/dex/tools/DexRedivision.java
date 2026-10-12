package io.github.abdurazaaqmohammed.features.dex.tools;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits ("redivides") the classes of one or more dex files across several dex files
 * so that each output stays below the 64K method-reference limit.
 *
 * <p>Each output dex is self-contained: {@code DexPool.internClass} pulls in every
 * type, method, field and string the partition's classes reference, so cross-dex
 * references keep resolving at runtime through the multidex class loader.
 * Pure Java + dexlib2 (no Android dependencies).
 */
public final class DexRedivision {

    /** Headroom below the 65536 hard limit. */
    public static final int DEFAULT_MAX_METHOD_REFS = 60000;

    private DexRedivision() {}

    /**
     * Combines {@code dexFiles} and repartitions their classes.
     *
     * @return the written dex files: {@code classes.dex}, {@code classes2.dex}, ...
     */
    public static List<File> redivide(List<File> dexFiles, File outDir, int maxMethodRefs)
            throws IOException {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Cannot create output dir: " + outDir);
        }
        List<ClassDef> allClasses = new ArrayList<>();
        Opcodes opcodes = null;
        for (File f : dexFiles) {
            DexBackedDexFile dex = DexFileFactory.loadDexFile(f, null);
            if (opcodes == null) opcodes = dex.getOpcodes();
            for (ClassDef classDef : dex.getClasses()) allClasses.add(classDef);
        }
        if (opcodes == null) throw new IOException("No dex files to redivide");

        List<File> written = new ArrayList<>();
        List<ClassDef> current = new ArrayList<>();
        int currentRefs = 0;
        int index = 0;
        for (ClassDef classDef : allClasses) {
            int refs = countMethodRefs(classDef);
            if (!current.isEmpty() && currentRefs + refs > maxMethodRefs) {
                written.add(writePartition(opcodes, current, outDir, index++));
                current = new ArrayList<>();
                currentRefs = 0;
            }
            current.add(classDef);
            currentRefs += refs;
        }
        if (!current.isEmpty()) {
            written.add(writePartition(opcodes, current, outDir, index));
        }
        return written;
    }

    private static File writePartition(Opcodes opcodes, List<ClassDef> classes,
                                       File outDir, int index) throws IOException {
        DexPool pool = new DexPool(opcodes);
        for (ClassDef classDef : classes) {
            pool.internClass(classDef);
        }
        File out = new File(outDir, index == 0 ? "classes.dex" : "classes" + (index + 1) + ".dex");
        pool.writeTo(new FileDataStore(out));
        return out;
    }

    /** Distinct method references touched by a class: its own methods plus referenced ones. */
    static int countMethodRefs(ClassDef classDef) {
        Set<String> refs = new HashSet<>();
        for (Method method : classDef.getMethods()) {
            refs.add(methodKey(method));
            MethodImplementation impl = method.getImplementation();
            if (impl == null) continue;
            for (Instruction instruction : impl.getInstructions()) {
                if (instruction instanceof ReferenceInstruction) {
                    Reference ref = ((ReferenceInstruction) instruction).getReference();
                    if (ref instanceof MethodReference) {
                        refs.add(methodKey((MethodReference) ref));
                    }
                }
            }
        }
        return refs.size();
    }

    private static String methodKey(MethodReference mr) {
        return mr.getDefiningClass() + "->" + mr.getName()
                + mr.getParameterTypes() + mr.getReturnType();
    }
}
