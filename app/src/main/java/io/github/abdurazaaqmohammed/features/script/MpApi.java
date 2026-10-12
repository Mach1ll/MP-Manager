package io.github.abdurazaaqmohammed.features.script;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The {@code mp} object exposed to scripts.
 *
 * <p>File, archive and shell automation for MP Manager scripts:
 * <pre>
 *   mp.log("hello");
 *   var files = mp.list("/sdcard/Download");
 *   mp.zipExtract("/sdcard/app.apk", "resources.arsc", "/sdcard/out.arsc");
 *   mp.toast("done");
 * </pre>
 *
 * <p>Pure Java (UI goes through the {@link Ui} callback) so the logic stays
 * unit-testable off-device.
 */
public class MpApi {

    /** Text sink for {@link #log}. */
    public interface Console {
        void print(String text);
    }

    /** UI callbacks (implemented on the Android side). */
    public interface Ui {
        void toast(String msg);
    }

    private final Console console;
    private final Ui ui;

    public MpApi(Console console, Ui ui) {
        this.console = console;
        this.ui = ui;
    }

    // ------------------------------------------------------------------ log

    /** Prints arguments separated by spaces, with a trailing newline. */
    public void log(Object... args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(stringify(args[i]));
        }
        console.print(sb.toString());
    }

    private static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        }
        return String.valueOf(o);
    }

    // ----------------------------------------------------------------- files

    /** Lists names (not paths) inside a directory. */
    public String[] list(String dir) {
        String[] names = new File(dir).list();
        return names == null ? new String[0] : names;
    }

    public boolean exists(String path) {
        return new File(path).exists();
    }

    public boolean isDirectory(String path) {
        return new File(path).isDirectory();
    }

    public long length(String path) {
        return new File(path).length();
    }

    /** Reads a whole text file as UTF-8. */
    public String read(String path) throws java.io.IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /** Writes text (UTF-8), creating parent directories. */
    public void write(String path, String text) throws java.io.IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Appends text (UTF-8). */
    public void append(String path, String text) throws java.io.IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(f, true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean mkdir(String path) {
        return new File(path).mkdir();
    }

    public boolean mkdirs(String path) {
        return new File(path).mkdirs();
    }

    /** Deletes a file or an empty directory. */
    public boolean delete(String path) {
        return new File(path).delete();
    }

    public void copy(String src, String dst) throws java.io.IOException {
        File d = new File(dst);
        File parent = d.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.copy(new File(src).toPath(), d.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void move(String src, String dst) throws java.io.IOException {
        copy(src, dst);
        if (!new File(src).delete()) throw new java.io.IOException("Cannot delete " + src);
    }

    public String fileName(String path) {
        return new File(path).getName();
    }

    public String parent(String path) {
        File p = new File(path).getParentFile();
        return p == null ? "" : p.getAbsolutePath();
    }

    // ------------------------------------------------------------------- zip

    /** Lists entry names inside a zip/apk. */
    public String[] zipList(String zipPath) throws java.io.IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipPath)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) names.add(en.nextElement().getName());
        }
        return names.toArray(new String[0]);
    }

    /** Extracts one zip entry to a file. */
    public void zipExtract(String zipPath, String entry, String outPath)
            throws java.io.IOException {
        File out = new File(outPath);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipFile zf = new ZipFile(zipPath)) {
            ZipEntry ze = zf.getEntry(entry);
            if (ze == null) throw new java.io.IOException("No such entry: " + entry);
            try (InputStream in = zf.getInputStream(ze);
                 OutputStream outStream = new FileOutputStream(out)) {
                copyStream(in, outStream);
            }
        }
    }

    /** Adds (or replaces) a file in the zip under the given entry name. */
    public void zipAdd(String zipPath, String filePath, String entryName)
            throws java.io.IOException {
        File tmp = File.createTempFile("mpzip", ".tmp");
        try {
            byte[] data;
            try (InputStream in = new FileInputStream(filePath)) {
                data = readAll(in);
            }
            try (ZipFile zf = new ZipFile(zipPath);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().equals(entryName)) continue;
                    zos.putNextEntry(new ZipEntry(ze.getName()));
                    if (!ze.isDirectory()) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            copyStream(in, zos);
                        }
                    }
                    zos.closeEntry();
                }
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(data);
                zos.closeEntry();
            }
            Files.move(tmp.toPath(), new File(zipPath).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Removes one entry from the zip. */
    public void zipRemove(String zipPath, String entry) throws java.io.IOException {
        File tmp = File.createTempFile("mpzip", ".tmp");
        try {
            boolean found = false;
            try (ZipFile zf = new ZipFile(zipPath);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().equals(entry)) {
                        found = true;
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(ze.getName()));
                    if (!ze.isDirectory()) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            copyStream(in, zos);
                        }
                    }
                    zos.closeEntry();
                }
            }
            if (!found) throw new java.io.IOException("No such entry: " + entry);
            Files.move(tmp.toPath(), new File(zipPath).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ----------------------------------------------------------------- shell

    /**
     * Runs a shell command ({@code sh -c}) and returns its stdout.
     * Throws with stderr attached when the exit code is non-zero.
     */
    public String exec(String command) throws java.io.IOException {
        Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(false).start();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread t1 = pump(p.getInputStream(), outBuf);
        Thread t2 = pump(p.getErrorStream(), errBuf);
        int code;
        try {
            code = p.waitFor();
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted: " + command);
        }
        String out = outBuf.toString(StandardCharsets.UTF_8.name());
        if (code != 0) {
            throw new java.io.IOException("exit " + code + ": "
                    + errBuf.toString(StandardCharsets.UTF_8.name()));
        }
        return out;
    }

    // -------------------------------------------------------------------- ui

    public void toast(String msg) {
        ui.toast(msg);
    }

    // --------------------------------------------------------------- helpers

    private static Thread pump(InputStream in, OutputStream out) {
        Thread t = new Thread(() -> {
            try {
                copyStream(in, out);
            } catch (java.io.IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void copyStream(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copyStream(in, bos);
        return bos.toByteArray();
    }
}
