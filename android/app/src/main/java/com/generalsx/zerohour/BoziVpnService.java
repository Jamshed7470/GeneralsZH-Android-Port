package com.generalsx.zerohour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * Туннель игровой сети.
 *
 * <p>В туннель заворачивается только подсеть игры 10.42.0.0/24 и
 * широковещательный адрес, и только для самого приложения: остальной интернет
 * телефона и другие программы работают как прежде. Это не «VPN на весь
 * телефон», а провод между игроками.
 *
 * <p>Отдельный маршрут на 255.255.255.255 обязателен: именно таким пакетом
 * Generals ищет соперников в локальной сети, и без маршрута ядро отправило бы
 * его в обычный Wi-Fi, где друга из другого города нет.
 *
 * <p>Сокет самого туннеля защищается через {@link #protect(int)} — иначе его
 * пакеты попали бы в тот же туннель и связь замкнулась бы на себя.
 */
public class BoziVpnService extends VpnService implements glnet.Protector {
    private static final String TAG = "BOZI";
    private static final String CHANNEL_ID = "bozi_tunnel";
    private static final int NOTIFICATION_ID = 4242;

    public static final String ACTION_START = "com.generalsx.zerohour.TUNNEL_START";
    public static final String ACTION_STOP = "com.generalsx.zerohour.TUNNEL_STOP";

    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!startTunnel()) {
            shutdown();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean startTunnel() {
        glnet.Session session = BoziTunnel.get().raw();
        if (session == null) {
            Log.e(TAG, "туннель запущен без сессии");
            return false;
        }
        String virtualIp = session.virtualIP();
        if (virtualIp == null || virtualIp.isEmpty()) {
            Log.e(TAG, "сервер не выдал адрес в игровой сети");
            return false;
        }

        try {
            Builder builder = new Builder();
            builder.setSession("BOZI");
            builder.addAddress(virtualIp, (int) session.netmask());
            builder.addRoute(session.subnetRoute(), (int) session.subnetRoutePrefix());
            builder.addRoute(session.broadcastRoute(), 32);
            builder.setMtu((int) session.mtu());
            builder.setBlocking(true);
            try {
                builder.addAllowedApplication(getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "не ограничить туннель своим приложением", e);
            }

            tun = builder.establish();
            if (tun == null) {
                Log.e(TAG, "система не выдала туннель");
                return false;
            }
            // Дескриптор отдаём сетевой части насовсем: закрывать его будет она.
            int fd = tun.detachFd();
            tun = null;

            session.start(fd, this, false);
            BoziTunnel.get().markTunnelStarted(true);
            startForegroundWithNotice(virtualIp);
            Log.i(TAG, "туннель поднят, адрес " + virtualIp);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "не поднять туннель", e);
            return false;
        }
    }

    /**
     * Вызывается из сетевой части для сокета туннеля. Тип {@code long} потому,
     * что gomobile переводит Go-шный int именно так.
     */
    @Override
    public boolean protect(long fd) {
        return protect((int) fd);
    }

    private void startForegroundWithNotice(String virtualIp) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Сетевая игра", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Связь с соперниками, пока идёт партия");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, BoziLobbyActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, pendingFlags);

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = nb
                .setContentTitle("Сетевая игра активна")
                .setContentText("Ваш адрес в игре: " + virtualIp)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            // Система может отказать в службе переднего плана (например, пока
            // приложение в фоне и нет разрешения на уведомления). Туннель это
            // не ломает: он уже поднят и работает, пока игрок в приложении.
            Log.w(TAG, "не показать уведомление о туннеле", e);
        }
    }

    private void shutdown() {
        BoziTunnel.get().stop();
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "туннель отозван системой");
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        BoziTunnel.get().markTunnelStarted(false);
        super.onDestroy();
    }

    /** Запускает туннель. Разрешение на VPN должно быть уже получено. */
    public static void start(Context context) {
        context.startService(new Intent(context, BoziVpnService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, BoziVpnService.class).setAction(ACTION_STOP));
    }
}
