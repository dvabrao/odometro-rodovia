package com.dvabrao.odometro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground Service que mantém o GPS ativo mesmo com a tela desligada.
 *
 * Estratégia de dupla fonte (Dual GPS):
 *  1. Tenta FusedLocationProviderClient (GPS + WiFi + sensores).
 *  2. Simultaneamente usa LocationManager/GPS_PROVIDER como fallback direto.
 *  3. A primeira fonte que entregar uma posição "vence" para aquela leitura.
 *  4. As posições são entregues ao MainActivity via PositionCallback.
 *  5. O MainActivity repassa para a WebView via evaluateJavascript().
 */
public class GpsService extends Service {

    private static final String CHANNEL_ID = "odometro_gps";
    private static final int    NOTIF_ID   = 1001;

    // Intervalo de atualização: 1 s, intervalo mínimo: 500 ms
    private static final long INTERVAL_MS     = 1_000L;
    private static final long MIN_INTERVAL_MS = 500L;

    public interface PositionCallback {
        void onPosition(double lat, double lon, float accuracy, float speed, float bearing, String source);
    }

    private final IBinder binder = new LocalBinder();

    // Fonte 1: FusedLocation (Google Play Services)
    private FusedLocationProviderClient fusedClient;
    private LocationCallback            fusedCallback;
    private boolean                     fusedTracking = false;

    // Fonte 2: LocationManager direto (GPS nativo do Android)
    private LocationManager   locationManager;
    private LocationListener  gpsListener;
    private LocationListener  networkListener;
    private boolean           lmTracking = false;

    private PositionCallback positionCallback;

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
        fusedClient     = LocationServices.getFusedLocationProviderClient(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        startTracking();
        return START_STICKY;
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
        startFusedTracking();
        startLocationManagerTracking();
    }

    public void stopTracking() {
        stopFusedTracking();
        stopLocationManagerTracking();
    }

    // ── Fonte 1: FusedLocationProviderClient ──────────────────────────
    private void startFusedTracking() {
        if (fusedTracking) return;

        fusedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null || positionCallback == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                android.util.Log.d("GpsService", "Fused → lat=" + loc.getLatitude()
                        + " acc=" + loc.getAccuracy());
                positionCallback.onPosition(
                        loc.getLatitude(), loc.getLongitude(),
                        loc.getAccuracy(), loc.getSpeed(), loc.getBearing(),
                        "fused");
            }
        };

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build();

        try {
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnSuccessListener(v ->
                            android.util.Log.d("GpsService", "FusedLocation: iniciado"))
                    .addOnFailureListener(e ->
                            android.util.Log.e("GpsService", "FusedLocation falhou: " + e.getMessage(), e));
            fusedTracking = true;
        } catch (SecurityException e) {
            android.util.Log.w("GpsService", "Fused sem permissão: " + e.getMessage());
        }
    }

    private void stopFusedTracking() {
        if (!fusedTracking || fusedCallback == null) return;
        fusedClient.removeLocationUpdates(fusedCallback);
        fusedCallback = null;
        fusedTracking = false;
    }

    // ── Fonte 2: LocationManager (GPS_PROVIDER + NETWORK_PROVIDER) ───
    private void startLocationManagerTracking() {
        if (lmTracking || locationManager == null) return;

        gpsListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (positionCallback == null) return;
                android.util.Log.d("GpsService", "LM/GPS → lat=" + loc.getLatitude()
                        + " acc=" + loc.getAccuracy());
                positionCallback.onPosition(
                        loc.getLatitude(), loc.getLongitude(),
                        loc.getAccuracy(), loc.getSpeed(), loc.getBearing(),
                        "gps");
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        networkListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (positionCallback == null) return;
                android.util.Log.d("GpsService", "LM/Network → lat=" + loc.getLatitude()
                        + " acc=" + loc.getAccuracy());
                positionCallback.onPosition(
                        loc.getLatitude(), loc.getLongitude(),
                        loc.getAccuracy(), loc.getSpeed(), loc.getBearing(),
                        "network");
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_INTERVAL_MS, 0f, gpsListener, Looper.getMainLooper());
                android.util.Log.d("GpsService", "LocationManager/GPS: iniciado");
            } else {
                android.util.Log.w("GpsService", "LocationManager/GPS: provider desabilitado");
            }

            if (netEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_INTERVAL_MS, 0f, networkListener, Looper.getMainLooper());
                android.util.Log.d("GpsService", "LocationManager/Network: iniciado");
            } else {
                android.util.Log.w("GpsService", "LocationManager/Network: provider desabilitado");
            }

            lmTracking = true;
        } catch (SecurityException e) {
            android.util.Log.w("GpsService", "LocationManager sem permissão: " + e.getMessage());
        }
    }

    private void stopLocationManagerTracking() {
        if (!lmTracking || locationManager == null) return;
        try {
            if (gpsListener     != null) locationManager.removeUpdates(gpsListener);
            if (networkListener != null) locationManager.removeUpdates(networkListener);
        } catch (Exception e) {
            android.util.Log.w("GpsService", "Erro ao parar LocationManager: " + e.getMessage());
        }
        gpsListener     = null;
        networkListener = null;
        lmTracking      = false;
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
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
