package com.dvabrao.odometro;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

    private WebView web;
    private GpsService gpsService;
    private boolean serviceBound = false;

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
        web.setBackgroundColor(0xFF003F87);
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

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        // Geolocation API do WebView (funciona em foreground)
        s.setGeolocationEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " OdometroApp/1.0");

        // Injeta ponte JS: window.NativeGPS
        web.addJavascriptInterface(new GpsBridge(), "NativeGPS");

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
