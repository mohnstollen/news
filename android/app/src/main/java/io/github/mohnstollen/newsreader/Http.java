package io.github.mohnstollen.newsreader;

import android.content.Context;
import java.io.File;
import java.util.concurrent.TimeUnit;
import okhttp3.Cache;
import okhttp3.OkHttpClient;

/** Gemeinsamer HTTP-Client für Plugin und Hintergrund-Update. */
final class Http {
    private static OkHttpClient client;

    private Http() {}

    static synchronized OkHttpClient client(Context context) {
        if (client == null) {
            client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .cache(new Cache(new File(context.getCacheDir(), "http"), 20L * 1024 * 1024))
                .build();
        }
        return client;
    }
}
