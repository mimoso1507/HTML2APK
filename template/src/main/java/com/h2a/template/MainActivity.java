package com.h2a.template;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Plantilla: muestra el HTML empaquetado en assets/www dentro de un WebView. La configuración está en assets/h2a.json. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PERMS = 100, REQ_BG = 101, REQ_WEB = 102, REQ_GEO = 103, REQ_FILE = 104;

    private WebView web;
    private FrameLayout root;
    private JSONObject cfg = new JSONObject();
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebRequest;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private String startHost; // host de la direccion web (modo URL), o null
    private String localEntry;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            InputStream in = getAssets().open("h2a.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            cfg = new JSONObject(bo.toString("UTF-8"));
        } catch (Exception ignored) { }

        String orient = cfg.optString("orientation", "unspecified");
        if (orient.equals("portrait")) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else if (orient.equals("landscape")) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        else if (orient.equals("sensor")) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);

        Window w = getWindow();
        if (cfg.optBoolean("fullscreen", false)) {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (cfg.optBoolean("keepAwake", false)) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { w.setStatusBarColor(Color.parseColor(cfg.optString("statusBarColor", "#000000"))); } catch (Exception ignored) { }

        int bg = parseColor(cfg.optString("backgroundColor", "#ffffff"), Color.WHITE);
        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        setContentView(root);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupWebView();

        String entry = cfg.optString("entry", "index.html");
        localEntry = "https://" + HOST + "/assets/www/" + Uri.encode(entry, "/");
        String url = cfg.optString("url", "");
        if (!url.isEmpty()) {
            startHost = Uri.parse(url).getHost();
            web.loadUrl(url);
        } else {
            web.loadUrl(localEntry);
        }

        showSplash();
        if (cfg.optBoolean("askOnStart", true)) requestStartupPermissions();
    }

    private static int parseColor(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setGeolocationEnabled(true);
        s.setMixedContentMode(cfg.optBoolean("allowCleartext", false)
                ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                if (startHost != null && startHost.equalsIgnoreCase(u.getHost())) return false; // misma direccion web
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest r, android.webkit.WebResourceError e) {
                // Modo URL: si la direccion no responde, muestra la pagina local "sin conexion" con Reintentar.
                if (startHost != null && r.isForMainFrame() && !HOST.equals(r.getUrl().getHost())) view.loadUrl(localEntry);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                // Android 5/6 (API < 23): misma logica
                if (Build.VERSION.SDK_INT < 23 && startHost != null && failingUrl != null && !failingUrl.contains(HOST)) view.loadUrl(localEntry);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { handleWebPermission(request); }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingGeo = cb; pendingGeoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    private void handleWebPermission(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23)
            for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingWebRequest = request;
            requestPermissions(missing.toArray(new String[0]), REQ_WEB);
        }
    }

    private void showSplash() {
        int ms = cfg.optInt("splashDuration", 0);
        if (ms <= 0) return;
        final FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(parseColor(cfg.optString("splashColor", "#ffffff"), Color.WHITE));
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_launcher);
        int size = (int) (128 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        splash.addView(iv, lp);
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { root.removeView(splash); }
        }, ms);
    }

    private void requestStartupPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        JSONArray arr = cfg.optJSONArray("runtime");
        if (arr == null) return;
        List<String> ask = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String p = arr.optString(i);
            if (p.equals("android.permission.ACCESS_BACKGROUND_LOCATION")) continue; // se pide aparte
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_PERMS);
        else requestBackgroundLocation();
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return;
        JSONArray arr = cfg.optJSONArray("runtime");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            if ("android.permission.ACCESS_BACKGROUND_LOCATION".equals(arr.optString(i))
                    && checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"}, REQ_BG);
                return;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (code == REQ_PERMS) requestBackgroundLocation();
        if (code == REQ_WEB && pendingWebRequest != null) {
            if (ok) pendingWebRequest.grant(pendingWebRequest.getResources()); else pendingWebRequest.deny();
            pendingWebRequest = null;
        }
        if (code == REQ_GEO && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, ok, false);
            pendingGeo = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
}
