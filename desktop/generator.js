// Genera proyectos Capacitor (Android/APK) y Electron (Windows/EXE) a partir de archivos HTML.
const fs = require('fs'), path = require('path');

const DEFAULTS = {
  mode: 'minima', name: 'MiApp', packageId: '', version: '1.0.0', versionCode: 1,
  description: '', author: '', targets: ['apk', 'exe'],
  // Android
  orientation: 'unspecified', fullscreen: false, statusBarColor: '#000000', backgroundColor: '#ffffff',
  splashColor: '#ffffff', splashDuration: 1500, permissions: [], allowCleartext: false,
  minSdk: 22, targetSdk: 34, keepAwake: false,
  // Windows
  width: 1000, height: 700, minWidth: 0, minHeight: 0, resizable: true, frame: true,
  alwaysOnTop: false, exeFullscreen: false, maximized: false, hideMenu: true, devTools: false,
  singleInstance: true, installer: 'nsis', askOnStart: true, autoGrantWeb: true, // nsis | portable
};
const PERMS = {
  camera: 'android.permission.CAMERA', microphone: 'android.permission.RECORD_AUDIO',
  location: 'android.permission.ACCESS_FINE_LOCATION', coarseLocation: 'android.permission.ACCESS_COARSE_LOCATION',
  backgroundLocation: 'android.permission.ACCESS_BACKGROUND_LOCATION',
  storage: 'android.permission.READ_EXTERNAL_STORAGE', writeStorage: 'android.permission.WRITE_EXTERNAL_STORAGE',
  manageStorage: 'android.permission.MANAGE_EXTERNAL_STORAGE',
  images: 'android.permission.READ_MEDIA_IMAGES', videos: 'android.permission.READ_MEDIA_VIDEO', audio: 'android.permission.READ_MEDIA_AUDIO',
  notifications: 'android.permission.POST_NOTIFICATIONS', contacts: 'android.permission.READ_CONTACTS', writeContacts: 'android.permission.WRITE_CONTACTS',
  calendar: 'android.permission.READ_CALENDAR', writeCalendar: 'android.permission.WRITE_CALENDAR',
  phone: 'android.permission.READ_PHONE_STATE', callPhone: 'android.permission.CALL_PHONE', callLog: 'android.permission.READ_CALL_LOG',
  sms: 'android.permission.READ_SMS', sendSms: 'android.permission.SEND_SMS', receiveSms: 'android.permission.RECEIVE_SMS',
  bodySensors: 'android.permission.BODY_SENSORS', activityRecognition: 'android.permission.ACTIVITY_RECOGNITION',
  bluetooth: 'android.permission.BLUETOOTH', bluetoothConnect: 'android.permission.BLUETOOTH_CONNECT',
  bluetoothScan: 'android.permission.BLUETOOTH_SCAN', nearbyWifi: 'android.permission.NEARBY_WIFI_DEVICES',
  wifiState: 'android.permission.ACCESS_WIFI_STATE', networkState: 'android.permission.ACCESS_NETWORK_STATE',
  changeWifi: 'android.permission.CHANGE_WIFI_STATE', nfc: 'android.permission.NFC',
  vibrate: 'android.permission.VIBRATE', wakeLock: 'android.permission.WAKE_LOCK',
  bootCompleted: 'android.permission.RECEIVE_BOOT_COMPLETED', foregroundService: 'android.permission.FOREGROUND_SERVICE',
  overlay: 'android.permission.SYSTEM_ALERT_WINDOW', flashlight: 'android.permission.FLASHLIGHT',
  biometric: 'android.permission.USE_BIOMETRIC', installPackages: 'android.permission.REQUEST_INSTALL_PACKAGES',
  accounts: 'android.permission.GET_ACCOUNTS', modifyAudio: 'android.permission.MODIFY_AUDIO_SETTINGS',
  exactAlarm: 'android.permission.SCHEDULE_EXACT_ALARM',
};
// Permisos "peligrosos" que Android exige pedir en tiempo de ejecución (diálogo al usuario)
const RUNTIME = new Set(['CAMERA','RECORD_AUDIO','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','ACCESS_BACKGROUND_LOCATION',
  'READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE','READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_AUDIO','POST_NOTIFICATIONS',
  'READ_CONTACTS','WRITE_CONTACTS','READ_CALENDAR','WRITE_CALENDAR','READ_PHONE_STATE','CALL_PHONE','READ_CALL_LOG','READ_SMS',
  'SEND_SMS','RECEIVE_SMS','BODY_SENSORS','ACTIVITY_RECOGNITION','BLUETOOTH_CONNECT','BLUETOOTH_SCAN','NEARBY_WIFI_DEVICES','GET_ACCOUNTS']);
