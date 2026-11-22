package dev.jason.gboardpatches.extension.webclipboard;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsActivity;

public final class ClipboardSyncService extends Service {
    private static final String TAG = "GboardWebClipboard";
    private static final String LOG_PREFIX = "[gboard-clipboard-sync]";
    private static final String SERVICE_LABEL = "Web Clipboard";
    private static final String BIND_FAILED_TOAST =
            "Web Clipboard could not start on this port.";
    private static final String ACTION_START_OR_UPDATE =
            "dev.jason.gboardpatches.extension.webclipboard.action.START_OR_UPDATE";
    private static final String ACTION_STOP =
            "dev.jason.gboardpatches.extension.webclipboard.action.STOP";
    private static final String ACTION_LOCAL_CLIP =
            "dev.jason.gboardpatches.extension.webclipboard.action.LOCAL_CLIP";
    private static final String EXTRA_TEXT = "text";
    private static final String NOTIFICATION_CHANNEL_ID = "web_clipboard";
    private static final int NOTIFICATION_ID = 1002;
    private static final long DUPLICATE_SUPPRESSION_WINDOW_MS = 750L;
    private static final AtomicLong START_COMMAND_SEQUENCE = new AtomicLong(1L);
    private static volatile ClipboardSyncService activeService;
    private static volatile ClipboardSyncWebPortal activePortal;

    private ClipboardManager serviceClipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener serviceClipboardListener;
    private boolean foregroundRefused;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdRegistrationListener;
    private ClipboardSyncWebPortal webPortal;
    private int currentPortalPort;
    private boolean currentPairingRequired;
    private String currentPairingCode = WebClipboardPreferences.DEFAULT_PAIRING_CODE;
    private String currentLoopbackIngressToken =
            WebClipboardPreferences.DEFAULT_LOOPBACK_INGRESS_TOKEN;
    private String lastPublishedText = "";
    private long lastPublishedAtElapsedMs;
    private final WebClipboardEchoSuppressor webClipboardEchoSuppressor =
            new WebClipboardEchoSuppressor(DUPLICATE_SUPPRESSION_WINDOW_MS);
    private final ExecutorService clipboardPublishExecutor = Executors.newSingleThreadExecutor();

    public static void startOrUpdate(Context context) {
        if (context == null || !WebClipboardLocalNetworkPermission.isGranted(context)) {
            return;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Intent intent = new Intent(appContext, ClipboardSyncService.class);
        intent.setAction(ACTION_START_OR_UPDATE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to start Web Clipboard service", throwable);
        }
    }

    public static void requestStop(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Intent intent = new Intent(appContext, ClipboardSyncService.class);
        intent.setAction(ACTION_STOP);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to request Web Clipboard service stop", throwable);
            appContext.stopService(new Intent(appContext, ClipboardSyncService.class));
        }
    }

