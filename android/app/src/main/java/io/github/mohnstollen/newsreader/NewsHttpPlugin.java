package io.github.mohnstollen.newsreader;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP für die Web-App: Feeds, Webseiten (Volltext) und Nextcloud.
 * Läuft nativ und unterliegt damit nicht der CORS-Prüfung der WebView. Mit binary=true kommt der
 * Inhalt Base64-kodiert zurück, damit die App den Zeichensatz selbst aus dem Dokument bestimmen kann.
 */
@CapacitorPlugin(name = "NewsHttp")
public class NewsHttpPlugin extends Plugin {

    static final int MAX_BODY = 8 * 1024 * 1024;

    @PluginMethod
    public void request(PluginCall call) {
        String url = call.getString("url");
        String method = call.getString("method", "GET").toUpperCase(Locale.ROOT);
        String body = call.getString("body");
        boolean binary = Boolean.TRUE.equals(call.getBoolean("binary", false));
        JSObject headers = call.getObject("headers", new JSObject());
        if (url == null) {
            call.reject("Keine Adresse angegeben");
            return;
        }
        Request.Builder builder;
        try {
            builder = new Request.Builder().url(url);
        } catch (IllegalArgumentException e) {
            call.reject("Ungültige Adresse: " + url, "BAD_URL");
            return;
        }
        String contentType = null;
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = headers.getString(key);
            if (value == null) continue;
            if (key.equalsIgnoreCase("Content-Type")) contentType = value;
            else builder.header(key, value);
        }
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(body, contentType != null ? MediaType.parse(contentType) : null);
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            requestBody = RequestBody.create(new byte[0], null);
        }
        builder.method(method, requestBody);

        Http.client(getContext()).newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                call.reject(e.getMessage() != null ? e.getMessage() : e.toString(), "NETWORK", e);
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (ResponseBody rb = response.body()) {
                    JSObject res = new JSObject();
                    res.put("status", response.code());
                    res.put("url", response.request().url().toString());
                    JSObject h = new JSObject();
                    for (Map.Entry<String, List<String>> e : response.headers().toMultimap().entrySet()) {
                        h.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()));
                    }
                    res.put("headers", h);
                    byte[] bytes = rb != null ? readLimited(rb.byteStream()) : new byte[0];
                    if (binary) res.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    else res.put("data", new String(bytes, rb != null && rb.contentType() != null && rb.contentType().charset() != null
                        ? rb.contentType().charset() : java.nio.charset.StandardCharsets.UTF_8));
                    call.resolve(res);
                } catch (IOException e) {
                    call.reject(e.getMessage() != null ? e.getMessage() : e.toString(), "NETWORK", e);
                }
            }
        });
    }

    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("Antwort ist zu groß");
        }
        return out.toByteArray();
    }
}