const slug = s => (s || 'app').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '') || 'app';
const w = (p, c) => { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, c); };
// Normaliza una dirección web: "192.168.40.9:8080" -> "http://192.168.40.9:8080/". Vacío -> ''.
function normUrl(u) {
  u = String(u || '').trim();
  if (!u) return '';
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(u)) u = 'http://' + u;
  let x;
  try { x = new URL(u); } catch (e) { throw new Error('La dirección no es válida: ' + u); }
  if (x.protocol !== 'http:' && x.protocol !== 'https:') throw new Error('La dirección debe empezar con http:// o https://');
  return x.href;
}
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
// Página que se muestra si la dirección no responde (sin red o servidor apagado).
const offlinePage = (url, name) => `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(name)}</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;font:16px system-ui,sans-serif;background:#f5f6f8;color:#1c1e21;text-align:center}
@media(prefers-color-scheme:dark){body{background:#15171a;color:#e8eaed}}div{padding:24px;max-width:420px}a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:8px;background:#2563eb;color:#fff;text-decoration:none}</style></head>
<body><div><h2>No se pudo conectar</h2><p>Revisa que estés en la misma red Wi-Fi y que el servidor esté encendido:</p><p><b>${esc(url)}</b></p><a href="${esc(url)}">Reintentar</a></div></body></html>`;

