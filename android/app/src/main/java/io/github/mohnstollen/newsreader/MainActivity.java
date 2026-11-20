package io.github.mohnstollen.newsreader;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NewsHttpPlugin.class);
        registerPlugin(BackgroundPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
