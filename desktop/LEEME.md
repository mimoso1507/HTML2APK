# Convertidor HTML → APK / EXE

1. Instala Node.js 18+.
2. `node server.js` y abre http://localhost:3000
3. Elige tu HTML (o carpeta) **o escribe una dirección web / red local** (ej: `192.168.40.9:8080`), modo **MINIMA** (solo nombre/icono) o **COMPLETA** (todas las opciones) y pulsa *Generar*.
4. En `salida/<app>/` tendrás `android/` (Capacitor → APK) y `windows/` (Electron → EXE) con `construir.bat`/`construir.sh`.
   Sin Android SDK, sube la carpeta a GitHub: los workflows compilan APK y EXE en la nube.

## Abrir una dirección de red local (ej: `192.168.40.9:8080`)
En lugar de archivos, escribe la dirección en el campo *dirección web / red local*. La app generada (APK y EXE) abre esa dirección al iniciar.
- Si no pones `http://` se agrega solo. Con `http://` (sin https) se permite el tráfico sin cifrar automáticamente (Android lo bloquea por defecto).
- El celular o la PC deben estar en la **misma red** (Wi-Fi) que el servidor, y el servidor debe estar encendido y escuchando en esa IP (no solo en `localhost`).
- Si no hay conexión, la app muestra una pantalla "No se pudo conectar" con botón *Reintentar*.
- Conviene que el servidor tenga IP fija en el router; si cambia la IP, hay que generar la app de nuevo.
- Cámara, micrófono y ubicación desde la web solo funcionan con `https://` (los navegadores no los permiten en `http://` de red local).
