package com.dvabrao.odometro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
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
 * Como funciona:
 *  1. Inicia como foreground service (mostra notificação persistente).
 *  2. Usa FusedLocationProviderClient (GPS + WiFi + sensores) para maior precisão.
 *  3. A cada posição recebida, chama o callback registrado pelo MainActivity.
 *  4. O MainActivity repassa as coordenadas para a WebView via evaluateJavascript().
 *  5. O JS da WebView sobrescreve watchPosition() para usar essas coordenadas
 *     quando window.NativeGPS.isNative() === "true".
 */
public class GpsService extends Service {

    private static final String CHANNEL_ID = "odometro_gps";
    private static final int    NOTIF_ID   = 1001;

    // Intervalo de atualização: 1 s, intervalo mínimo: 500 ms
    private static final long INTERVAL_MS     = 1_000L;
    private static final long MIN_INTERVAL_MS = 500L;

    public interface PositionCallback {
        void onPosition(double lat, double lon, float accuracy, float speed, float bearing);
    }

    private final IBinder binder = new LocalBinder();
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
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
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 10+ exige o tipo de serviço quando foregroundServiceType="location" está no manifest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
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

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null || positionCallback == null) return;
                android.location.Location loc = result.getLastLocation();
                if (loc == null) return;
                positionCallback.onPosition(
                        loc.getLatitude(),
                        loc.getLongitude(),
                        loc.getAccuracy(),
                        loc.getSpeed(),     // m/s
                        loc.getBearing());
            }
        };

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                .build();

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                    .addOnSuccessListener(v ->
                            android.util.Log.d("GpsService", "FusedLocation: atualizações iniciadas"))
                    .addOnFailureListener(e ->
                            android.util.Log.e("GpsService", "FusedLocation falhou: " + e.getMessage(), e));
            tracking = true;
        } catch (SecurityException e) {
            // Permissão negada — o serviço ficará em espera
            android.util.Log.w("GpsService", "Sem permissão de localização: " + e.getMessage());
        }
    }

    public void stopTracking() {
        if (!tracking || locationCallback == null) return;
        fusedClient.removeLocationUpdates(locationCallback);
        locationCallback = null;
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
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
