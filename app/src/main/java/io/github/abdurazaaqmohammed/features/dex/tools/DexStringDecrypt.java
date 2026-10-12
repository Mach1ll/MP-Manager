package io.github.abdurazaaqmohammed.features.dex.tools;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Statically decrypts StringFog-style encrypted strings.
 *
 * <p>String-confusion tools replace string constants with ciphertext and insert
 * {@code decrypt(key, data)} calls. This tool finds the tight instruction pattern
 *
 * <pre>
 *   const-string vA, "..."            // key or ciphertext
 *   const-string vB, "..."            // ciphertext or key
 *   invoke-static {vA, vB}, L...;-&gt;decrypt(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
 *   move-result-object vC
 * </pre>
 *
 * <p>tries the StringFog key schedule (cyclic XOR + Base64) in both argument orders,
 * and -- only when the result looks like sane text -- replaces the four instructions
 * with a single {@code const-string vC, "<decrypted>"}.
 *
 * <p>Pure Java + dexlib2 (no Android dependencies).
 */
public final class DexStringDecrypt {

    private static final String STRING_TYPE = "Ljava/lang/String;";

    private DexStringDecrypt() {}

    /**
     * @return number of strings decrypted, 0 if none matched.
     */
    public static int decryptStrings(File inDex, File outDex) throws IOException {
        DexBackedDexFile dex = DexFileFactory.loadDexFile(inDex, null);
        Opcodes opcodes = dex.getOpcodes();

        List<ClassDef> newClasses = new ArrayList<>();
        int decrypted = 0;
        for (ClassDef classDef : dex.getClasses()) {
            List<Method> newMethods = new ArrayList<>();
            boolean changed = false;
            for (Method method : classDef.getMethods()) {
                DecryptResult r = tryDecryptMethod(method);
                if (r != null) {
                    newMethods.add(r.method);
                    decrypted += r.count;
                    changed = true;
                } else {
                    newMethods.add(method);
                }
            }
            if (changed) {
                newClasses.add(new ImmutableClassDef(
                        classDef.getType(), classDef.getAccessFlags(), classDef.getSuperclass(),
                        classDef.getInterfaces(), classDef.getSourceFile(),
                        classDef.getAnnotations(), classDef.getFields(), newMethods));
            } else {
                newClasses.add(classDef);
            }
        }
        if (decrypted == 0) return 0;

        DexPool pool = new DexPool(opcodes);
        for (ClassDef c : newClasses) pool.internClass(c);
        pool.writeTo(new FileDataStore(outDex));
        return decrypted;
    }

    private static final class DecryptResult {
        final Method method;
        final int count;
        DecryptResult(Method method, int count) {
            this.method = method;
            this.count = count;
        }
    }

    private static DecryptResult tryDecryptMethod(Method method) {
        MethodImplementation impl = method.getImplementation();
        if (impl == null) return null;
        List<Instruction> insns = new ArrayList<>();
        for (Instruction i : impl.getInstructions()) insns.add(i);

        List<Instruction> out = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < insns.size(); i++) {
            String decrypted = null;
            int resultReg = -1;
            if (i + 3 < insns.size()) {
                decrypted = matchDecryptPattern(insns, i);
                if (decrypted != null) {
                    resultReg = ((OneRegisterInstruction) insns.get(i + 3)).getRegisterA();
                }
            }
            if (decrypted != null) {
                out.add(new ImmutableInstruction21c(Opcode.CONST_STRING, resultReg,
                        new ImmutableStringReference(decrypted)));
                i += 3;
                count++;
            } else {
                out.add(insns.get(i));
            }
        }
        if (count == 0) return null;
        ImmutableMethodImplementation newImpl = new ImmutableMethodImplementation(
                impl.getRegisterCount(), out, impl.getTryBlocks(), impl.getDebugItems());
        Method newMethod = new ImmutableMethod(method.getDefiningClass(), method.getName(),
                method.getParameters(), method.getReturnType(), method.getAccessFlags(),
                method.getAnnotations(), Collections.emptySet(), newImpl);
        return new DecryptResult(newMethod, count);
    }

    /** Returns the decrypted string if insns[i..i+3] is a decrypt call, else null. */
    private static String matchDecryptPattern(List<Instruction> insns, int i) {
        Instruction c1 = insns.get(i);
        Instruction c2 = insns.get(i + 1);
        Instruction invoke = insns.get(i + 2);
        Instruction moveRes = insns.get(i + 3);

        if (c1.getOpcode() != Opcode.CONST_STRING || c2.getOpcode() != Opcode.CONST_STRING) return null;
        if (moveRes.getOpcode() != Opcode.MOVE_RESULT_OBJECT) return null;
        Opcode invOp = invoke.getOpcode();
        if (invOp != Opcode.INVOKE_STATIC && invOp != Opcode.INVOKE_STATIC_RANGE) return null;
        if (!(invoke instanceof ReferenceInstruction)) return null;
        Reference ref = ((ReferenceInstruction) invoke).getReference();
        if (!(ref instanceof MethodReference)) return null;
        MethodReference mr = (MethodReference) ref;
        if (!mr.getName().toLowerCase(Locale.ROOT).contains("decrypt")) return null;
        if (!STRING_TYPE.equals(mr.getReturnType())) return null;
        List<? extends CharSequence> params = mr.getParameterTypes();
        if (params.size() != 2) return null;
        if (!STRING_TYPE.contentEquals(params.get(0)) || !STRING_TYPE.contentEquals(params.get(1))) return null;

        int regA = ((OneRegisterInstruction) c1).getRegisterA();
        int regB = ((OneRegisterInstruction) c2).getRegisterA();
        int[] callRegs = invokeRegisters(invoke);
        if (callRegs == null || callRegs.length != 2) return null;

        String s1 = stringValue(c1);
        String s2 = stringValue(c2);
        if (s1 == null || s2 == null) return null;

        String first, second;
        if (callRegs[0] == regA && callRegs[1] == regB) {
            first = s1;
            second = s2;
        } else if (callRegs[0] == regB && callRegs[1] == regA) {
            first = s2;
            second = s1;
        } else {
            return null;
        }
        String r = stringFogDecrypt(first, second);
        return r;
    }

    private static int[] invokeRegisters(Instruction invoke) {
        if (invoke instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction f = (FiveRegisterInstruction) invoke;
            int n = f.getRegisterCount();
            int[] regs = new int[n];
            if (n > 0) regs[0] = f.getRegisterC();
            if (n > 1) regs[1] = f.getRegisterD();
            if (n > 2) regs[2] = f.getRegisterE();
            if (n > 3) regs[3] = f.getRegisterF();
            if (n > 4) regs[4] = f.getRegisterG();
            return regs;
        } else if (invoke instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) invoke;
            int start = r.getStartRegister();
            int n = r.getRegisterCount();
            int[] regs = new int[n];
            for (int k = 0; k < n; k++) regs[k] = start + k;
            return regs;
        }
        return null;
    }

    private static String stringValue(Instruction constString) {
        if (!(constString instanceof ReferenceInstruction)) return null;
        Reference ref = ((ReferenceInstruction) constString).getReference();
        return ref instanceof StringReference ? ((StringReference) ref).getString() : null;
    }

    /**
     * StringFog key schedule: Base64-decode the ciphertext, then XOR each byte with
     * the key bytes cyclically.
     *
     * <p>Which argument is the key is not known statically, so both orders are tried.
     * A candidate is accepted only when it looks like plausible text AND re-encrypting
     * it reproduces the ciphertext (self-consistency). When both orders validate, the
     * more natural-looking text wins.
     */
    static String stringFogDecrypt(String a, String b) {
        String r1 = tryDecryptOrder(a, b);
        String r2 = tryDecryptOrder(b, a);
        if (r1 != null && r2 != null) {
            return naturalScore(r1) >= naturalScore(r2) ? r1 : r2;
        }
        return r1 != null ? r1 : r2;
    }

    /** Tries one (data, key) assignment. Returns null when it does not validate. */
    private static String tryDecryptOrder(String data, String key) {
        if (data == null || key == null || data.isEmpty() || key.isEmpty()) return null;
        final String padded;
        final byte[] raw;
        try {
            padded = padBase64(data);
            raw = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) (raw[i] ^ kb[i % kb.length]);
        }
        String s = new String(out, StandardCharsets.UTF_8);
        if (!looksSane(s)) return null;
        // Self-consistency: re-encrypting must reproduce the ciphertext exactly.
        byte[] sb = s.getBytes(StandardCharsets.UTF_8);
        byte[] re = new byte[sb.length];
        for (int i = 0; i < sb.length; i++) {
            re[i] = (byte) (sb[i] ^ kb[i % kb.length]);
        }
        if (!Base64.getEncoder().encodeToString(re).equals(padded)) return null;
        return s;
    }

    /** Higher = more like natural text. Used to break ties between the two orders. */
    private static int naturalScore(String s) {
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                score += 2;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c > 0x7F) {
                score += 1;
            } else if (".,;:!?()[]{}-_/\\@#%^&*+=<>\"'".indexOf(c) >= 0) {
                score += 1;
            }
        }
        return score;
    }

    private static String padBase64(String s) {
        int mod = s.length() % 4;
        if (mod == 2) return s + "==";
        if (mod == 3) return s + "=";
        return s;
    }

    static boolean looksSane(String s) {
        if (s.isEmpty() || s.length() > 2000) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }
}
