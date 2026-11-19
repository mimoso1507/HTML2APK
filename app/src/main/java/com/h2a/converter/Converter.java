package com.h2a.converter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.util.*;

/** Orquesta la conversión: opciones + archivos -> APK firmada y/o proyecto ZIP de Windows. */
public final class Converter {
    public static final class Result {
        public File apk, exeZip;
    }

    public static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        return n.isEmpty() ? "app" : n;
    }

    public static String packageOf(JSONObject o, String name) {
        String p = o.optString("packageId", "").trim();
        return p.isEmpty() ? "com.app." + slug(name) : p;
    }

    public static Result convert(JSONObject req, Map<String, byte[]> files, File templateApk, PrivateKey key, X509Certificate cert,
                                 byte[] iconApk, byte[] iconRaw, File outDir) throws Exception {
        JSONObject o = req.getJSONObject("options");
        boolean minima = "minima".equals(o.optString("mode", "minima"));
        String name = o.optString("name", "MiApp").trim();
        if (name.isEmpty()) name = "MiApp";
        JSONArray targets = o.optJSONArray("targets");
        boolean wantApk = targets == null, wantExe = targets == null;
        if (targets != null) for (int i = 0; i < targets.length(); i++) {
            if ("apk".equals(targets.optString(i))) wantApk = true;
            if ("exe".equals(targets.optString(i))) wantExe = true;
        }
        if (files == null || files.isEmpty()) throw new IOException("Primero elige tu archivo HTML o carpeta.");

        String entry = null;
        for (String p : files.keySet()) if (p.equalsIgnoreCase("index.html") || p.equalsIgnoreCase("index.htm")) { entry = p; break; }
        if (entry == null) for (String p : files.keySet()) if (p.toLowerCase(Locale.ROOT).matches(".*\\.html?$")) {
            if (entry == null || p.length() < entry.length()) entry = p;
        }
        if (entry == null) throw new IOException("No hay ningun archivo .html entre los seleccionados.");

        // Si se eligio la carpeta con subcarpeta raiz comun, ya viene sin ella; la ruta de entrada es relativa a www/.
        String pkgId = packageOf(o, name);
        outDir.mkdirs();
        Result r = new Result();

        if (wantApk) {
            ApkBuilder.Input in = new ApkBuilder.Input();
            in.name = name; in.packageId = pkgId;
            in.www = new LinkedHashMap<>(files);
            in.iconPng = iconApk;
            if (!minima) {
                in.version = o.optString("version", "1.0.0");
                in.versionCode = Math.max(1, o.optInt("versionCode", 1));
                in.minSdk = Math.max(21, o.optInt("minSdk", 21));
                in.targetSdk = Math.max(in.minSdk, o.optInt("targetSdk", 34));
                JSONArray ps = o.optJSONArray("permissions");
                if (ps != null) for (int i = 0; i < ps.length(); i++) {
                    String perm = Perms.MAP.get(ps.optString(i));
                    if (perm != null) in.permissions.add(perm);
                }
                if (o.optBoolean("keepAwake", false)) in.permissions.add("android.permission.WAKE_LOCK");
            }
            JSONObject cfg = new JSONObject();
            cfg.put("entry", entry);
            cfg.put("orientation", o.optString("orientation", "unspecified"));
            cfg.put("fullscreen", !minima && o.optBoolean("fullscreen", false));
            cfg.put("statusBarColor", minima ? "#000000" : o.optString("statusBarColor", "#000000"));
            cfg.put("backgroundColor", minima ? "#ffffff" : o.optString("backgroundColor", "#ffffff"));
            cfg.put("splashColor", minima ? "#ffffff" : o.optString("splashColor", "#ffffff"));
            cfg.put("splashDuration", minima ? 1000 : o.optInt("splashDuration", 1500));
            cfg.put("keepAwake", !minima && o.optBoolean("keepAwake", false));
            cfg.put("askOnStart", minima || o.optBoolean("askOnStart", true));
            cfg.put("allowCleartext", !minima && o.optBoolean("allowCleartext", false));
            JSONArray rt = new JSONArray();
            for (String p : in.permissions) if (Perms.isRuntime(p)) rt.put(p);
            cfg.put("runtime", rt);
            in.configJson = cfg.toString();
            r.apk = new File(outDir, slug(name) + ".apk");
            ApkBuilder.build(templateApk, in, key, cert, r.apk);
        }
        if (wantExe) {
            r.exeZip = new File(outDir, slug(name) + "-windows.zip");
            ExeProject.write(minima ? new JSONObject().put("name", name) : o, entry, pkgId, files, iconRaw, r.exeZip);
        }
        return r;
    }
}
