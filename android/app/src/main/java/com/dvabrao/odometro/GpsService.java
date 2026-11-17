package com.dvabrao.odometro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * Foreground Service que mantém o GPS ativo mesmo com a tela desligada.
 *
 * Como funciona:
 *  1. Inicia como foreground service (mostra notificação persistente).
 *  2. Registra um LocationListener no LocationManager com intervalo de 1 s / 2 m.
 *  3. A cada posição recebida, chama o callback registrado pelo MainActivity.
 *  4. O MainActivity repassa as coordenadas para a WebView via evaluateJavascript().
 *  5. O JS da WebView sobrescreve watchPosition() para usar essas coordenadas
 *     quando window.NativeGPS.isNative() === "true".
 */
public class GpsService extends Service {

    private static final String CHANNEL_ID   = "odometro_gps";
    private static final int    NOTIF_ID     = 1001;

    // Intervalo mínimo: 1 s, deslocamento mínimo: 2 m
    private static final long   MIN_TIME_MS  = 1_000L;
    private static final float  MIN_DIST_M   = 2.0f;

    public interface PositionCallback {
        void onPosition(double lat, double lon, float accuracy, float speed, float bearing);
    }

    private final IBinder binder = new LocalBinder();
    private LocationManager locationManager;
    private LocationListener locationListener;
    private PositionCallback positionCallback;
    private boolean tracking = false;

    // ── Binder ────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        GpsService getService() { return GpsService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Ciclo de vida ─────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        startTracking();
        return START_STICKY;   // reinicia automaticamente se o sistema matar
    }

    @Override
    public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    // ── API pública ───────────────────────────────────────────────────
    public void setPositionCallback(PositionCallback cb) {
        positionCallback = cb;
    }

    public void startTracking() {
        if (tracking) return;
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (positionCallback != null) {
                    positionCallback.onPosition(
                            loc.getLatitude(),
                            loc.getLongitude(),
                            loc.getAccuracy(),
                            loc.getSpeed(),       // m/s
                            loc.getBearing());
                }
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String p)  {}
            @Override public void onProviderDisabled(String p) {}
        };

        try {
            // GPS provider (preciso, funciona em campo)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_MS, MIN_DIST_M,
                        locationListener, Looper.getMainLooper());
            }
            // Network provider como fallback
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_MS, MIN_DIST_M,
                        locationListener, Looper.getMainLooper());
            }
            tracking = true;
        } catch (SecurityException e) {
            // Permissão negada — o serviço ficará em espera
        }
    }

    public void stopTracking() {
        if (!tracking || locationListener == null) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {}
        locationListener = null;
        tracking = false;
    }

    // ── Notificação obrigatória do foreground service ─────────────────
    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
