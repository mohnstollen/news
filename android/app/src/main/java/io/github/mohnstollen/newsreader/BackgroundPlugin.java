package io.github.mohnstollen.newsreader;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import org.json.JSONObject;

/**
 * Hintergrund-Update: Die Web-App teilt mit, welche Feeds sie hat und welche Artikel sie kennt
 * (configure). RefreshWorker lädt die Feeds dann auch bei geschlossener App, meldet neue Artikel
 * und legt die Antworten ab; die App holt sie beim nächsten Öffnen ab (take) und speichert sie.
 */
@CapacitorPlugin(name = "NewsBackground")
public class BackgroundPlugin extends Plugin {

    @PluginMethod
    public void configure(PluginCall call) {
        JSObject data = call.getData();
        try {
            RefreshWorker.saveConfig(getContext(), data);
            RefreshWorker.schedule(getContext(), data.optBoolean("enabled", false), data.optInt("intervalMinutes", 60));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void take(PluginCall call) {
        JSArray items = new JSArray();
        File dir = RefreshWorker.pendingDir(getContext());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try {
                    items.put(new JSONObject(RefreshWorker.read(f)));
                } catch (Exception ignored) {
                    // beschädigt: verwerfen
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        RefreshWorker.cancelNotification(getContext());
        JSObject res = new JSObject();
        res.put("items", items);
        call.resolve(res);
    }
}