function generate(input, outRoot) {
  const o = Object.assign({}, DEFAULTS, input.options || {});
  if (o.mode === 'minima') { // MINIMA: solo lo esencial, el resto por defecto
    const keep = { name: o.name, targets: o.targets, mode: 'minima', url: o.url };
    Object.assign(o, DEFAULTS, keep);
  }
  o.packageId = o.packageId || `com.app.${slug(o.name)}`;
  o.url = normUrl(o.url);
  if (o.url && o.url.startsWith('http:')) o.allowCleartext = true; // red local sin https
  let files = input.files || []; // [{path, data(base64)}]
  // Modo dirección web: la app abre o.url; solo se empaqueta la página de "sin conexión".
  if (o.url) files = [{ path: 'index.html', data: Buffer.from(offlinePage(o.url, o.name)).toString('base64') }];
  if (!files.length) throw new Error('Elige un archivo HTML o escribe una dirección web (ej: 192.168.40.9:8080).');
  let entry = files.find(f => /(^|\/)index\.html?$/i.test(f.path)) || files.find(f => /\.html?$/i.test(f.path));
  if (!entry) throw new Error('No hay ningún archivo .html.');
  const base = path.posix.dirname(entry.path.replace(/\\/g, '/'));
  const rel = p => { p = p.replace(/\\/g, '/'); return base === '.' ? p : (p.startsWith(base + '/') ? p.slice(base.length + 1) : p); };
  const entryRel = rel(entry.path);
  const dir = path.join(outRoot, slug(o.name) + '-' + Date.now().toString(36));
  const icon = input.icon ? Buffer.from(input.icon, 'base64') : null;
  const result = { dir, projects: [] };

  const copyWeb = dest => {
    for (const f of files) {
      const r = rel(f.path); if (r.includes('..')) continue;
      w(path.join(dest, r), Buffer.from(f.data, 'base64'));
    }
    if (entryRel !== 'index.html' && !files.some(f => rel(f.path) === 'index.html'))
      w(path.join(dest, 'index.html'), `<meta http-equiv="refresh" content="0;url=${entryRel}">`);
  };

  if (o.targets.includes('apk')) {
    const d = path.join(dir, 'android'); copyWeb(path.join(d, 'www'));
    if (icon) w(path.join(d, 'resources', 'icon.png'), icon);
    w(path.join(d, 'capacitor.config.json'), JSON.stringify({
      appId: o.packageId, appName: o.name, webDir: 'www', backgroundColor: o.backgroundColor,
      server: Object.assign({ androidScheme: 'https', cleartext: !!o.allowCleartext },
        o.url ? { url: o.url, errorPath: 'index.html' } : {}),
      plugins: { SplashScreen: { launchShowDuration: +o.splashDuration, backgroundColor: o.splashColor, launchAutoHide: true },
        StatusBar: { backgroundColor: o.statusBarColor } },
    }, null, 2));
    w(path.join(d, 'package.json'), JSON.stringify({
      name: slug(o.name), version: o.version, private: true, description: o.description,
      scripts: { setup: 'npx cap add android && node configure-android.js && npx cap sync android',
        'build:apk': 'node configure-android.js && npx cap sync android && cd android && ./gradlew assembleDebug' },
      dependencies: { '@capacitor/core': '^6.1.0', '@capacitor/android': '^6.1.0', '@capacitor/splash-screen': '^6.0.0', '@capacitor/status-bar': '^6.0.0' },
      devDependencies: { '@capacitor/cli': '^6.1.0', '@capacitor/assets': '^3.0.5' },
    }, null, 2));
    w(path.join(d, 'configure-android.js'), configureAndroid(o, !!icon));
    w(path.join(d, 'construir.sh'), `#!/bin/sh\nset -e\nnpm install\n[ -d android ] || npm run setup\nnpm run build:apk\necho "APK: android/app/build/outputs/apk/debug/app-debug.apk"\n`);
    w(path.join(d, 'construir.bat'), `@echo off\r\ncall npm install || exit /b 1\r\nif not exist android call npm run setup || exit /b 1\r\ncall npm run build:apk || exit /b 1\r\necho APK: android\\app\\build\\outputs\\apk\\debug\\app-debug.apk\r\npause\r\n`);
    w(path.join(d, '.github/workflows/apk.yml'), `name: Build APK
on: [workflow_dispatch, push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - run: npm install && npm run setup && npm run build:apk
      - uses: actions/upload-artifact@v4
        with: { name: apk, path: android/app/build/outputs/apk/debug/*.apk }
`);
    result.projects.push('android');
  }

  if (o.targets.includes('exe')) {
    const d = path.join(dir, 'windows'); copyWeb(path.join(d, 'app'));
    if (icon) w(path.join(d, 'build', 'icon.png'), icon);
    w(path.join(d, 'config.json'), JSON.stringify({ entry: 'app/' + entryRel, url: o.url || undefined, width: +o.width, height: +o.height,
      minWidth: +o.minWidth || undefined, minHeight: +o.minHeight || undefined, resizable: !!o.resizable, frame: !!o.frame,
      alwaysOnTop: !!o.alwaysOnTop, fullscreen: !!o.exeFullscreen, maximized: !!o.maximized, hideMenu: !!o.hideMenu,
      devTools: !!o.devTools, autoGrantWeb: o.autoGrantWeb !== false, singleInstance: !!o.singleInstance, title: o.name, backgroundColor: o.backgroundColor,
      icon: icon ? 'build/icon.png' : undefined }, null, 2));
    w(path.join(d, 'main.js'), `const { app, BrowserWindow, Menu } = require('electron');
const path = require('path');
const c = require('./config.json');
if (c.singleInstance && !app.requestSingleInstanceLock()) app.quit();
function create() {
  const win = new BrowserWindow({
    width: c.width, height: c.height, minWidth: c.minWidth, minHeight: c.minHeight,
    resizable: c.resizable, frame: c.frame, alwaysOnTop: c.alwaysOnTop, fullscreen: c.fullscreen,
    title: c.title, backgroundColor: c.backgroundColor,
    icon: c.icon ? path.join(__dirname, c.icon) : undefined,
    webPreferences: { devTools: c.devTools, contextIsolation: true },
  });
  if (c.autoGrantWeb) win.webContents.session.setPermissionRequestHandler((wc, perm, cb) => cb(true));
  if (c.hideMenu) Menu.setApplicationMenu(null);
  if (c.maximized) win.maximize();
  if (c.url) {
    // Si la dirección no responde, muestra la página local con el botón "Reintentar".
    win.webContents.on('did-fail-load', (e, code, desc, failedUrl, isMain) => {
      if (isMain && code !== -3) win.loadFile(path.join(__dirname, c.entry));
    });
    win.loadURL(c.url);
  } else win.loadFile(path.join(__dirname, c.entry));
}
app.whenReady().then(create);
app.on('window-all-closed', () => app.quit());
`);
    w(path.join(d, 'package.json'), JSON.stringify({
      name: slug(o.name), productName: o.name, version: o.version, description: o.description || o.name,
      author: o.author || 'autor', main: 'main.js',
      scripts: { start: 'electron .', 'build:exe': 'electron-builder --win ' + (o.installer === 'portable' ? 'portable' : 'nsis') + ' --x64' },
      devDependencies: { electron: '^31.0.0', 'electron-builder': '^24.13.3' },
      build: { appId: o.packageId, productName: o.name, files: ['main.js', 'config.json', 'app/**', 'build/**'],
        directories: { output: 'dist' }, win: { icon: icon ? 'build/icon.png' : undefined } },
    }, null, 2));
    w(path.join(d, 'construir.bat'), `@echo off\r\ncall npm install || exit /b 1\r\ncall npm run build:exe || exit /b 1\r\necho EXE en la carpeta dist\r\npause\r\n`);
    w(path.join(d, 'construir.sh'), `#!/bin/sh\nset -e\nnpm install\nnpm run build:exe\necho "EXE en dist/ (en Linux/Mac necesita wine)"\n`);
    w(path.join(d, '.github/workflows/exe.yml'), `name: Build EXE
on: [workflow_dispatch, push]
jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20 }
      - run: npm install && npm run build:exe
      - uses: actions/upload-artifact@v4
        with: { name: exe, path: dist/*.exe }
`);
    result.projects.push('windows');
  }
  w(path.join(dir, 'LEEME.txt'), `Proyecto generado: ${o.name} (${o.packageId}) v${o.version}\n\n` +
(o.url ? `La app abre la dirección ${o.url}\n  El celular o la PC deben estar en la misma red y el servidor encendido.\n\n` : '') +
`ANDROID (APK): entra a la carpeta android y ejecuta construir.bat (Windows) o construir.sh.\n  Requiere Node.js 18+, Java 17 y Android SDK (ANDROID_HOME).\n  Sin SDK: sube la carpeta a GitHub; el workflow .github/workflows/apk.yml compila el APK en la nube.\n\n` +
`WINDOWS (EXE): entra a la carpeta windows y ejecuta construir.bat. Requiere Node.js 18+.\n  Probar sin compilar: npm install && npm start.\n  Resultado en dist/.\n`);
  result.options = o;
  return result;
}

