package io.github.mohnstollen.newsreader;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Lädt die Feeds im Hintergrund, auch wenn die App geschlossen ist. Gespeichert werden die
 * Artikel erst von der Web-App (BackgroundPlugin.take); hier wird nur geladen, abgelegt und –
 * wenn eingeschaltet – über neue Artikel benachrichtigt.
 */
public class RefreshWorker extends Worker {
    private static final String WORK = "refresh";
    private static final String CONFIG = "background.json";
    private static final String CHANNEL = "new_articles";
    private static final int NOTIFICATION_ID = 4711;
    private static final int MAX_LINES = 5;

    public RefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // ---------- Einstellungen der Web-App ----------

    static void saveConfig(Context context, JSONObject config) throws IOException {
        write(new File(context.getFilesDir(), CONFIG), config.toString());
    }

    static JSONObject loadConfig(Context context) {
        try {
            return new JSONObject(read(new File(context.getFilesDir(), CONFIG)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void schedule(Context context, boolean enabled, int intervalMinutes) {
        WorkManager wm = WorkManager.getInstance(context);
        if (!enabled) {
            wm.cancelUniqueWork(WORK);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RefreshWorker.class, Math.max(15, intervalMinutes), TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build())
            .setInitialDelay(Math.max(15, intervalMinutes), TimeUnit.MINUTES)
            .build();
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static File pendingDir(Context context) {
        File dir = new File(context.getFilesDir(), "pending");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    // ---------- Hintergrundlauf ----------

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        JSONObject config = loadConfig(context);
        if (!config.optBoolean("enabled", false)) return Result.success();
        JSONArray feeds = config.optJSONArray("feeds");
        if (feeds == null) return Result.success();
        List<String[]> fresh = new ArrayList<>();   // [Feed-Titel, Artikel-Titel]
        boolean changed = false;
        for (int i = 0; i < feeds.length() && !isStopped(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed == null) continue;
            try {
                changed |= refreshFeed(context, feed, fresh);
            } catch (Exception ignored) {
                // Fehler zeigt die App beim nächsten eigenen Laden an
            }
        }
        if (changed) {
            try {
                saveConfig(context, config);       // neu gemeldete Artikel gelten als bekannt
            } catch (IOException ignored) {
            }
        }
        if (config.optBoolean("notify", false) && !fresh.isEmpty()) showNotification(context, fresh);
        return Result.success();
    }

    /** Lädt einen Feed; liefert true, wenn neue Artikel gemeldet wurden. */
    private boolean refreshFeed(Context context, JSONObject feed, List<String[]> fresh) throws Exception {
        String url = feed.getString("url");
        File pending = new File(pendingDir(context), sha1(url) + ".json");
        JSONObject previous = pending.exists() ? new JSONObject(read(pending)) : null;
        String etag = previous != null ? previous.optString("etag", "") : feed.optString("etag", "");
        String lastModified = previous != null ? previous.optString("lastModified", "") : feed.optString("lastModified", "");

        Request.Builder builder = new Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) NewsReader/2")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5");
        if (!etag.isEmpty()) builder.header("If-None-Match", etag);
        if (!lastModified.isEmpty()) builder.header("If-Modified-Since", lastModified);
        byte[] body;
        JSONObject item = new JSONObject();
        try (Response response = Http.client(context).newCall(builder.build()).execute()) {
            if (response.code() == 304 || !response.isSuccessful()) return false;
            ResponseBody rb = response.body();
            if (rb == null) return false;
            body = NewsHttpPlugin.readLimited(rb.byteStream());
            item.put("url", url);
            item.put("etag", response.header("ETag", ""));
            item.put("lastModified", response.header("Last-Modified", ""));
            item.put("contentType", response.header("Content-Type", ""));
            item.put("fetchedAt", System.currentTimeMillis());
            item.put("data", Base64.encodeToString(body, Base64.NO_WRAP));
        }
        write(pending, item.toString());

        // Neue Einträge erkennen: weder Kennung noch Titel sind der App bekannt
        JSONArray knownArr = feed.optJSONArray("known");
        Set<String> known = new HashSet<>();
        if (knownArr != null) for (int k = 0; k < knownArr.length(); k++) known.add(knownArr.optString(k));
        boolean added = false;
        for (String[] entry : entries(body)) {
            if (known.contains(entry[0]) || known.contains(entry[1])) continue;
            if (feed.optBoolean("notify", true)) fresh.add(new String[] {feed.optString("title", ""), entry[1]});
            if (knownArr == null) {
                knownArr = new JSONArray();
                feed.put("known", knownArr);
            }
            knownArr.put(entry[0]);
            known.add(entry[0]);
            added = true;
        }
        return added;
    }

    /** Kennung und Titel der Einträge (RSS: guid, sonst link; Atom: id, sonst link). */
    static List<String[]> entries(byte[] body) {
        List<String[]> out = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser p = factory.newPullParser();
            p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true);
            p.setInput(new ByteArrayInputStream(body), null);
            String guid = null, link = null, title = null, tag = null;
            boolean inItem = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName().toLowerCase(Locale.ROOT);
                    if (name.equals("item") || name.equals("entry")) {
                        inItem = true;
                        guid = link = title = null;
                    } else if (inItem) {
                        tag = name;
                        if (name.equals("link") && p.getAttributeValue(null, "href") != null
                            && (p.getAttributeValue(null, "rel") == null || "alternate".equals(p.getAttributeValue(null, "rel")))) {
                            link = p.getAttributeValue(null, "href");
                        }
                    }
                } else if (ev == XmlPullParser.TEXT && inItem && tag != null) {
                    String text = p.getText().trim();
                    if (text.isEmpty()) continue;
                    if ((tag.equals("guid") || tag.equals("id")) && guid == null) guid = text;
                    else if (tag.equals("link") && link == null) link = text;
                    else if (tag.equals("title") && title == null) title = text;
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName().toLowerCase(Locale.ROOT);
                    if ((name.equals("item") || name.equals("entry")) && inItem) {
                        inItem = false;
                        String t = title != null ? title : "";
                        String id = guid != null ? guid : link != null ? link : t;
                        if (!id.isEmpty()) out.add(new String[] {id, t});
                    }
                    tag = null;
                }
            }
        } catch (Exception ignored) {
            // schlampiges XML: dann eben ohne Meldung; die App liest den Feed trotzdem
        }
        return out;
    }

    // ---------- Benachrichtigung ----------

    private static void showNotification(Context context, List<String[]> fresh) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Neue Artikel", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_news)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(fresh.size());
        if (fresh.size() == 1) {
            b.setContentTitle(fresh.get(0)[1]).setContentText(fresh.get(0)[0]);
        } else {
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
            Set<String> feeds = new java.util.LinkedHashSet<>();
            for (int i = 0; i < fresh.size(); i++) {
                feeds.add(fresh.get(i)[0]);
                if (i < MAX_LINES) style.addLine(fresh.get(i)[0] + ": " + fresh.get(i)[1]);
            }
            if (fresh.size() > MAX_LINES) style.setSummaryText("und " + (fresh.size() - MAX_LINES) + " weitere");
            List<String> names = new ArrayList<>(feeds);
            String text = String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? " …" : "");
            b.setContentTitle(fresh.size() + " neue Artikel").setContentText(text).setStyle(style);
        }
        try {
            manager.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    static void cancelNotification(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }

    // ---------- Dateien ----------

    static String read(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("Konnte " + f + " nicht schreiben");
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
