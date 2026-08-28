package app.minddeck.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://minddeck-ai-flashcards.vercel.app/";
    private FrameLayout root;
    private WebView webView;
    private TextView status;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 10, 15));
        getWindow().setNavigationBarColor(Color.rgb(9, 10, 15));
        WebView.setWebContentsDebuggingEnabled(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 10, 15));
        setContentView(root);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setUserAgentString(settings.getUserAgentString() + " MindDeckAndroid/1.1");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showStatus("Opening MindDeck...");
            }

            @Override public void onPageFinished(WebView view, String url) {
                hideStatus();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showOffline();
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showStatus("A secure connection could not be verified. MindDeck stopped the page to protect your data.");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (isTrustedNavigation(target)) {
                    view.loadUrl(target.toString());
                } else {
                    openExternalBrowser(target);
                }
                return true;
            }
        });

        root.addView(webView);
        addStatusView();
        if (isOnline()) webView.loadUrl(APP_URL); else showOffline();
    }

    private boolean isTrustedNavigation(Uri target) {
        if (!"https".equalsIgnoreCase(target.getScheme())) return false;
        String host = target.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("minddeck-ai-flashcards.vercel.app")
                || host.endsWith(".supabase.co");
    }

    private void openExternalBrowser(Uri target) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, target));
        } catch (Exception ignored) {
            showStatus("This link could not be opened safely.");
        }
    }

    private void addStatusView() {
        status = new TextView(this);
        status.setTextColor(Color.rgb(234, 255, 255));
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 32, 48, 32);
        status.setBackgroundColor(Color.rgb(9, 10, 15));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(status, params);
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(android.view.View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(android.view.View.GONE);
    }

    private void showOffline() {
        status.setText("MindDeck needs an internet connection to sync your decks.\n\nCheck your connection and reopen the app.");
        status.setVisibility(android.view.View.VISIBLE);
    }

    private boolean isOnline() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
