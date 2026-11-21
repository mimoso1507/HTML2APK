package com.h2a.converter;

import com.android.apksig.ApkSigner;

import java.io.*;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/** Toma la APK plantilla, le inyecta el HTML/configuración, parchea el manifiesto y la firma. */
public final class ApkBuilder {
    public static final class Input {
        public String name = "MiApp", packageId = "com.app.miapp", version = "1.0.0";
        public int versionCode = 1, minSdk = 21, targetSdk = 34;
        public Set<String> permissions = new HashSet<>();
        public String configJson = "{}";
        public Map<String, byte[]> www = new LinkedHashMap<>();
        public byte[] iconPng; // opcional (192x192)
    }

    private static final class Counting extends FilterOutputStream {
        long n;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
    }

    public static void build(File template, Input in, PrivateKey key, X509Certificate cert, File outApk) throws Exception {
        File unsigned = new File(outApk.getParentFile(), outApk.getName() + ".unsigned");
        try (ZipFile zf = new ZipFile(template);
             Counting co = new Counting(new BufferedOutputStream(new FileOutputStream(unsigned)));
             ZipOutputStream zos = new ZipOutputStream(co)) {
            Set<String> iconPaths = new HashSet<>();
            ZipEntry arscEntry = zf.getEntry("resources.arsc");
            if (arscEntry != null) iconPaths = ArscIcons.iconPaths(readAll(zf.getInputStream(arscEntry)), "ic_launcher");
            if (iconPaths.isEmpty()) { // respaldo: la PNG mas grande de res/
                long best = -1; String bestName = null;
                for (Enumeration<? extends ZipEntry> e2 = zf.entries(); e2.hasMoreElements();) {
                    ZipEntry z2 = e2.nextElement();
                    if (z2.getName().startsWith("res/") && z2.getName().endsWith(".png") && !z2.getName().endsWith(".9.png") && z2.getSize() > best) { best = z2.getSize(); bestName = z2.getName(); }
                }
                if (bestName != null) iconPaths.add(bestName);
            }
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (ze.isDirectory() || n.startsWith("META-INF/")) continue;
                byte[] data = readAll(zf.getInputStream(ze));
                if (n.equals("AndroidManifest.xml")) {
                    data = patchManifest(data, in);
                } else if (in.iconPng != null && iconPaths.contains(n)) {
                    data = in.iconPng;
                }
                if (n.equals("resources.arsc")) putStored(zos, co, n, data);
                else putDeflated(zos, n, data);
            }
            for (Map.Entry<String, byte[]> f : in.www.entrySet()) {
                String p = f.getKey().replace('\\', '/');
                while (p.startsWith("/")) p = p.substring(1);
                if (p.isEmpty() || p.contains("..")) continue;
                putDeflated(zos, "assets/www/" + p, f.getValue());
            }
            putDeflated(zos, "assets/h2a.json", in.configJson.getBytes("UTF-8"));
        }
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("h2a", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned).setOutputApk(outApk)
                .setMinSdkVersion(21)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign();
        unsigned.delete();
    }

    static byte[] patchManifest(byte[] manifest, Input in) {
        AxmlPatcher.Edits e = new AxmlPatcher.Edits();
        e.stringReplace.put("com.h2a.placeholderpackage", in.packageId);
        e.stringReplace.put("H2A_APPLABEL", in.name);
        e.stringReplace.put("H2A_VERSIONNAME", in.version);
        Set<String> keep = new HashSet<>(in.permissions);
        keep.add("android.permission.INTERNET");
        e.keepPermissions = keep;
        e.intAttrs.put("versionCode", in.versionCode);
        e.intAttrs.put("minSdkVersion", in.minSdk);
        e.intAttrs.put("targetSdkVersion", in.targetSdk);
        return AxmlPatcher.patch(manifest, e);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream i = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void putDeflated(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** Entrada sin comprimir y alineada a 4 bytes (obligatorio para resources.arsc en Android 11+). */
    private static void putStored(ZipOutputStream zos, Counting co, String name, byte[] data) throws IOException {
        zos.flush();
        int nameLen = name.getBytes("UTF-8").length;
        long dataOffset = co.n + 30 + nameLen;
        int pad = (int) ((4 - dataOffset % 4) % 4);
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32(); crc.update(data);
        e.setCrc(crc.getValue());
        if (pad != 0) {
            byte[] extra = new byte[pad + 4];
            extra[0] = (byte) 0x35; extra[1] = (byte) 0xD9; // id de relleno
            extra[2] = (byte) pad;
            e.setExtra(extra);
        }
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }
}
