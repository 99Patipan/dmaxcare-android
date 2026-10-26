package th.dmaxcare.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.FrameLayout;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final int R_SAVE = 1, R_PICK = 2, R_FILE = 3;
    private WebView web;
    private String outJson, picked;
    private ValueCallback<Uri[]> fileCb;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        int navy = Color.rgb(15, 23, 42);
        getWindow().setStatusBarColor(navy);
        getWindow().setNavigationBarColor(Color.rgb(255, 255, 255));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(navy);
        // Android 15 (targetSdk 35) draws edge-to-edge: pad content below/above system bars
        root.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                    i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(244, 246, 251));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), R_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    class Bridge {
        @JavascriptInterface public String loadData() {
            try {
                File f = new File(getFilesDir(), "records.json");
                return f.exists() ? read(new FileInputStream(f)) : "";
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public boolean saveData(String s) {
            try {
                File tmp = new File(getFilesDir(), "records.tmp"), f = new File(getFilesDir(), "records.json");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
                return tmp.renameTo(f);
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void exportBackup(String name, String json) {
            outJson = json;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, R_SAVE);
            });
        }
        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), R_PICK));
        }
        @JavascriptInterface public String takePicked() { String s = picked; picked = null; return s == null ? "" : s; }
    }

    private static String read(InputStream in) throws IOException {
        try (ByteArrayOutputStream o = new ByteArrayOutputStream(); InputStream i = in) {
            byte[] buf = new byte[65536]; int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        boolean ok = res == RESULT_OK && data != null;
        if (req == R_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(ok ? WebChromeClient.FileChooserParams.parseResult(res, data) : null);
            fileCb = null;
        } else if (req == R_SAVE) {
            if (!ok || data.getData() == null) { js("onExported(false)"); return; }
            Uri u = data.getData();
            new Thread(() -> {
                boolean done = false;
                try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
                    o.write(outJson.getBytes(StandardCharsets.UTF_8)); done = true;
                } catch (Exception e) { }
                outJson = null;
                js("onExported(" + done + ")");
            }).start();
        } else if (req == R_PICK && ok && data.getData() != null) {
            Uri u = data.getData();
            new Thread(() -> {
                try { picked = read(getContentResolver().openInputStream(u)); js("onPicked()"); }
                catch (Exception e) { picked = ""; js("onPicked()"); }
            }).start();
        }
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.onBack&&window.onBack()", v -> { if (!"true".equals(v)) finish(); });
    }
}
