package com.minddeck.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class FocusTimerService extends Service {
    public static final String ACTION_START = "com.minddeck.app.timer.START";
    public static final String ACTION_PAUSE = "com.minddeck.app.timer.PAUSE";
    public static final String ACTION_RESET = "com.minddeck.app.timer.RESET";
    public static final String ACTION_COMPLETE = "com.minddeck.app.timer.COMPLETE";
    public static final String EXTRA_SECONDS = "seconds";
    private static final String CHANNEL_ID = "minddeck_focus_timer";
    private static final int NOTIFICATION_ID = 1203;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Focus timer", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Live MindDeck focus-session countdown");
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESET : intent.getAction();
        long seconds = intent == null ? 0 : Math.max(0, intent.getLongExtra(EXTRA_SECONDS, 0));
        if (ACTION_START.equals(action) && seconds > 0) {
            startForeground(NOTIFICATION_ID, buildCountdown(seconds));
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action) && seconds > 0) {
            notifyIfAllowed(buildPaused(seconds));
            return START_NOT_STICKY;
        }
        if (ACTION_COMPLETE.equals(action)) {
            stopForeground(true);
            notifyIfAllowed(buildComplete());
            stopSelf();
            return START_NOT_STICKY;
        }
        stopForeground(true);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
        return START_NOT_STICKY;
    }

    private void notifyIfAllowed(Notification notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private NotificationCompat.Builder baseBuilder() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.minddeck.app.R.drawable.ic_minddeck)
            .setContentTitle("MindDeck focus session")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    }

    private Notification buildCountdown(long seconds) {
        NotificationCompat.Builder builder = baseBuilder()
            .setContentText("Stay focused — your timer is running")
            .setWhen(System.currentTimeMillis() + seconds * 1000)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setUsesChronometer(true).setChronometerCountDown(true);
        } else {
            builder.setContentText("Focus session running — open MindDeck for remaining time");
        }
        return builder.build();
    }

    private Notification buildPaused(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return baseBuilder()
            .setContentText(String.format("Paused · %02d:%02d remaining", minutes, remainder))
            .setOngoing(true)
            .build();
    }

    private Notification buildComplete() {
        return baseBuilder()
            .setContentTitle("Focus session complete ✨")
            .setContentText("Your focused minutes were saved to MindDeck")
            .setAutoCancel(true)
            .setOngoing(false)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
