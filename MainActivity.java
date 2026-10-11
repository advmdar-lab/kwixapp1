package shop.kwix.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * MB2B: the mb2b.in website inside a simple Android app.
 * Opens the home page (buyers and sellers), keeps the login, lets sellers pick or take photos,
 * and sends phone and UPI links to the right apps.
 * Version 2: the MB2B bar that stays in the phone's notification panel (Inbox, Buy Leads, Search),
 * the number of new messages on it, and a notification when new messages come (checked every 15 minutes).
 * Version 3: instant notifications from mb2b.in (Firebase), and the phone says out loud when a new inquiry comes
 * ("MB2B par naya inquiry aaya hai, aap check kijiye"). It can be turned off in Account.
 */
public class MainActivity extends Activity {

    static final String SITE = "https://mb2b.in";
    private static final String START = SITE + "/";
    private static final int PICK = 41;
    private static final int ASK_NOTIFY = 42;
    static final String CH_BAR = "mb2b_bar";
    static final String CH_MSG = "mb2b_messages";
    static final int ID_BAR = 1;
    static final int ID_MSG = 2;
    static final int JOB = 7;
    static final String PREF = "mb2b";

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private boolean wantSearch;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bar, new FrameLayout.LayoutParams(-1, 8));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " MB2BApp/3.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        // The page tells the app how many messages are new (for the MB2B bar).
        web.addJavascriptInterface(new Bridge(getApplicationContext()), "MB2BApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handle(req.getUrl());
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) { bar.setVisibility(View.VISIBLE); }
            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();   // keep the user logged in
                saveCookie(getApplicationContext());   // for the 15-minute check of new messages
                if (wantSearch) { wantSearch = false; showKeyboard(); }
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, offlinePage(), "text/html", "utf-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) { bar.setProgress(p); }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                Intent cam = cameraIntent();
                Intent go;
                if (params.isCaptureEnabled() && cam != null) {
                    go = cam;                                  // "Take photo" button
                } else {
                    go = Intent.createChooser(pick, "Choose photos");
                    if (cam != null) go.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                }
                try {
                    startActivityForResult(go, PICK);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "No app found to pick photos", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        Uri link = getIntent() != null ? getIntent().getData() : null;
        if (saved != null) web.restoreState(saved);
        else {
            if (link != null && isOurs(link) && "search".equals(link.getFragment())) wantSearch = true;
            web.loadUrl(link != null && isOurs(link) ? link.toString() : START);
        }

        // The MB2B bar in the notification panel, and the check for new messages every 15 minutes.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, ASK_NOTIFY);
        }
        showBar(this);
        Poll.schedule(this);
        fetchToken();   // version 3: instant notifications
    }

    /** The Firebase code of this phone. The page sends it to mb2b.in (window.MB2BApp.pushToken). */
    private void fetchToken() {
        try {
            if (FirebaseApp.getApps(this).isEmpty() && FirebaseApp.initializeApp(this) == null) return;   // built without google-services.json
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                try {
                    if (!t.isSuccessful() || t.getResult() == null) return;
                    prefs(this).edit().putString("fcm", t.getResult()).apply();
                    if (web != null) web.post(() -> web.evaluateJavascript("window.appSync&&appSync()", null));
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }   // never stop the app because of notifications
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == ASK_NOTIFY) showBar(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBar(this);   // Android 14 lets people swipe the bar away; it comes back when the app opens
    }

    private Intent cameraIntent() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "mb2b_" + System.currentTimeMillis() + ".jpg");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraUri == null) return null;
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return cam;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK || fileCallback == null) return;
        Uri[] out = null;
        boolean usedCamera = false;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                out = new Uri[]{cameraUri};
                usedCamera = true;
            }
        }
        if (!usedCamera && cameraUri != null) {
            try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
        }
        cameraUri = null;
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    static boolean isOurs(Uri u) {
        String h = u.getHost();
        return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
                && h != null && (h.equals("mb2b.in") || h.endsWith(".mb2b.in"));
    }

    /** true = we handled it outside the app. */
    private boolean handle(Uri u) {
        if (isOurs(u)) return false;                          // our pages stay inside the app
        try {
            Intent i = "intent".equals(u.getScheme())
                    ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, u);       // phone call, UPI apps, other sites
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private String offlinePage() {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:60px 24px;color:#1c2433'>"
                + "<h1 style='color:#16307a'>MB<span style='color:#e8a400'>2</span>B</h1><p><b>No internet connection</b></p>"
                + "<p>Check mobile data or Wi-Fi.</p>"
                + "<a href='" + START + "' style='display:inline-block;margin-top:14px;padding:12px 22px;background:#16307a;color:#fff;"
                + "border-radius:10px;text-decoration:none'>Try again</a></body></html>";
    }

    /** A tap on the MB2B bar (or a link) while the app is open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = intent.getData();
        if (u == null || !isOurs(u)) return;
        if ("search".equals(u.getFragment())) {   // Search: put the cursor in the search box
            web.evaluateJavascript("(function(){var q=document.getElementById('q');if(!q)return 0;window.scrollTo(0,0);q.focus();return 1;})()",
                    r -> { if ("1".equals(r)) showKeyboard(); else { wantSearch = true; web.loadUrl(SITE + "/#search"); } });
            return;
        }
        web.loadUrl(u.toString());
    }

    private void showKeyboard() {
        web.requestFocus();
        web.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) { }
        }, 300);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ---------------- the MB2B bar in the notification panel ---------------- */

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    /** The login cookie of mb2b.in, kept for the background check (read on the main thread, after a page has loaded). */
    static void saveCookie(Context ctx) {
        try {
            String ck = CookieManager.getInstance().getCookie(SITE);
            if (ck != null) prefs(ctx).edit().putString("cookie", ck).apply();
        } catch (Throwable ignored) { }
    }

    static boolean canNotify(Context ctx) {
        return Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    static void channels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel bar = new NotificationChannel(CH_BAR, "MB2B bar", NotificationManager.IMPORTANCE_LOW);
        bar.setDescription("Quick buttons for Inbox, Buy Leads and Search");
        bar.setShowBadge(false);
        bar.setSound(null, null);
        nm.createNotificationChannel(bar);
        NotificationChannel msg = new NotificationChannel(CH_MSG, "New messages", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("When a buyer or seller writes to you on MB2B");
        nm.createNotificationChannel(msg);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context ctx, String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, ch) : new Notification.Builder(ctx);
    }

    /** A white "M" for the status bar (Android shows small icons in one colour). */
    static Icon smallIcon() {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(86);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText("M", 48, 48 - (fm.ascent + fm.descent) / 2, p);
        return Icon.createWithBitmap(b);
    }

    static Bitmap logo(Context ctx) {
        try { return BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher); } catch (Throwable e) { return null; }
    }

    /** Opens the app at a page of mb2b.in. */
    static PendingIntent open(Context ctx, String path, int code) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(SITE + path), ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification.Action action(Context ctx, int icon, String title, String path, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, open(ctx, path, code)).build();
    }

    /** The bar that stays in the notification panel: the MB2B logo, new messages, and three quick buttons. */
    static void showBar(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !canNotify(ctx)) return;
            channels(nm);
            SharedPreferences p = prefs(ctx);
            int n = p.getInt("unread", 0), leads = p.getInt("leads", 0);
            String role = p.getString("role", "");
            boolean seller = "seller".equals(role) || "owner".equals(role);
            String inbox = seller ? "/inbox" : "/my-inquiries";
            StringBuilder t = new StringBuilder();
            if (n > 0) t.append(n).append(n == 1 ? " new message" : " new messages");
            if (seller && leads > 0) t.append(t.length() > 0 ? " · " : "").append(leads).append(leads == 1 ? " new buyer lead" : " new buyer leads");
            if (t.length() == 0) t.append(seller ? "Inbox, Buy Leads and Search, one tap away" : "Inbox, Saved and Search, one tap away");
            Notification.Builder b = builder(ctx, CH_BAR)
                    .setSmallIcon(smallIcon())
                    .setContentTitle("MB2B")
                    .setContentText(t.toString())
                    .setContentIntent(open(ctx, "/", 10))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setColor(0xFF16307A)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .addAction(action(ctx, android.R.drawable.ic_dialog_email, n > 0 ? "Inbox (" + n + ")" : "Inbox", inbox, 11))
                    .addAction(seller ? action(ctx, android.R.drawable.ic_menu_agenda, leads > 0 ? "Buy Leads (" + leads + ")" : "Buy Leads", "/buy-leads", 12)
                                      : action(ctx, android.R.drawable.btn_star, "Saved", "/fav", 12))
                    .addAction(action(ctx, android.R.drawable.ic_menu_search, "Search", "/#search", 13));
            Bitmap lg = logo(ctx);
            if (lg != null) b.setLargeIcon(lg);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
            nm.notify(ID_BAR, b.build());
        } catch (Throwable ignored) { }   // the app must never stop because of the bar
    }

    /** "You have 3 new messages" (when the 15-minute check finds more than before). */
    static void notifyNew(Context ctx, int n, String role) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !canNotify(ctx)) return;
            channels(nm);
            boolean seller = "seller".equals(role) || "owner".equals(role);
            Notification.Builder b = builder(ctx, CH_MSG)
                    .setSmallIcon(smallIcon())
                    .setContentTitle("New message on MB2B")
                    .setContentText(n == 1 ? "You have 1 new message. Tap to open." : "You have " + n + " new messages. Tap to open.")
                    .setContentIntent(open(ctx, seller ? "/inbox" : "/my-inquiries", 20))
                    .setAutoCancel(true)
                    .setColor(0xFF16307A);
            Bitmap lg = logo(ctx);
            if (lg != null) b.setLargeIcon(lg);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
            nm.notify(ID_MSG, b.build());
        } catch (Throwable ignored) { }
    }

    /** window.MB2BApp in the page: the page tells the app how many messages are new. */
    static class Bridge {
        private final Context ctx;
        Bridge(Context c) { ctx = c; }

        @JavascriptInterface
        public void setUnread(int n, String role) {
            try {
                int m = Math.max(0, n);
                prefs(ctx).edit().putInt("unread", m).putInt("seen", m).putString("role", role == null ? "" : role).apply();
                showBar(ctx);
            } catch (Throwable ignored) { }
        }

        /** Version 3: this phone's Firebase code ("" until Firebase is set up). */
        @JavascriptInterface
        public String pushToken() { return prefs(ctx).getString("fcm", ""); }

        /** Version 3: the phone says out loud when a new inquiry comes (on unless the user turns it off). */
        @JavascriptInterface
        public boolean speakOn() { return prefs(ctx).getBoolean("speak", true); }

        @JavascriptInterface
        public void setSpeak(boolean on) {
            prefs(ctx).edit().putBoolean("speak", on).apply();
            if (on) say(ctx, "test", 0);
        }
    }

    /* ---------------- version 3: instant notifications and the spoken alert ---------------- */

    /** What the phone says. Hindi when the phone has a Hindi voice, otherwise English. */
    static String[] words(String kind) {
        switch (kind == null ? "" : kind) {
            case "inq": return new String[]{"एम बी टू बी पर नया इंक्वायरी आया है। आप चेक कीजिए।", "New inquiry on M B 2 B. Please check."};
            case "lead": return new String[]{"एम बी टू बी पर नया बायर लीड आया है। आप चेक कीजिए।", "New buyer lead on M B 2 B. Please check."};
            case "msg": return new String[]{"एम बी टू बी पर नया मैसेज आया है।", "New message on M B 2 B."};
            case "test": return new String[]{"एम बी टू बी की आवाज़ चालू है।", "M B 2 B voice alerts are on."};
            default: return null;
        }
    }

    /** Says it out loud, after the notification sound. Silent when the phone is on silent, vibrate or Do Not Disturb. */
    static void say(Context ctx, String kind, long delayMs) {
        final String[] w = words(kind);
        if (w == null) return;
        final Context app = ctx.getApplicationContext();
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 23 && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return;
        } catch (Throwable ignored) { }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                final TextToSpeech[] h = new TextToSpeech[1];
                h[0] = new TextToSpeech(app, st -> {
                    TextToSpeech t = h[0];
                    try {
                        if (st != TextToSpeech.SUCCESS || t == null) { if (t != null) t.shutdown(); return; }
                        Locale hi = new Locale("hi", "IN");
                        boolean hindi = t.isLanguageAvailable(hi) >= TextToSpeech.LANG_AVAILABLE;
                        t.setLanguage(hindi ? hi : new Locale("en", "IN"));
                        t.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { }
                            @Override public void onDone(String id) { t.shutdown(); }
                            @SuppressWarnings("deprecation") @Override public void onError(String id) { t.shutdown(); }
                        });
                        if (t.speak(hindi ? w[0] : w[1], TextToSpeech.QUEUE_FLUSH, null, "mb2b") != TextToSpeech.SUCCESS) t.shutdown();
                    } catch (Throwable e) { try { if (t != null) t.shutdown(); } catch (Throwable ignored) { } }
                });
            } catch (Throwable ignored) { }
        }, delayMs);
    }

    /** One notification from mb2b.in (title, text, the page it opens). Each inquiry or chat gets its own. */
    static void notifyPush(Context ctx, String title, String body, String url) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !canNotify(ctx)) return;
            channels(nm);
            Uri u = url == null ? null : Uri.parse(url);
            String path = u != null && isOurs(u) ? (u.getEncodedPath() == null ? "/" : u.getEncodedPath()) + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery()) : "/";
            int id = 100 + Math.abs(path.hashCode() % 100000);
            String text = body == null ? "" : body;
            Notification.Builder b = builder(ctx, CH_MSG)
                    .setSmallIcon(smallIcon())
                    .setContentTitle(title == null || title.isEmpty() ? "MB2B" : title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open(ctx, path, id))
                    .setAutoCancel(true)
                    .setColor(0xFF16307A)
                    .setCategory(Notification.CATEGORY_MESSAGE);
            Bitmap lg = logo(ctx);
            if (lg != null) b.setLargeIcon(lg);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
            nm.notify(id, b.build());
        } catch (Throwable ignored) { }
    }

    /** Firebase brings each notification from mb2b.in at once, even when the app is closed. */
    public static class Fcm extends FirebaseMessagingService {
        @Override
        public void onNewToken(String token) {
            try { prefs(this).edit().putString("fcm", token).apply(); } catch (Throwable ignored) { }
        }

        @Override
        public void onMessageReceived(RemoteMessage m) {
            try {
                Context ctx = getApplicationContext();
                Map<String, String> d = m.getData();
                String kind = d.get("kind");
                notifyPush(ctx, d.get("title"), d.get("body"), d.get("url"));
                SharedPreferences p = prefs(ctx);
                SharedPreferences.Editor e = p.edit().putLong("fcm_at", System.currentTimeMillis());
                if ("lead".equals(kind)) e.putInt("leads", p.getInt("leads", 0) + 1);
                else if ("inq".equals(kind) || "msg".equals(kind)) e.putInt("unread", p.getInt("unread", 0) + 1).putInt("seen", p.getInt("seen", 0) + 1);   // the 15-minute check does not tell again
                e.apply();
                showBar(ctx);
                if (p.getBoolean("speak", true)) say(ctx, kind, 1500);
            } catch (Throwable ignored) { }
        }
    }

    /** Every 15 minutes (when Android allows): asks mb2b.in how many messages are new, updates the bar, tells about new ones. */
    public static class Poll extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            Context ctx = getApplicationContext();
            new Thread(() -> {
                try { check(ctx); } catch (Throwable ignored) { }
                jobFinished(params, false);
            }).start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) { return true; }

        static void schedule(Context ctx) {
            try {
                JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (js == null || js.getPendingJob(JOB) != null) return;
                js.schedule(new JobInfo.Builder(JOB, new ComponentName(ctx, Poll.class))
                        .setPeriodic(15 * 60 * 1000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build());
            } catch (Throwable ignored) { }
        }

        static void check(Context ctx) throws Exception {
            SharedPreferences p = prefs(ctx);
            String ck = p.getString("cookie", "");
            if (ck == null || ck.isEmpty()) return;   // not logged in yet
            HttpURLConnection c = (HttpURLConnection) new URL(SITE + "/api.php?a=me").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Cookie", ck);
            c.setRequestProperty("User-Agent", "MB2BApp/3.0");
            try {
                if (c.getResponseCode() != 200) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                }
                JSONObject u = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optJSONObject("user");
                if (u == null) {   // logged out: a plain bar
                    p.edit().putInt("unread", 0).putInt("leads", 0).putString("role", "").putInt("seen", 0).apply();
                    showBar(ctx);
                    return;
                }
                int msgs = Math.max(0, u.optInt("inbox_new", 0)) + Math.max(0, u.optInt("replies_new", 0));
                int leads = Math.max(0, u.optInt("leads_new", 0));
                String role = u.optString("role", "");
                int seen = p.getInt("seen", 0);
                p.edit().putInt("unread", msgs).putInt("leads", leads).putString("role", role).putInt("seen", msgs).apply();
                showBar(ctx);
                boolean live = System.currentTimeMillis() - p.getLong("fcm_at", 0) < 3L * 24 * 3600 * 1000;   // version 3: Firebase works on this phone
                if (msgs > seen && !live) notifyNew(ctx, msgs, role);
            } finally {
                c.disconnect();
            }
        }
    }

    /** After the phone restarts (or the app is updated): the bar and the 15-minute check come back by themselves. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            showBar(ctx);
            Poll.schedule(ctx);
        }
    }
}