function configureAndroid(o, hasIcon) {
  return `// Aplica las opciones a los archivos nativos de Android (se ejecuta tras "cap add android").
const fs = require('fs'), path = require('path');
const o = ${JSON.stringify({ orientation: o.orientation, fullscreen: o.fullscreen, statusBarColor: o.statusBarColor,
    permissions: o.permissions.map(p => PERMS[p]).filter(Boolean), runtime: o.permissions.map(p => PERMS[p]).filter(p => p && RUNTIME.has(p.split('.').pop())), askOnStart: o.askOnStart !== false, allowCleartext: o.allowCleartext, version: o.version,
    versionCode: +o.versionCode, minSdk: +o.minSdk, targetSdk: +o.targetSdk, keepAwake: o.keepAwake, hasIcon }, null, 2)};
const A = 'android/app/src/main';
const mf = path.join(A, 'AndroidManifest.xml');
let m = fs.readFileSync(mf, 'utf8');
const orient = { portrait: 'portrait', landscape: 'landscape', sensor: 'sensor', unspecified: null }[o.orientation];
m = m.replace(/\\s+android:screenOrientation="[^"]*"/, '');
if (orient) m = m.replace('<activity', '<activity android:screenOrientation="' + orient + '"');
if (o.allowCleartext && !m.includes('usesCleartextTraffic')) m = m.replace('<application', '<application android:usesCleartextTraffic="true"');
const perms = new Set(o.permissions); perms.add('android.permission.INTERNET'); if (o.keepAwake) perms.add('android.permission.WAKE_LOCK');
for (const p of perms) if (!m.includes(p)) m = m.replace('</manifest>', '    <uses-permission android:name="' + p + '" />\\n</manifest>');
fs.writeFileSync(mf, m);
// Pantalla completa y color de barra de estado
const sf = path.join(A, 'res/values/styles.xml');
if (fs.existsSync(sf)) {
  let s = fs.readFileSync(sf, 'utf8');
  s = s.replace(/<item name="android:statusBarColor">[^<]*<\\/item>\\s*/g, '');
  const items = '<item name="android:statusBarColor">' + o.statusBarColor + '</item>' +
    (o.fullscreen ? '<item name="android:windowFullscreen">true</item>' : '');
  s = s.replace(/(<style name="AppTheme.NoActionBar"[^>]*>)/, '$1' + items);
  fs.writeFileSync(sf, s);
}
// Versión y SDK
const gf = 'android/app/build.gradle';
let g = fs.readFileSync(gf, 'utf8');
g = g.replace(/versionCode \\d+/, 'versionCode ' + o.versionCode).replace(/versionName "[^"]*"/, 'versionName "' + o.version + '"');
fs.writeFileSync(gf, g);
const vf = 'android/variables.gradle';
if (fs.existsSync(vf)) {
  let v = fs.readFileSync(vf, 'utf8');
  v = v.replace(/minSdkVersion = \\d+/, 'minSdkVersion = ' + o.minSdk).replace(/targetSdkVersion = \\d+/, 'targetSdkVersion = ' + o.targetSdk);
  fs.writeFileSync(vf, v);
}
// Pedir permisos al primer arranque (tras instalar)
if (o.askOnStart && o.runtime.length) {
  const jf = require('child_process').execSync('find android/app/src/main/java -name MainActivity.java').toString().trim();
  let j = fs.readFileSync(jf, 'utf8');
  if (!j.includes('H2A_PERMS')) {
    j = j.replace(/import com.getcapacitor.BridgeActivity;/, 'import com.getcapacitor.BridgeActivity;\\nimport android.os.Bundle;\\nimport android.os.Build;');
    const list = o.runtime.map(p => '"' + p + '"').join(', ');
    j = j.replace(/public class MainActivity extends BridgeActivity \\{\\s*\\}/, 'public class MainActivity extends BridgeActivity {\\n  // H2A_PERMS\\n  @Override public void onCreate(Bundle b) {\\n    super.onCreate(b);\\n    if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{' + list + '}, 77);\\n  }\\n}');
    fs.writeFileSync(jf, j);
  }
}
// Icono
if (o.hasIcon) { try { require('child_process').execSync('npx capacitor-assets generate --android --iconBackgroundColor "#ffffff"', { stdio: 'inherit' }); } catch (e) { console.warn('No se pudo generar el icono:', e.message); } }
console.log('Android configurado.');
`;
}
module.exports = { generate, normUrl, DEFAULTS, PERMS };
