# HTML2APK-EXE

Convertidor de HTML a APK (Android) y EXE (Windows), con modos **MINIMA** y **COMPLETA**.

- `app/` – APK convertidora (se instala en el celular). Arma la APK del usuario dentro del teléfono: toma la plantilla, mete el HTML, parchea el manifiesto (permisos, id, versión), firma y permite instalar.
- `template/` – APK plantilla (WebView) que se incrusta en la convertidora.
- `desktop/` – versión para PC (`node server.js`).
- En vez de archivos HTML se puede escribir una **dirección web o de red local** (ej: `192.168.40.9:8080`): la APK/EXE generada abre esa dirección y permite `http://` sin cifrar. Detalles en `desktop/LEEME.md`.
- Para Windows, la APK genera un proyecto ZIP que GitHub Actions compila a `.exe`.

La APK convertidora se compila con GitHub Actions y se publica en *Releases* (`latest`).
