package com.h2a.converter;

import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Genera un proyecto Electron (ZIP) que GitHub Actions o una PC convierten en .exe. */
public final class ExeProject {
    public static void write(JSONObject o, String entry, String url, String packageId, Map<String, byte[]> www, byte[] icon, File outZip) throws Exception {
        Map<String, byte[]> f = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> w : www.entrySet()) f.put("app/" + w.getKey(), w.getValue());
        String name = o.optString("name", "MiApp");
        boolean portable = "portable".equals(o.optString("installer", "nsis"));
        if (icon != null) f.put("build/icon.png", icon);

        JSONObject c = new JSONObject();
        c.put("entry", "app/" + entry);
        if (url != null && !url.isEmpty()) c.put("url", url);
        c.put("width", o.optInt("width", 1000)); c.put("height", o.optInt("height", 700));
        if (o.optInt("minWidth", 0) > 0) c.put("minWidth", o.optInt("minWidth"));
        if (o.optInt("minHeight", 0) > 0) c.put("minHeight", o.optInt("minHeight"));
        c.put("resizable", o.optBoolean("resizable", true)); c.put("frame", o.optBoolean("frame", true));
        c.put("alwaysOnTop", o.optBoolean("alwaysOnTop", false)); c.put("fullscreen", o.optBoolean("exeFullscreen", false));
        c.put("maximized", o.optBoolean("maximized", false)); c.put("hideMenu", o.optBoolean("hideMenu", true));
        c.put("devTools", o.optBoolean("devTools", false)); c.put("singleInstance", o.optBoolean("singleInstance", true));
        c.put("autoGrantWeb", o.optBoolean("autoGrantWeb", true));
        c.put("title", name); c.put("backgroundColor", o.optString("backgroundColor", "#ffffff"));
        if (icon != null) c.put("icon", "build/icon.png");
        f.put("config.json", c.toString(2).getBytes("UTF-8"));

        f.put("main.js", MAIN_JS.getBytes("UTF-8"));

        String slug = Converter.slug(name);
        JSONObject pkg = new JSONObject();
        pkg.put("name", slug); pkg.put("productName", name); pkg.put("version", o.optString("version", "1.0.0"));
        pkg.put("description", o.optString("description", name).isEmpty() ? name : o.optString("description", name));
        pkg.put("author", o.optString("author", "").isEmpty() ? "autor" : o.optString("author"));
        pkg.put("main", "main.js");
        pkg.put("scripts", new JSONObject().put("start", "electron .")
                .put("build:exe", "electron-builder --win " + (portable ? "portable" : "nsis") + " --x64"));
        pkg.put("devDependencies", new JSONObject().put("electron", "^31.0.0").put("electron-builder", "^24.13.3"));
        JSONObject win = new JSONObject();
        if (icon != null) win.put("icon", "build/icon.png");
        pkg.put("build", new JSONObject().put("appId", Converter.packageOf(o, name)).put("productName", name)
                .put("files", new org.json.JSONArray().put("main.js").put("config.json").put("app/**").put("build/**"))
                .put("directories", new JSONObject().put("output", "dist")).put("win", win));
        f.put("package.json", pkg.toString(2).getBytes("UTF-8"));

        f.put("construir.bat", "@echo off\r\ncall npm install || exit /b 1\r\ncall npm run build:exe || exit /b 1\r\necho EXE en la carpeta dist\r\npause\r\n".getBytes("UTF-8"));
        f.put(".github/workflows/exe.yml", WORKFLOW.getBytes("UTF-8"));
        f.put("LEEME.txt", ("Proyecto Windows de " + name + "\n\n"
                + (url != null && !url.isEmpty() ? "La app abre la direccion " + url + " (la PC debe estar en la misma red).\n\n" : "")
                + "OPCION 1 (GitHub, sin instalar nada): sube TODO el contenido de este ZIP a un repositorio de GitHub.\n"
                + "  En la pestana Actions ejecuta 'Build EXE'; el .exe aparece como artefacto descargable.\n\n"
                + "OPCION 2 (PC con Windows y Node.js 18+): descomprime y ejecuta construir.bat. El .exe queda en la carpeta dist.\n"
                + "  Para probar sin compilar: npm install && npm start\n").getBytes("UTF-8"));

        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outZip)))) {
            for (Map.Entry<String, byte[]> e : f.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    private static final String WORKFLOW = "name: Build EXE\n"
            + "on: [workflow_dispatch, push]\n"
            + "jobs:\n  build:\n    runs-on: windows-latest\n    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-node@v4\n        with: { node-version: 20 }\n"
            + "      - run: npm install\n      - run: npm run build:exe\n"
            + "      - uses: actions/upload-artifact@v4\n        with: { name: exe, path: 'dist/*.exe' }\n";

    private static final String MAIN_JS = "const { app, BrowserWindow, Menu } = require('electron');\n"
            + "const path = require('path');\nconst c = require('./config.json');\n"
            + "if (c.singleInstance && !app.requestSingleInstanceLock()) app.quit();\n"
            + "function create() {\n  const win = new BrowserWindow({\n"
            + "    width: c.width, height: c.height, minWidth: c.minWidth, minHeight: c.minHeight,\n"
            + "    resizable: c.resizable, frame: c.frame, alwaysOnTop: c.alwaysOnTop, fullscreen: c.fullscreen,\n"
            + "    title: c.title, backgroundColor: c.backgroundColor,\n"
            + "    icon: c.icon ? path.join(__dirname, c.icon) : undefined,\n"
            + "    webPreferences: { devTools: c.devTools, contextIsolation: true },\n  });\n"
            + "  if (c.autoGrantWeb) win.webContents.session.setPermissionRequestHandler((wc, perm, cb) => cb(true));\n"
            + "  if (c.hideMenu) Menu.setApplicationMenu(null);\n"
            + "  if (c.maximized) win.maximize();\n"
            + "  if (c.url) {\n"
            + "    // Si la direccion no responde, muestra la pagina local con el boton Reintentar.\n"
            + "    win.webContents.on('did-fail-load', (e, code, desc, failedUrl, isMain) => {\n"
            + "      if (isMain && code !== -3) win.loadFile(path.join(__dirname, c.entry));\n    });\n"
            + "    win.loadURL(c.url);\n"
            + "  } else win.loadFile(path.join(__dirname, c.entry));\n}\n"
            + "app.whenReady().then(create);\napp.on('window-all-closed', () => app.quit());\n";
}
