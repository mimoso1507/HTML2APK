package com.h2a.converter;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.zip.ZipFile;

public class ConverterTest {
    @Test
    public void buildsSignedApkAndExeProject() throws Exception {
        File template = new File("src/main/assets/template.apk");
        Assume.assumeTrue("template.apk no existe (compilar :template primero)", template.exists());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream("src/main/assets/h2a.p12")) { ks.load(in, "h2apass".toCharArray()); }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("index.html", "<h1>Hola</h1>".getBytes(StandardCharsets.UTF_8));
        files.put("css/a.css", "body{}".getBytes(StandardCharsets.UTF_8));
        JSONObject req = new JSONObject("{\"options\":{\"mode\":\"completa\",\"name\":\"Mi Prueba\",\"packageId\":\"com.test.prueba\","
                + "\"version\":\"2.5\",\"versionCode\":7,\"targets\":[\"apk\",\"exe\"],\"permissions\":[\"camera\",\"location\",\"vibrate\"],"
                + "\"orientation\":\"landscape\",\"minSdk\":24,\"targetSdk\":34}}");
        File out = new File("build/test-out");
        Converter.Result r = Converter.convert(req, files, template,
                (java.security.PrivateKey) ks.getKey("h2a", "h2apass".toCharArray()),
                (java.security.cert.X509Certificate) ks.getCertificate("h2a"), null, null, out);
        assertTrue(r.apk.length() > 1000);
        try (ZipFile z = new ZipFile(r.apk)) {
            assertNotNull(z.getEntry("assets/www/index.html"));
            assertNotNull(z.getEntry("assets/www/css/a.css"));
            assertNotNull(z.getEntry("assets/h2a.json"));
            assertNotNull(z.getEntry("resources.arsc"));
        }
        // Con icono propio: TODAS las imagenes PNG de res/ deben ser el icono (si no, queda el generico)
        byte[] icon = "PNG-FALSO-PARA-PRUEBA".getBytes(StandardCharsets.UTF_8);
        Converter.Result r2 = Converter.convert(req, files, template,
                (java.security.PrivateKey) ks.getKey("h2a", "h2apass".toCharArray()),
                (java.security.cert.X509Certificate) ks.getCertificate("h2a"), icon, icon, new File("build/test-out-icon"));
        int pngs = 0;
        try (ZipFile z = new ZipFile(r2.apk)) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements();) {
                java.util.zip.ZipEntry ze = en.nextElement();
                if (ze.getName().startsWith("res/") && ze.getName().endsWith(".png")) {
                    pngs++;
                    assertArrayEquals(ze.getName(), icon, z.getInputStream(ze).readAllBytes());
                }
            }
        }
        assertTrue("la plantilla no tiene PNG de icono", pngs >= 1);
        try (ZipFile z = new ZipFile(r.exeZip)) {
            assertNotNull(z.getEntry("app/index.html"));
            assertNotNull(z.getEntry("main.js"));
        }
    }
}