    public static void submitLocalClipboard(Context context, String text) {
        if (context == null || !hasClipboardText(text)) {
            return;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Intent intent = new Intent(appContext, ClipboardSyncService.class);
        intent.setAction(ACTION_LOCAL_CLIP);
        intent.putExtra(EXTRA_TEXT, text);
        try {
            Log.i(TAG, LOG_PREFIX + " submit local clipboard len=" + text.length());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to submit local clipboard event", throwable);
        }
    }

    public static List<ClipboardSyncWebPortal.ConnectedClientSnapshot>
            getConnectedClientSnapshots() {
        ClipboardSyncWebPortal portal = activePortal;
        return portal == null ? Collections.emptyList() : portal.getConnectedClients();
    }

    public static boolean kickConnectedClient(String clientId) {
        ClipboardSyncWebPortal portal = activePortal;
        return portal != null && portal.kickClient(clientId);
    }

    public static boolean publishPhoneClipboardIfActive(String text) {
        ClipboardSyncService service = activeService;
        if (service == null || !hasClipboardText(text)) {
            return false;
        }
        if (service.webPortal == null) {
            return false;
        }
        service.publishClipboardToPortal(text);
        return true;
    }

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            activeService = this;
            Log.i(TAG, LOG_PREFIX + " service onCreate");
            ensureNotificationChannel();
            try {
                startForeground("Starting Web Clipboard");
            } catch (RuntimeException failure) {
                Log.w(TAG, LOG_PREFIX + " foreground start refused; stopping", failure);
                foregroundRefused = true;
                stopSelf();
                return;
            }
            WebClipboardTileController.markRuntimeActive(this, false);
            registerClipboardListener();
            refreshWebPortal();
        } catch (Throwable throwable) {
            Log.w(TAG, "Web Clipboard service onCreate failed", throwable);
            stopSelf();
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        try {
            Log.w(TAG, LOG_PREFIX + " foreground service timeout type=" + fgsType + "; stopping");
            stopWebPortal();
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String action = intent != null ? intent.getAction() : ACTION_START_OR_UPDATE;
            long commandSeq = START_COMMAND_SEQUENCE.getAndIncrement();
            if (foregroundRefused) {
                stopSelf();
                return START_NOT_STICKY;
            }
            Log.i(TAG, LOG_PREFIX + " onStartCommand"
                    + " seq=" + commandSeq
                    + ", action=" + action
                    + ", startId=" + startId
                    + ", portal=" + describePortal(webPortal));
            if (ACTION_STOP.equals(action)) {
                SharedPreferences preferences = WebClipboardPreferences.preferences(this);
                Log.i(TAG, LOG_PREFIX + " stop requested"
                        + " seq=" + commandSeq
                        + ", enabled=" + WebClipboardPreferences.isEnabled(preferences));
                if (!WebClipboardTileController.shouldStopService(preferences)) {
                    Log.i(TAG, LOG_PREFIX + " stop ignored because Web Clipboard was re-enabled"
                            + " seq=" + commandSeq);
                    refreshWebPortal();
                    return START_STICKY;
                }
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_LOCAL_CLIP.equals(action)) {
                String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
                if (hasClipboardText(text)) {
                    Log.i(TAG, LOG_PREFIX + " service local clip received action=" + action
                            + ", len=" + text.length()
                            + ", seq=" + commandSeq);
                    publishClipboardToPortal(text);
                }
            }
            refreshWebPortal();
            return START_STICKY;
        } catch (Throwable throwable) {
            Log.w(TAG, "Web Clipboard service onStartCommand failed", throwable);
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        try {
            Log.i(TAG, LOG_PREFIX + " service onDestroy portal=" + describePortal(webPortal));
            if (activeService == this) {
                activeService = null;
            }
            unregisterClipboardListener();
            clipboardPublishExecutor.shutdownNow();
            stopWebPortal();
            WebClipboardTileController.requestTileRefresh(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Web Clipboard service onDestroy cleanup failed", throwable);
        } finally {
            super.onDestroy();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refreshWebPortal() {
        SharedPreferences preferences = WebClipboardPreferences.preferences(this);
        boolean enabled = WebClipboardPreferences.isEnabled(preferences);
        int desiredPort = WebClipboardPreferences.getPort(preferences);
        boolean pairingRequired = WebClipboardPreferences.isPairingRequired(preferences);
        String pairingCode = WebClipboardPreferences.getPairingCode(preferences);
        String loopbackIngressToken = WebClipboardPreferences.getLoopbackIngressToken(preferences);
        boolean needsRestart = webPortal == null
                || currentPortalPort != desiredPort
                || currentPairingRequired != pairingRequired
                || !currentPairingCode.equals(pairingCode)
                || !currentLoopbackIngressToken.equals(loopbackIngressToken);
        Log.i(TAG, LOG_PREFIX + " refresh portal"
                + " enabled=" + enabled
                + ", desiredPort=" + desiredPort
                + ", pairingRequired=" + pairingRequired
                + ", pairingCode=" + summarizePairingCode(pairingCode)
                + ", loopbackToken=" + summarizeLoopbackToken(loopbackIngressToken)
                + ", currentPortal=" + describePortal(webPortal)
                + ", currentPort=" + currentPortalPort
                + ", currentPairingRequired=" + currentPairingRequired
                + ", currentPairingCode=" + summarizePairingCode(currentPairingCode)
                + ", currentLoopbackToken=" + summarizeLoopbackToken(currentLoopbackIngressToken)
                + ", needsRestart=" + needsRestart);
        if (!WebClipboardLocalNetworkPermission.isGranted(this)) {
            stopWebPortal();
            WebClipboardTileController.markRuntimeActive(this,false);
            stopSelf();return;
        }
        if (!enabled) {
            Log.i(TAG, LOG_PREFIX + " refresh portal stopping because feature disabled");
            stopWebPortal();
            stopSelf();
            return;
        }
        if (needsRestart) {
            Log.i(TAG, LOG_PREFIX + " refresh portal restarting current=" + describePortal(webPortal));
            stopWebPortal();
            if (!startWebPortal(desiredPort, pairingRequired, pairingCode, loopbackIngressToken)) {
                return;
            }
        } else {
            WebClipboardTileController.markRuntimeActive(this, true);
        }
        updateNotification("Open " + firstUrl(desiredPort));
        WebClipboardTileController.requestTileRefresh(this);
    }

    private boolean startWebPortal(int port, boolean pairingRequired, String pairingCode,
            String loopbackIngressToken) {
        ClipboardSyncWebPortal portal = new ClipboardSyncWebPortal(
                port,
                new ClipboardSyncWebPortal.ClipboardBridge() {
                    @Override
                    public void applyDesktopClipboard(String text) {
                        applyWebClipboardToPhone(text);
                    }
                    @Override
                    public void applyDesktopImage(byte[] imageBytes, String mimeType) {
                        applyWebImageToPhone(imageBytes, mimeType);
                    }
                    @Override
                    public void applyDesktopFile(byte[] fileBytes, String filename, String mimeType) {
                        applyWebFileToPhone(fileBytes, filename, mimeType);
                    }
                },
                ClipboardSyncWebPortal.WebAssets.from(this),
                new ClipboardSyncWebPortal.SecurityConfig(
                        pairingRequired,
                        pairingCode,
                        loopbackIngressToken));
        try {
            portal.start();
            webPortal = portal;
            activePortal = portal;
            currentPortalPort = port;
            currentPairingRequired = pairingRequired;
            currentPairingCode = pairingCode;
            currentLoopbackIngressToken = loopbackIngressToken;
            seedPortalFromCurrentClipboard(portal);
            WebClipboardTileController.markRuntimeActive(this, true);
            registerNsdService(port, pairingRequired);
            Log.i(TAG, LOG_PREFIX + " startWebPortal success"
                    + " instance=" + describePortal(portal)
                    + ", port=" + port
                    + ", pairingRequired=" + pairingRequired
                    + ", pairingCode=" + summarizePairingCode(pairingCode)
                    + ", loopbackToken=" + summarizeLoopbackToken(loopbackIngressToken));
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to start Web Clipboard portal", throwable);
            portal.stop();
            handlePortalStartFailure();
            return false;
        }
    }

    private void handlePortalStartFailure() {
        SharedPreferences preferences = WebClipboardPreferences.preferences(this);
        WebClipboardTileController.setEnabled(preferences, false);
        WebClipboardTileController.setRuntimeActive(preferences, false);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> Toast.makeText(getApplicationContext(), BIND_FAILED_TOAST,
                        Toast.LENGTH_LONG).show());
        WebClipboardTileController.requestTileRefresh(this);
        stopSelf();
    }

    private void registerNsdService(int port, boolean pairingRequired) {
        unregisterNsdService();
        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                return;
            }
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            String model = Build.MODEL != null ? Build.MODEL.replaceAll("[^a-zA-Z0-9 -]", "") : "Phone";
            serviceInfo.setServiceName("Gboard (" + model.trim() + ")");
            serviceInfo.setServiceType("_gboard-sync._tcp");
            serviceInfo.setPort(port);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                serviceInfo.setAttribute("version", "4.6.0");
                serviceInfo.setAttribute("pairing", pairingRequired ? "true" : "false");
            }

            nsdRegistrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo registeredInfo) {
                    Log.i(TAG, LOG_PREFIX + " NSD mDNS service registered: " + registeredInfo.getServiceName()
                            + " port=" + registeredInfo.getPort());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, LOG_PREFIX + " NSD registration failed code=" + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo arg0) {
                    Log.i(TAG, LOG_PREFIX + " NSD service unregistered");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, LOG_PREFIX + " NSD unregistration failed code=" + errorCode);
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener);
        } catch (Throwable t) {
            Log.w(TAG, LOG_PREFIX + " Failed to register NSD service", t);
        }
    }

    private void unregisterNsdService() {
        if (nsdManager != null && nsdRegistrationListener != null) {
            try {
                nsdManager.unregisterService(nsdRegistrationListener);
                Log.i(TAG, LOG_PREFIX + " unregisterNsdService called");
            } catch (Throwable t) {
                Log.w(TAG, LOG_PREFIX + " Failed to unregister NSD service", t);
            }
            nsdRegistrationListener = null;
        }
    }

    private void stopWebPortal() {
        ClipboardSyncWebPortal portal = webPortal;
        Log.i(TAG, LOG_PREFIX + " stopWebPortal"
                + " current=" + describePortal(portal)
                + ", active=" + describePortal(activePortal));
        webPortal = null;
        if (activePortal == portal) {
            activePortal = null;
        }
        currentPortalPort = 0;
        currentPairingRequired = false;
        currentPairingCode = WebClipboardPreferences.DEFAULT_PAIRING_CODE;
        currentLoopbackIngressToken = WebClipboardPreferences.DEFAULT_LOOPBACK_INGRESS_TOKEN;
        unregisterNsdService();
        if (portal != null) {
            portal.stop();
        }
        WebClipboardTileController.markRuntimeActive(this, false);
    }

    private void registerClipboardListener() {
        ClipboardManager clipboardManager = getSystemService(ClipboardManager.class);
        if (clipboardManager == null) {
            return;
        }
        ClipboardManager.OnPrimaryClipChangedListener listener = () -> {
            try {
                if (clipboardManager.hasPrimaryClip()) {
                    ClipData clipData = clipboardManager.getPrimaryClip();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        ClipData.Item item = clipData.getItemAt(0);
                        Uri uri = item.getUri();
                        if (uri != null) {
                            ClipDescription desc = clipData.getDescription();
                            String detectedMime = null;
                            if (desc != null) {
                                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                                    String m = desc.getMimeType(i);
                                    if (m != null && m.toLowerCase(Locale.US).startsWith("image/")) {
                                        detectedMime = m;
                                        break;
                                    }
                                }
                            }
                            if (detectedMime == null) {
                                try {
                                    detectedMime = getContentResolver().getType(uri);
                                } catch (Throwable ignored) {}
                            }
                            if (detectedMime != null && detectedMime.toLowerCase(Locale.US).startsWith("image/")) {
                                handlePhoneImageClip(uri, detectedMime);
                                return;
                            } else {
                                handlePhoneFileClip(uri, detectedMime);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error checking primary clip for image", t);
            }
            CharSequence currentText = currentClipboardText(clipboardManager);
            if (currentText != null) {
                Log.i(TAG, LOG_PREFIX + " service clipboard listener fired"
                        + " len=" + currentText.length()
                        + ", portal=" + describePortal(webPortal));
                publishClipboardToPortalIfNotWebEcho(currentText.toString());
            }
        };
        try {
            clipboardManager.addPrimaryClipChangedListener(listener);
            serviceClipboardManager = clipboardManager;
            serviceClipboardListener = listener;
        } catch (Throwable throwable) {
            Log.w(TAG, "Failed to register clipboard listener", throwable);
        }
    }

    private void handlePhoneImageClip(Uri uri, String resolvedMimeType) {
        clipboardPublishExecutor.execute(() -> {
            try {
                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        return;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[8192];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                        if (buffer.size() > 16 * 1024 * 1024) {
                            break;
                        }
                    }
                    bytes = buffer.toByteArray();
                }
                if (bytes.length == 0) {
                    return;
                }
                String hash = sha256Hex(bytes);
                if (webClipboardEchoSuppressor.shouldSuppressClipboardEvent("IMAGE_HASH:" + hash, SystemClock.elapsedRealtime())) {
                    Log.i(TAG, LOG_PREFIX + " service clipboard listener suppressed web echo image len=" + bytes.length);
                    return;
                }
                String mimeType = "image/png";
                if (resolvedMimeType != null && (resolvedMimeType.contains("jpeg") || resolvedMimeType.contains("jpg"))) {
                    mimeType = "image/jpeg";
                } else if (resolvedMimeType != null && !resolvedMimeType.isBlank()) {
                    mimeType = resolvedMimeType;
                }
                ClipboardSyncWebPortal portal = webPortal;
                if (portal != null) {
                    portal.publishPhoneImage(bytes, mimeType);
                    Log.i(TAG, LOG_PREFIX + " published phone image to portal len=" + bytes.length + ", mime=" + mimeType);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read image from clipboard URI: " + uri, t);
            }
        });
    }

    private void handlePhoneFileClip(Uri uri, String resolvedMimeType) {
        clipboardPublishExecutor.execute(() -> {
            try {
                String filename = resolveFilenameFromUri(uri);
                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        return;
                    }
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[8192];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                        if (buffer.size() > 50 * 1024 * 1024) { // 50MB limit
                            break;
                        }
                    }
                    bytes = buffer.toByteArray();
                }
                if (bytes.length == 0) {
                    return;
                }
                String hash = sha256Hex(bytes);
                if (webClipboardEchoSuppressor.shouldSuppressClipboardEvent("FILE_HASH:" + hash, SystemClock.elapsedRealtime())) {
                    return;
                }
                ClipboardSyncWebPortal portal = webPortal;
                if (portal != null) {
                    portal.publishPhoneFile(bytes, filename,
                            resolvedMimeType != null && !resolvedMimeType.isBlank()
                                    ? resolvedMimeType : "application/octet-stream");
                    Log.i(TAG, LOG_PREFIX + " published phone file to portal: " + filename + " len=" + bytes.length);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read file from clipboard URI: " + uri, t);
            }
        });
    }

    private String resolveFilenameFromUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {}
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.isBlank()) {
            return lastSegment;
        }
        return "file_" + System.currentTimeMillis();
    }

    private void unregisterClipboardListener() {
        if (serviceClipboardManager == null || serviceClipboardListener == null) {
            return;
        }
        try {
            serviceClipboardManager.removePrimaryClipChangedListener(serviceClipboardListener);
        } catch (Throwable ignored) {
            // Best effort.
        }
        serviceClipboardManager = null;
        serviceClipboardListener = null;
    }

    private void applyWebClipboardToPhone(String text) {
        if (!hasClipboardText(text)) {
            return;
        }
        ClipboardManager clipboardManager = serviceClipboardManager;
        if (clipboardManager == null) {
            clipboardManager = getSystemService(ClipboardManager.class);
        }
        if (clipboardManager == null) {
            return;
        }
        try {
            webClipboardEchoSuppressor.markWebApplied(text, SystemClock.elapsedRealtime());
            clipboardManager.setPrimaryClip(ClipData.newPlainText(
                    ClipboardSyncIngressContract.WEB_CLIPBOARD_LABEL,
                    text));
        } catch (Throwable throwable) {
            webClipboardEchoSuppressor.clearWebApplied();
            Log.w(TAG, "Failed to apply web clipboard to phone", throwable);
            return;
        }
        updateNotification("Clipboard applied from Web");
    }

    private void applyWebImageToPhone(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        ClipboardManager clipboardManager = serviceClipboardManager;
        if (clipboardManager == null) {
            clipboardManager = getSystemService(ClipboardManager.class);
        }
        if (clipboardManager == null) {
            return;
        }
        try {
            String extension = (mimeType != null && (mimeType.contains("jpeg") || mimeType.contains("jpg"))) ? ".jpg" : ".png";
            String resolvedMime = (mimeType != null && (mimeType.contains("jpeg") || mimeType.contains("jpg"))) ? "image/jpeg" : "image/png";
            String filename = "incoming_" + System.currentTimeMillis() + extension;
            WebClipboardFileProvider.saveImageFile(this, filename, bytes);
            Uri uri = saveToMediaStore(bytes, resolvedMime, filename);
            if (uri == null) {
                uri = WebClipboardFileProvider.getUriForFile(this, filename);
            }

            String hash = sha256Hex(bytes);
            webClipboardEchoSuppressor.markWebApplied("IMAGE_HASH:" + hash, SystemClock.elapsedRealtime());

            ClipDescription desc = new ClipDescription(
                    ClipboardSyncIngressContract.WEB_CLIPBOARD_LABEL,
                    new String[]{resolvedMime}
            );
            ClipData clip = new ClipData(desc, new ClipData.Item(uri));
            clipboardManager.setPrimaryClip(clip);
            updateNotification("Image applied from Web");
            Log.i(TAG, LOG_PREFIX + " applied web image to phone: " + filename + " len=" + bytes.length + " uri=" + uri);
        } catch (Throwable throwable) {
            webClipboardEchoSuppressor.clearWebApplied();
            Log.w(TAG, "Failed to apply web image to phone", throwable);
        }
    }

    private Uri saveToMediaStore(byte[] bytes, String mimeType, String filename) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WebClipboard");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        out.write(bytes);
                        out.flush();
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
                Log.i(TAG, LOG_PREFIX + " saved image to MediaStore: " + uri);
                return uri;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save image to MediaStore", t);
        }
        return null;
    }

    private void applyWebFileToPhone(byte[] bytes, String filename, String mimeType) {
        if (bytes == null || bytes.length == 0 || filename == null || filename.isBlank()) {
            return;
        }
        try {
            String hash = sha256Hex(bytes);
            webClipboardEchoSuppressor.markWebApplied("FILE_HASH:" + hash, SystemClock.elapsedRealtime());

            Uri uri = saveFileToDownloads(bytes, filename, mimeType);
            if (uri == null) {
                WebClipboardFileProvider.saveImageFile(this, filename, bytes);
                uri = WebClipboardFileProvider.getUriForFile(this, filename);
            }

            ClipboardManager clipboardManager = serviceClipboardManager != null
                    ? serviceClipboardManager : getSystemService(ClipboardManager.class);
            if (clipboardManager != null && uri != null) {
                ClipDescription desc = new ClipDescription(
                        ClipboardSyncIngressContract.WEB_CLIPBOARD_LABEL,
                        new String[]{mimeType != null && !mimeType.isBlank() ? mimeType : "application/octet-stream"}
                );
                ClipData clip = new ClipData(desc, new ClipData.Item(uri));
                clipboardManager.setPrimaryClip(clip);
            }

            showFileReceivedNotification(filename, uri, mimeType);
            Log.i(TAG, LOG_PREFIX + " applied web file to phone: " + filename + " len=" + bytes.length + " uri=" + uri);
        } catch (Throwable throwable) {
            webClipboardEchoSuppressor.clearWebApplied();
            Log.w(TAG, "Failed to apply web file to phone", throwable);
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private Uri saveFileToDownloads(byte[] bytes, String filename, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            if (mimeType != null && !mimeType.isBlank()) {
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            }
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebClipboard");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        out.write(bytes);
                        out.flush();
                    }
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                Log.i(TAG, LOG_PREFIX + " saved file to Downloads MediaStore: " + uri);
                return uri;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save file to MediaStore Downloads", t);
        }
        return null;
    }

    private void showFileReceivedNotification(String filename, Uri fileUri, String mimeType) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            ensureNotificationChannel();

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mimeType != null && !mimeType.isBlank() ? mimeType : "*/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    (int) System.currentTimeMillis(),
                    viewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Otrzymano plik z Maca")
                    .setContentText(filename)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();

            nm.notify(NOTIFICATION_ID + 1, notification);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to show file notification", t);
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(Character.forDigit((current >> 4) & 0xF, 16));
                builder.append(Character.forDigit(current & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(Arrays.hashCode(input));
        }
    }

    private void publishClipboardToPortalIfNotWebEcho(String text) {
        if (webClipboardEchoSuppressor.shouldSuppressClipboardEvent(
                text,
                SystemClock.elapsedRealtime())) {
            Log.i(TAG, LOG_PREFIX + " service clipboard listener suppressed web echo"
                    + " len=" + text.length());
            return;
        }
        publishClipboardToPortal(text);
    }

    private void seedPortalFromCurrentClipboard(ClipboardSyncWebPortal portal) {
        if (portal == null) {
            return;
        }
        ClipboardManager clipboardManager = serviceClipboardManager;
        if (clipboardManager == null) {
            clipboardManager = getSystemService(ClipboardManager.class);
        }
        if (clipboardManager == null) {
            return;
        }
        CharSequence currentText = currentClipboardText(clipboardManager);
        if (currentText == null || currentText.length() == 0) {
            return;
        }
        String text = currentText.toString();
        lastPublishedText = text;
        lastPublishedAtElapsedMs = SystemClock.elapsedRealtime();
        portal.publishPhoneClipboard(text);
    }

    private void publishClipboardToPortal(String text) {
        if (!hasClipboardText(text)) {
            return;
        }
        try {
            clipboardPublishExecutor.execute(() -> publishClipboardToPortalInternal(text));
        } catch (RejectedExecutionException ignored) {
            Log.i(TAG, LOG_PREFIX + " portal publish ignored during teardown");
        }
    }

    private void publishClipboardToPortalInternal(String text) {
        ClipboardSyncWebPortal portal = webPortal;
        if (portal == null || !hasClipboardText(text)) {
            Log.i(TAG, LOG_PREFIX + " portal publish skipped"
                    + ", portalReady=" + (portal != null)
                    + ", hasText=" + hasClipboardText(text));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (text.equals(lastPublishedText)
                && now - lastPublishedAtElapsedMs <= DUPLICATE_SUPPRESSION_WINDOW_MS) {
            Log.i(TAG, LOG_PREFIX + " portal publish suppressed duplicate len=" + text.length());
            return;
        }
        lastPublishedText = text;
        lastPublishedAtElapsedMs = now;
        Log.i(TAG, LOG_PREFIX + " service publishing phone clipboard"
                + " len=" + text.length()
                + ", portal=" + describePortal(portal));
        portal.publishPhoneClipboard(text);
        updateNotification("Clipboard available");
    }

    private String describePortal(ClipboardSyncWebPortal portal) {
        if (portal == null) {
            return "none";
        }
        return portal.getPortalInstanceId();
    }

    private String summarizePairingCode(String pairingCode) {
        if (pairingCode == null || pairingCode.isBlank()) {
            return "blank";
        }
        String trimmed = pairingCode.trim();
        if (trimmed.length() <= 2) {
            return "len=" + trimmed.length();
        }
        return "len=" + trimmed.length() + ", suffix=" + trimmed.substring(trimmed.length() - 2);
    }

    private String summarizeLoopbackToken(String loopbackIngressToken) {
        if (loopbackIngressToken == null || loopbackIngressToken.isEmpty()) {
            return "empty";
        }
        int suffixStart = Math.max(0, loopbackIngressToken.length() - 4);
        return "len=" + loopbackIngressToken.length()
                + ", suffix=" + loopbackIngressToken.substring(suffixStart);
    }

    private CharSequence currentClipboardText(ClipboardManager clipboardManager) {
        try {
            if (!clipboardManager.hasPrimaryClip()) {
                return null;
            }
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() <= 0) {
                return null;
            }
            ClipData.Item item = clipData.getItemAt(0);
            CharSequence directText = item.getText();
            if (directText != null && directText.length() > 0) {
                return directText;
            }
            // Do NOT call coerceToText if it has a URI, because reading binary image files as text
            // turns JPEG/PNG binary data into corrupted strings!
            if (item.getUri() == null) {
                return item.coerceToText(this);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startForeground(String statusText) {
        Notification notification = buildNotification(statusText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return;
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification(String statusText) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText));
    }

    private Notification buildNotification(String statusText) {
        Intent settingsIntent = new Intent(this, GboardPatchesSettingsActivity.class);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setContentTitle(SERVICE_LABEL)
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                SERVICE_LABEL,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Foreground status for the phone-hosted Web Clipboard service.");
        notificationManager.createNotificationChannel(channel);
    }

    private String firstUrl(int port) {
        List<String> urls = WebClipboardEndpointResolver.resolveUrls(port);
        return urls.isEmpty() ? "http://<phone LAN IP>:" + port + "/" : urls.get(0);
    }

    private static boolean hasClipboardText(String text) {
        return text != null && !text.isEmpty();
    }

}

