package com.h2a.converter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1, REQ_DIR = 2, REQ_ICON = 3;
    private WebView web;
    private Map<String, byte[]> files = new LinkedHashMap<>();
    private byte[] iconBytes;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "H2A");
        web.loadUrl("file:///android_asset/ui/index.html");
    }

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void fail(String msg) {
        js("onResult(" + JSONObject.quote("{\"ok\":false,\"error\":" + JSONObject.quote(msg) + "}") + ")");
    }

    public class Bridge {
        @JavascriptInterface public void pickFiles() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_FILES);
        }
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void aiIcon(final String prompt) {
            new Thread(() -> {
                try {
                    String full = "app icon, " + prompt + ", flat vector illustration, centered, simple clean background, no text";
                    String url = "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(full, "UTF-8").replace("+", "%20")
                            + "?width=1024&height=1024&nologo=true&seed=" + (int) (Math.random() * 100000);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(20000); c.setReadTimeout(120000);
                    if (c.getResponseCode() != 200) throw new IOException("El servicio de IA respondio " + c.getResponseCode());
                    byte[] raw = readAll(c.getInputStream());
                    Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (bm == null) throw new IOException("Imagen no valida");
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.PNG, 100, png);
                    js("onIconImage('" + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP) + "')");
                } catch (Throwable t) {
                    js("onAiError(" + JSONObject.quote(String.valueOf(t.getMessage())) + ")");
                }
            }).start();
        }
        @JavascriptInterface public void pickFolder() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR); }
        @JavascriptInterface public void pickIcon() {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_ICON);
        }
        @JavascriptInterface public void setIcon(String b64) { iconBytes = Base64.decode(b64, Base64.DEFAULT); }

        @JavascriptInterface public void generate(final String json) {
            new Thread(() -> {
                try { doGenerate(json); } catch (Throwable t) { fail(String.valueOf(t.getMessage() != null ? t.getMessage() : t)); }
            }).start();
        }

        @JavascriptInterface public void install(String path) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    return;
                }
                Uri u = FileProvider.getUriForFile(MainActivity.this, "com.h2a.converter.files", new File(path));
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }

        @JavascriptInterface public void share(String path) {
            runOnUiThread(() -> {
                File f = new File(path);
                Uri u = FileProvider.getUriForFile(MainActivity.this, "com.h2a.converter.files", f);
                String type = f.getName().endsWith(".apk") ? "application/vnd.android.package-archive" : "application/zip";
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir"));
            });
        }
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        new Thread(() -> {
            try {
                if (req == REQ_FILES) {
                    files.clear();
                    List<Uri> uris = new ArrayList<>();
                    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                    else if (data.getData() != null) uris.add(data.getData());
                    for (Uri u : uris) files.put(displayName(u), readAll(getContentResolver().openInputStream(u)));
                    reportFiles();
                } else if (req == REQ_DIR) {
                    files.clear();
                    DocumentFile root = DocumentFile.fromTreeUri(this, data.getData());
                    walk(root, "");
                    reportFiles();
                } else if (req == REQ_ICON) {
                    byte[] raw = readAll(getContentResolver().openInputStream(data.getData()));
                    BitmapFactory.Options bo = new BitmapFactory.Options();
                    bo.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(raw, 0, raw.length, bo);
                    int sample = 1;
                    while (Math.max(bo.outWidth, bo.outHeight) / sample > 2048) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
                    if (bm == null) { js("onIconImage('')"); return; }
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.PNG, 100, png);
                    js("onIconImage('" + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP) + "')");
                }
            } catch (Throwable t) { fail("No se pudo leer: " + t.getMessage()); }
        }).start();
    }

    private void walk(DocumentFile dir, String prefix) throws IOException {
        for (DocumentFile f : dir.listFiles()) {
            String n = f.getName() == null ? "x" : f.getName();
            if (f.isDirectory()) walk(f, prefix + n + "/");
            else files.put(prefix + n, readAll(getContentResolver().openInputStream(f.getUri())));
        }
    }

    private void reportFiles() throws Exception {
        String entry = "";
        for (String p : files.keySet()) if (p.toLowerCase(Locale.ROOT).matches(".*\\.html?$")) { entry = p; if (p.equalsIgnoreCase("index.html")) break; }
        JSONObject o = new JSONObject().put("count", files.size()).put("entry", entry);
        js("onFiles(" + o + ")");
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        }
        return "archivo";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void doGenerate(String json) throws Exception {
        JSONObject req = new JSONObject(json);
        File template = new File(getCacheDir(), "template.apk");
        try (InputStream in = getAssets().open("template.apk"); OutputStream out = new FileOutputStream(template)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getAssets().open("h2a.p12")) { ks.load(in, "h2apass".toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey("h2a", "h2apass".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("h2a");

        byte[] iconApk = null;
        if (iconBytes != null) {
            Bitmap bm = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.length);
            if (bm != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                Bitmap.createScaledBitmap(bm, 512, 512, true).compress(Bitmap.CompressFormat.PNG, 100, bo);
                iconApk = bo.toByteArray();
            }
        }
        String name = req.getJSONObject("options").optString("name", "MiApp");
        File outDir = new File(getExternalFilesDir(null), "salida/" + Converter.slug(name));
        Converter.Result r = Converter.convert(req, new LinkedHashMap<>(files), template, key, cert, iconApk, iconBytes, outDir);

        JSONObject res = new JSONObject().put("ok", true);
        if (r.apk != null) { res.put("apk", r.apk.getAbsolutePath()); res.put("apkPublic", copyToDownloads(r.apk, "application/vnd.android.package-archive")); }
        if (r.exeZip != null) { res.put("zip", r.exeZip.getAbsolutePath()); res.put("zipPublic", copyToDownloads(r.exeZip, "application/zip")); }
        js("onResult(" + JSONObject.quote(res.toString()) + ")");
    }

    /** Copia el archivo a Descargas/HTML2APK y devuelve el texto de ubicación. */
    private String copyToDownloads(File f, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HTML2APK");
                try { // evitar que quede una copia vieja con el mismo nombre (y "(1)" en la nueva)
                    getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                            new String[]{f.getName(), Environment.DIRECTORY_DOWNLOADS + "/HTML2APK%"});
                } catch (Exception ignored) { }
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "";
                try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(u)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return "Descargas/HTML2APK/" + f.getName();
            }
        } catch (Exception ignored) { }
        return "";
    }
}
