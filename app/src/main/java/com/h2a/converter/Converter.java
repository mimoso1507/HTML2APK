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

    /** "192.168.40.9:8080" -> "http://192.168.40.9:8080/". Vacio -> "". */
    public static String normUrl(String u) throws IOException {
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "http://" + u;
        java.net.URI x;
        try { x = new java.net.URI(u); } catch (Exception e) { throw new IOException("La direccion no es valida: " + u); }
        String sc = x.getScheme() == null ? "" : x.getScheme().toLowerCase(Locale.ROOT);
        if (!sc.equals("http") && !sc.equals("https")) throw new IOException("La direccion debe empezar con http:// o https://");
        if (x.getHost() == null || x.getHost().isEmpty()) throw new IOException("La direccion no es valida: " + u);
        if (x.getRawPath() == null || x.getRawPath().isEmpty()) u = u.replaceFirst("^([^/]*//[^/?#]*)", "$1/");
        return u;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Pagina local que se muestra si la direccion no responde. */
    public static String offlinePage(String url, String name) {
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(name) + "</title>"
                + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font:16px system-ui,sans-serif;background:#f5f6f8;color:#1c1e21;text-align:center}"
                + "@media(prefers-color-scheme:dark){body{background:#15171a;color:#e8eaed}}div{padding:24px;max-width:420px}"
                + "a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:8px;background:#2563eb;color:#fff;text-decoration:none}</style></head>"
                + "<body><div><h2>No se pudo conectar</h2><p>Revisa que estes en la misma red Wi-Fi y que el servidor este encendido:</p><p><b>" + esc(url) + "</b></p>"
                + "<a href=\"" + esc(url) + "\">Reintentar</a></div></body></html>";
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
        String url = normUrl(o.optString("url", ""));
        if (!url.isEmpty()) { // Modo direccion web: solo se empaqueta la pagina "sin conexion".
            files = new LinkedHashMap<>();
            files.put("index.html", offlinePage(url, name).getBytes("UTF-8"));
        }
        if (files == null || files.isEmpty()) throw new IOException("Elige tu archivo HTML o carpeta, o escribe una direccion web (ej: 192.168.40.9:8080).");

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
            cfg.put("allowCleartext", url.startsWith("http:") || (!minima && o.optBoolean("allowCleartext", false)));
            if (!url.isEmpty()) cfg.put("url", url);
            JSONArray rt = new JSONArray();
            for (String p : in.permissions) if (Perms.isRuntime(p)) rt.put(p);
            cfg.put("runtime", rt);
            in.configJson = cfg.toString();
            r.apk = new File(outDir, slug(name) + "-v" + in.version + ".apk");
            ApkBuilder.build(templateApk, in, key, cert, r.apk);
        }
        if (wantExe) {
            r.exeZip = new File(outDir, slug(name) + "-v" + (minima ? "1.0.0" : o.optString("version", "1.0.0")) + "-windows.zip");
            ExeProject.write(minima ? new JSONObject().put("name", name) : o, entry, url, pkgId, files, iconRaw, r.exeZip);
        }
        return r;
    }
}
