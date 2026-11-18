package com.dvabrao.odometro;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

/**
 * Odômetro Rodovia Pro — WebView com GPS nativo (foreground service para background).
 *
 * Arquitetura GPS:
 *  • Em foreground: a WebView usa a Geolocation API nativa do WebView (padrão W3C).
 *  • Em background (tela desligada): GpsService mantém LocationManager ativo e
 *    envia coordenadas para a WebView via evaluateJavascript().
 *  • Pontes JS: window.NativeGPS.start() / stop() / isNative() / onPosition(...)
 */
public class MainActivity extends Activity {

    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/www/index.html";

    private static final int REQ_LOCATION = 1;
    private static final int REQ_FILE_CHOOSER = 10;

    private WebView web;
    private GpsService gpsService;
    private boolean serviceBound = false;
    private ValueCallback<Uri[]> fileChooserCallback;

    // ── Conexão com o GpsService ──────────────────────────────────────
    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            gpsService = ((GpsService.LocalBinder) binder).getService();
            serviceBound = true;
            // Injeta callback: quando o serviço receber posição, chama JS
            gpsService.setPositionCallback((lat, lon, acc, speed, bearing) -> {
                if (web != null) {
                    final String js = String.format(
                            "window._nativeGpsPosition && window._nativeGpsPosition(%f,%f,%f,%f,%f);",
                            lat, lon, acc, speed, bearing);
                    web.post(() -> web.evaluateJavascript(js, null));
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            gpsService = null;
        }
    };

    // ── Ponte para salvar fotos na galeria ───────────────────────────────
    private class NativeSaveBridge {

        /**
         * Recebe imagem JPEG em base64 e salva na galeria, pasta "Odometro Rodovia".
         * Chamada pelo JS: window.NativeSave.saveToGallery(base64, fileName)
         */
        @JavascriptInterface
        public void saveToGallery(final String base64, final String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    final String folderName = "Odometro Rodovia";

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+: usa MediaStore sem permissão WRITE_EXTERNAL_STORAGE
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/" + folderName);
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);

                        Uri uri = getContentResolver().insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                                if (os != null) os.write(data);
                            }
                            values.clear();
                            values.put(MediaStore.Images.Media.IS_PENDING, 0);
                            getContentResolver().update(uri, values, null, null);
                            android.util.Log.d("NativeSave", "Foto salva: " + fileName);
                        }
                    } else {
                        // Android 9 e abaixo
                        java.io.File dir = new java.io.File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                                folderName);
                        dir.mkdirs();
                        java.io.File outFile = new java.io.File(dir, fileName);
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                            fos.write(data);
                        }
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                Uri.fromFile(outFile)));
                    }
                } catch (Exception e) {
                    android.util.Log.e("NativeSave", "Erro ao salvar foto: " + e.getMessage(), e);
                }
            });
        }
    }

    // ── Ponte de compartilhamento nativo ──────────────────────────────
    private class NativeShareBridge {

        /**
         * Recebe arquivo em base64 do JS e abre o seletor de compartilhamento nativo do Android.
         * Chamada pelo JS: window.NativeShare.shareFile(base64, mimeType, fileName)
         */
        @JavascriptInterface
        public void shareFile(final String base64, final String mimeType, final String fileName) {
            runOnUiThread(() -> {
                try {
                    // Decodifica base64 → bytes
                    byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);

                    // Grava em cache/share/<fileName>
                    java.io.File dir = new java.io.File(getCacheDir(), "share");
                    dir.mkdirs();
                    java.io.File outFile = new java.io.File(dir, fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                    fos.write(data);
                    fos.close();

                    // Obtém URI via FileProvider (necessário para Android 7+)
                    Uri uri = FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            outFile);

                    // Cria Intent de compartilhamento
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(mimeType);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.putExtra(Intent.EXTRA_SUBJECT, fileName);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    startActivity(Intent.createChooser(share, "Compartilhar " + fileName));
                } catch (Exception e) {
                    android.util.Log.e("NativeShare", "Erro ao compartilhar: " + e.getMessage(), e);
                }
            });
        }
    }

    // ── Ponte JavaScript ──────────────────────────────────────────────
    private class GpsBridge {

        /** Inicia o foreground service de GPS */
        @JavascriptInterface
        public void start() {
            runOnUiThread(() -> startAndBindGpsService());
        }

        /** Para o foreground service de GPS */
        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                if (serviceBound && gpsService != null) {
                    gpsService.stopTracking();
                }
            });
        }

        /** Retorna "true" para o JS saber que está rodando no APK nativo */
        @JavascriptInterface
        public String isNative() {
            return "true";
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Manter tela ligada enquanto o app estiver aberto
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFFFFFF); // branco — evita flash azul no relatório PDF
        setContentView(web);

        // Asset loader: serve assets/www/* como https://appassets.androidplatform.net/assets/www/
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
        });

        // WebChromeClient: necessário para <input type="file"> funcionar no WebView
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                // Cancela callback anterior se houver
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                // Cria intent para câmera
                Intent camIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                // Cria intent de seleção geral (galeria/câmera)
                Intent chooser = fileChooserParams.createIntent();
                Intent finalIntent = Intent.createChooser(chooser, "Selecionar imagem");
                // Adiciona câmera como opção extra
                finalIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camIntent});

                try {
                    startActivityForResult(finalIntent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    android.util.Log.e("FileChooser", "Erro: " + e.getMessage(), e);
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setNegativeButton("Cancelar", (d, w) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        // Geolocation API do WebView (funciona em foreground)
        s.setGeolocationEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " OdometroApp/1.0");

        // Injeta pontes JS: window.NativeGPS, window.NativeShare, window.NativeSave
        web.addJavascriptInterface(new GpsBridge(), "NativeGPS");
        web.addJavascriptInterface(new NativeShareBridge(), "NativeShare");
        web.addJavascriptInterface(new NativeSaveBridge(), "NativeSave");

        if (state != null) web.restoreState(state);
        else               web.loadUrl(START_URL);

        // Pede permissões de localização
        requestLocationPermissions();
    }

    private void requestLocationPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            }
        } else {
            // Permissão já concedida — vincula o serviço
            startAndBindGpsService();
            // Pede background location separadamente (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startAndBindGpsService();
                // Android 10+: pedir background em sequência
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
                }
            }
        }
    }

    private void startAndBindGpsService() {
        Intent intent = new Intent(this, GpsService.class);
        startForegroundService(intent);
        bindService(intent, serviceConn, BIND_AUTO_CREATE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        // Múltiplos arquivos selecionados (não esperado mas tratado)
                        results = new Uri[]{data.getClipData().getItemAt(0).getUri()};
                    }
                }
                // Se data == null mas RESULT_OK: câmera pode ter devolvido via thumbnail
                // O WebView vai receber null e retornar vazio, que é aceitável
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConn);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Botão Voltar: deixa o JS tratar (fechar modais etc.); se JS não tratar, minimiza o app */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript(
                "(window.odometroBack && window.odometroBack()) ? '1' : '0'",
                value -> {
                    if (!"\"1\"".equals(value)) moveTaskToBack(true);
                });
    }
}
