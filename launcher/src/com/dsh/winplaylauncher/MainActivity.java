package com.dsh.winplaylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WinPlay Launcher —— 小米平板 PC 引擎（com.xiaomi.winplay / Wine 10.5 + FEX）的极简启动器。
 *
 * 一个 Activity，无 AndroidX、无第三方依赖，全部 UI 用 framework 控件在代码里搭。
 * 所有真正的动作都交给 root shell 脚本（assets/launch.sh、assets/push.sh），
 * 参数走纯文本文件，避免任何引号/转义问题。
 */
public class MainActivity extends Activity {

    static final String DATA_DIR = "/storage/emulated/0/WinPlay";
    static final String APPS_JSON = DATA_DIR + "/apps.json";
    static final String WPZ_DIR = "/storage/emulated/0/wpz";
    static final String CMD_FILE = WPZ_DIR + "/cmd2.txt";
    static final String PREFIX = "/data/user/0/com.xiaomi.winplay/files/prefix";
    static final String ENGINE_PKG = "com.xiaomi.winplay";
    static final String ENGINE_ACT = "com.xiaomi.winplay/com.xiaomi.winplay.WineActivity";

    static final int REQ_PICK_EXE = 1001;
    /** 开发期临时开关：启动即跑一次 exec 诊断。 */
    static final boolean DIAG_ON_START = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<AppEntry>();

    private android.widget.GridLayout appsGrid;
    private int gridCellW = 0;
    private final StringBuilder logBuf = new StringBuilder();
    private final Map<String, Bitmap> iconCache = new HashMap<String, Bitmap>();
    private final Map<String, String> archCache = new HashMap<String, String>();
    private final Set<String> archPending = new HashSet<String>();
    private final Set<String> iconPending = new HashSet<String>();
    private ListView list;
    private TextView logView;
    private ScrollView logScroll;
    private TextView statusView;
    private TextView emptyView;
    private MiuixSwitch steamBox;
    private MiuixSwitch panelBox;
    static final String PREFS = "wp";
    static final String STAGE_ROOT = DATA_DIR + "/staging";
    private volatile boolean busy = false;

    // ------------------------------------------------------------------ model

    static class AppEntry {
        String name = "";
        String exe = "";
        String args = "";
    }

    static class Cmd {
        int code = -1;
        String out = "";
    }

    // ------------------------------------------------------------------- life

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // MIUIX 观感：浅色状态栏/导航栏 + 深色图标
        getWindow().setStatusBarColor(0xFFF2F3F7);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        appendLog("WinPlay Launcher 就绪");
        appendLog("apps.json = " + APPS_JSON);
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                fileLog("!!! 未捕获异常 @" + t.getName() + "\n" + android.util.Log.getStackTraceString(e));
                if (def != null) {
                    def.uncaughtException(t, e);
                }
            }
        });
        reloadApps();
        handleIntentExtras(getIntent());
        if (DIAG_ON_START) {
            diagnose();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // apps.json 可能被外部（adb / 文件管理器）改过：回到前台就重读一次，
        // 顺便把 wintask_apps.txt 重新生成，任务面板才不会漏应用。
        try { reloadApps(); } catch (Throwable ignored) {}
        refreshStatusLater();
        // 点击后引擎会把 WineActivity 抢到前台，本 App 会被冻结，轮询线程随之中断。
        // 回到前台时补一次结果查询，保证"日志里有交代"。
        final String s;
        final String l;
        synchronized (bridgeLock) {
            s = pendingSeq;
            l = pendingLabel;
        }
        if (s != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    finishBridgeIfReady(s, l);
                }
            }).start();
        }
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    /** 桌面快捷方式（auto_launch）与 adb 驱动的静默安装（install_path）。 */
    private void handleIntentExtras(final Intent it) {
        if (it == null) {
            return;
        }
        final String auto = it.getStringExtra("auto_launch");
        if (auto != null && !auto.isEmpty()) {
            appendLog("从快捷方式启动: " + auto);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    AppEntry hit = null;
                    for (AppEntry e : apps) {
                        if (e.name.equalsIgnoreCase(auto)) { hit = e; break; }
                    }
                    // MIUI 会把过长的 Shortcut 标签截断，这里做前缀/包含兜底匹配
                    if (hit == null) {
                        String a = norm(auto);
                        for (AppEntry e : apps) {
                            String n = norm(e.name);
                            if (n.startsWith(a) || a.startsWith(n) || n.contains(a) || a.contains(n)) { hit = e; break; }
                        }
                    }
                    if (hit != null) {
                        appendLog("快捷方式命中: " + hit.name);
                        launchApp(hit.name, hit.exe, hit.args);
                        return;
                    }
                    appendLog("! 找不到应用: " + auto);
                    toast("找不到 " + auto);
                }
            }, 600);
        }
        final String setPanel = it.getStringExtra("set_panel");
        if (setPanel != null && !setPanel.isEmpty()) {
            boolean on = "1".equals(setPanel) || "true".equalsIgnoreCase(setPanel);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("panel", on).apply();
            if (panelBox != null) {
                final boolean v = on;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        panelBox.setChecked(v);
                    }
                });
            }
            appendLog("set_panel=" + (on ? "1（开）" : "0（关）"));
        }

        if (it.getBooleanExtra("panel", false) || "1".equals(it.getStringExtra("panel"))) {
            appendLog("收到 panel=1，唤起 Wine 任务面板");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showPanel();
                }
            }, 400);
        }

        final String path = it.getStringExtra("install_path");
        if (path != null && !path.isEmpty()) {
            appendLog("install_path = " + path);
            File f = new File(path);
            if (!f.exists()) {
                appendLog("! 文件不存在");
                toast("文件不存在: " + path);
                return;
            }
            Uri u = Uri.fromFile(f);
            if (path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                importZip(u, f.getName());
            } else {
                String dir = sanitize(stripExt(f.getName()));
                importAndLaunch(u, sanitize(f.getName()), dir);
            }
        }
    }

    /** 快捷方式名称归一化：去空格/点/横线并小写，抵消 MIUI 截断与编码差异。 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '.' || c == '-' || c == '_') continue;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    // --------------------------------------------------------------------- ui

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
    private android.graphics.drawable.GradientDrawable card() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(18));
        return g;
    }

    private TextView pill(String text, int bg, int fg, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    /** 有自带图标就显示图标，没有则首字母占位（并异步去 PE 里抠一次）。 */
    private View appIcon(AppEntry e, int sizePx) {
        String key = iconKey(e.exe);
        Bitmap bm = iconCache.get(key);
        if (bm == null) {
            File cf = new File(iconDir(), key + ".png");
            if (cf.isFile()) {
                Bitmap b = BitmapFactory.decodeFile(cf.getAbsolutePath());
                if (b != null) {
                    bm = b;                 // 缓存里已经是「底板 + 图标 + 角标」的成品，不能再套一次
                    iconCache.put(key, bm);
                }
            }
        }
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return iv;
        }
        // 已经判定「没有图标」的不要每次渲染都重试（否则 renderRows → appIcon → 重试 → 死循环）
        if (!new File(iconDir(), key + ".none").isFile()) ensureIcon(e);

        // 首字母方块：右下角同样要挂架构角标
        FrameLayout box = new FrameLayout(this);
        box.addView(letterAvatar(e.name), new FrameLayout.LayoutParams(sizePx, sizePx));
        String arch = archOfApp(e);
        if (arch != null) {
            // 角标几何必须和位图里那套一致（PeIcon.round 的 14.5% / 0.40 上限 / 0.085 边距）
            TextView b = new TextView(this);
            b.setText(arch);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(0xFFFFFFFF);
            float ts = sizePx * 0.145f;
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts);
            int padX = Math.round(ts * 0.26f), padY = Math.round(ts * 0.12f);
            b.setPadding(padX, padY, padX, padY);
            b.setIncludeFontPadding(false);
            android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
            bgd.setColor(0xCC000000);
            bgd.setCornerRadius((ts + padY * 2f) * 0.34f);
            b.setBackground(bgd);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.BOTTOM);
            blp.rightMargin = Math.round(sizePx * 0.085f);
            blp.bottomMargin = Math.round(sizePx * 0.085f);
            box.addView(b, blp);
        }
        return box;
    }

    /** 用 root 把目标文件拷成自己能读的副本。 */
    private boolean copyForRead(String unix, File dst) {
        int uid = android.os.Process.myUid();
        root("cp '" + unix + "' '" + dst.getAbsolutePath() + "' && chown " + uid + ":" + uid
                + " '" + dst.getAbsolutePath() + "' && chmod 600 '" + dst.getAbsolutePath() + "'");
        return dst.isFile() && dst.length() > 0;
    }

    /** 应用的架构标签：PE 直接读；.bat 里挑第一个 C:\...\x.exe 再读。 */
    private String archOfApp(AppEntry e) {
        String key = iconKey(e.exe);
        String a = archCache.get(key);
        if (a != null) return a;
        if (archPending.contains(key)) return null;
        archPending.add(key);
        final AppEntry app = e;
        final String k = key;
        new Thread(new Runnable() {
            @Override public void run() {
                String found = null;
                File tmp = null;
                try {
                    tmp = new File(getFilesDir(), "arch_" + k + ".tmp");
                    String unix = exeToUnix(app.exe);
                    if (unix != null && copyForRead(unix, tmp)) {
                        found = PeIcon.archOf(tmp);
                        if (found == null && app.exe != null
                                && app.exe.toLowerCase(Locale.US).endsWith(".bat")) {
                            // bat 包装：从脚本里挑第一个 exe 路径
                            java.io.BufferedReader br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(new java.io.FileInputStream(tmp)));
                            String line;
                            while ((line = br.readLine()) != null) {
                                java.util.regex.Matcher m = java.util.regex.Pattern
                                        .compile("[A-Za-z]:\\\\[^\"\\r\\n]*(?i:\\.exe)").matcher(line);
                                if (m.find()) {
                                    String target = m.group();
                                    File t2 = new File(getFilesDir(), "arch2_" + k + ".tmp");
                                    String u2 = exeToUnix(target);
                                    if (u2 != null && copyForRead(u2, t2)) found = PeIcon.archOf(t2);
                                    if (t2.isFile()) t2.delete();
                                    break;
                                }
                            }
                            br.close();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (tmp != null && tmp.isFile()) tmp.delete();
                    synchronized (archPending) { archPending.remove(k); }
                }
                if (found != null) {
                    final String fa = found;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            archCache.put(k, fa);
                            renderRows();
                        }
                    });
                }
            }
        }, "wp-arch").start();
        return null;
    }

    private TextView letterAvatar(String name) {
        TextView av = new TextView(this);
        String nm = (name == null || name.isEmpty()) ? "?" : name;
        av.setText(nm.substring(0, 1).toUpperCase(Locale.US));
        av.setTextSize(17);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        av.setTextColor(0xFFFFFFFF);
        av.setGravity(Gravity.CENTER);
        int h = Math.abs(nm.hashCode());
        int[] palette = {0xFF3482FF, 0xFF00B578, 0xFFFF8A00, 0xFFE8455C,
                         0xFF7A5CFF, 0xFF00A6C0, 0xFFB25CFF, 0xFF2FA84F};
        android.graphics.drawable.GradientDrawable ag = new android.graphics.drawable.GradientDrawable();
        ag.setColor(palette[h % palette.length]);
        ag.setCornerRadius(dp(12));
        av.setBackground(ag);
        return av;
    }

    private File iconDir() {
        File d = new File(getFilesDir(), "icons");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private String iconKey(String exe) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(exe.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(exe.hashCode());
        }
    }

    /** 后台：root 拷一份 exe 到 App 私有目录 → 解析 PE 图标 → 存 PNG → 刷新列表。 */
    private void ensureIcon(final AppEntry e) {
        final String key = iconKey(e.exe);
        if (new File(iconDir(), key + ".none").isFile()) return;
        synchronized (iconPending) {
            if (iconPending.contains(key)) return;
            iconPending.add(key);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                File tmp = new File(getFilesDir(), "pe_" + key + ".tmp");
                final boolean[] ok = {false};
                try {
                    String unix = exeToUnix(e.exe);
                    if (unix == null) return;
                    int uid = android.os.Process.myUid();
                    Cmd r = root("cp '" + unix + "' '" + tmp.getAbsolutePath() + "' && chown "
                            + uid + ":" + uid + " '" + tmp.getAbsolutePath() + "' && chmod 600 '"
                            + tmp.getAbsolutePath() + "'");
                    if (!tmp.isFile() || tmp.length() == 0) {
                        appendLog("图标: 取不到 " + unix + " (" + (r.out == null ? "" : r.out.trim()) + ")");
                        return;
                    }
                    String arch = PeIcon.archOf(tmp);
                    Bitmap raw = PeIcon.load(tmp);
                    if (raw != null) {
                        // 按 2 倍（96dp）出图，屏幕上再缩到 48dp —— 图标和角标都更清楚
                        Bitmap round = PeIcon.round(raw, dp(96), dp(28), arch);
                        File out = new File(iconDir(), key + ".png");
                        FileOutputStream fos = new FileOutputStream(out);
                        round.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.close();
                        synchronized (iconCache) { iconCache.put(key, round); }
                        ok[0] = true;
                        appendLog("图标: " + e.name + " 已提取 (" + raw.getWidth() + "x" + raw.getHeight()
                                    + (arch == null ? "" : ", " + arch) + ")");
                    } else {
                        appendLog("图标: " + e.name + " 失败(" + PeIcon.lastError + ")，用首字母");
                        // 写个空标记，避免每次渲染都重试
                        new File(iconDir(), key + ".none").createNewFile();
                    }
                } catch (Throwable t) {
                    appendLog("图标失败: " + e.name + " " + t);
                } finally {
                    if (tmp.isFile()) tmp.delete();
                    synchronized (iconPending) { iconPending.remove(key); }
                    if (ok[0]) {
                        ui.post(new Runnable() {
                            @Override public void run() { renderRows(); }
                        });
                    }
                }
            }
        }, "wp-icon").start();
    }

    /** C:\apps\x\x.exe → prefix/drive_c/apps/x/x.exe；其它盘符返回 null。 */
    private String exeToUnix(String exe) {
        if (exe == null) return null;
        String p = exe.trim();
        if (p.length() < 3 || p.charAt(1) != ':') return null;
        if (Character.toUpperCase(p.charAt(0)) != 'C') return null;
        String rest = p.substring(2).replace('\\', '/');
        if (!rest.startsWith("/")) rest = "/" + rest;
        return PREFIX + "/drive_c" + rest;
    }

    /** 一行应用：圆角方块头像 + 名称/路径 + 箭头，整行白色圆角卡。 */
    /** 网格单元：图标在上、名称在下，MIUIX 的 app 图标格。 */
    /** 网格单元：图标在上、名称在下（MIUIX 的 app 图标格）。 */
    private View appCell(final AppEntry e) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(10), dp(4), dp(10));

        android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
        rg.setColor(0x00FFFFFF);
        rg.setCornerRadius(dp(16));
        cell.setBackground(rg);

        View icon = appIcon(e, dp(48));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView nm = new TextView(this);
        nm.setText(e.name);
        nm.setTextSize(12);
        nm.setTextColor(0xFF0D0D0D);
        nm.setGravity(Gravity.CENTER);
        nm.setMaxLines(2);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(7);
        cell.addView(nm, nlp);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchApp(e.name, e.exe, e.args); }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { confirmDelete(e); return true; }
        });
        return cell;
    }

    private void renderRows() {
        if (appsGrid == null) return;
        appsGrid.removeAllViews();
        for (int i = 0; i < apps.size(); i++) {
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED));
            lp.width = gridCellW > 0 ? gridCellW : ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            appsGrid.addView(appCell(apps.get(i)), lp);
        }
    }

    private LinearLayout switchRow(String title, String sub, MiuixSwitch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(16);
        t1.setTextColor(0xFF0D0D0D);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(12);
        t2.setTextColor(0xFF8A8A8E);
        t2.setPadding(0, dp(2), 0, 0);
        col.addView(t1);
        col.addView(t2);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(26));
        slp.leftMargin = dp(12);
        row.addView(sw, slp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        return row;
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        // 两种方向都左右分栏：左 0.618 应用网格 / 右 0.382 其余
        final boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setBackgroundColor(0xFFF2F3F7);
        page.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                Insets b = ins.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(10), b.top + dp(6), dp(10), b.bottom + dp(8));
                return ins;
            }
        });

        // ================= 左栏：应用（0.618） =================
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setClipToPadding(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView leftHead = new TextView(this);
        leftHead.setText("应用");
        leftHead.setTextSize(20);
        leftHead.setTypeface(Typeface.DEFAULT_BOLD);
        leftHead.setTextColor(0xFF0D0D0D);
        leftHead.setPadding(dp(6), dp(2), dp(6), dp(8));
        left.addView(leftHead);

        appsGrid = new android.widget.GridLayout(this);
        appsGrid.setColumnCount(4);
        left.addView(appsGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyView = new TextView(this);
        emptyView.setText("暂无应用\n\n点右侧「安装 / 运行」添加 exe 或 zip");
        emptyView.setTextSize(13);
        emptyView.setTextColor(0xFF8A8A8E);
        emptyView.setPadding(dp(12), dp(28), dp(12), dp(28));
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setBackground(card());
        emptyView.setVisibility(View.GONE);
        LinearLayout.LayoutParams evp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        evp.topMargin = dp(6);
        left.addView(emptyView, evp);

        page.addView(leftScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.618f));

        // ================= 右栏：标题 / 状态 / 操作 / 选项 / 日志 =================
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setClipToPadding(false);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(portrait ? 4 : 8), 0, dp(2), 0);
        rightScroll.addView(right, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("PC 引擎");
        title.setTextSize(portrait ? 22 : 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF0D0D0D);
        right.addView(title);

        TextView sub = new TextView(this);
        sub.setText("WinPlay 启动器 · ARM64");
        sub.setTextSize(11);
        sub.setTextColor(0xFF8A8A8E);
        sub.setPadding(0, dp(2), 0, dp(10));
        right.addView(sub);

        TextView runAny = pill("＋  安装 / 运行", 0xFF3482FF, 0xFFFFFFFF, 14);
        runAny.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickExe(); }
        });
        right.addView(runAny, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout a2 = new LinearLayout(this);
        a2.setOrientation(LinearLayout.HORIZONTAL);
        a2.setPadding(0, dp(8), 0, dp(10));

        TextView panelBtn = pill("▣ 面板", 0x1A3482FF, 0xFF3482FF, 12);
        panelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(); }
        });
        a2.addView(panelBtn);

        TextView reload = pill("⟳ 重载", 0x0F000000, 0xFF3C3C43, 12);
        reload.setTypeface(Typeface.DEFAULT);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(6);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reloadApps(); }
        });
        a2.addView(reload, rlp);

        TextView diag = pill("诊断", 0x0F000000, 0xFF3C3C43, 12);
        diag.setTypeface(Typeface.DEFAULT);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(6);
        diag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { diagnose(); }
        });
        a2.addView(diag, dlp);
        right.addView(a2);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(card());
        statusView = new TextView(this);
        statusView.setTextSize(11);
        statusView.setTextColor(0xFF6B6B70);
        statusView.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusView.setText("检查引擎状态中…");
        statusCard.addView(statusView);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scp.bottomMargin = dp(8);
        right.addView(statusCard, scp);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout optCard = new LinearLayout(this);
        optCard.setOrientation(LinearLayout.VERTICAL);
        optCard.setBackground(card());

        steamBox = new MiuixSwitch(this);
        steamBox.setChecked(sp.getBoolean("steam", false));
        steamBox.setOnCheckedChange(new MiuixSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(MiuixSwitch b, boolean v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("steam", v).apply();
                syncNoSteamFlag(v);
                appendLog(v ? "Steam：开（官方入口起的 Steam 也不再拦截）"
                            : "Steam：关（已写入 nosteam.flag，官方入口起的 Steam 会被守卫杀掉）");
            }
        });
        syncNoSteamFlag(steamBox.isChecked());
        optCard.addView(switchRow("附带 Steam", "关闭时只起 Windows 桌面", steamBox));

        View div = new View(this);
        div.setBackgroundColor(0xFFEDEDF0);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        dlp2.leftMargin = dp(16); dlp2.rightMargin = dp(16);
        optCard.addView(div, dlp2);

        panelBox = new MiuixSwitch(this);
        panelBox.setChecked(sp.getBoolean("panel", true));
        panelBox.setOnCheckedChange(new MiuixSwitch.OnCheckedChange() {
            @Override public void onCheckedChanged(MiuixSwitch b, boolean v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("panel", v).apply();
                appendLog(v ? "任务面板：开（启动应用时自动拉起）" : "任务面板：关（启动应用时不再拉起）");
                if (!v) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            Cmd r = root("killall -9 wintask.exe 2>/dev/null; echo done");
                            appendLog("关闭任务面板: " + (r.out == null ? "" : r.out.trim()));
                        }
                    }, "wp-paneloff").start();
                }
            }
        });
        optCard.addView(switchRow("任务面板", "wintask：窗口列表 / 找回最小化", panelBox));

        LinearLayout.LayoutParams ocp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ocp.bottomMargin = dp(8);
        right.addView(optCard, ocp);

        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable lg = new android.graphics.drawable.GradientDrawable();
        lg.setColor(0xFF1B1B1D);
        lg.setCornerRadius(dp(16));
        logCard.setBackground(lg);
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(12);
        logTitle.setTextColor(0xFFB8B8BE);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(12);
        clear.setTextColor(0xFF7FA8FF);
        clear.setPadding(dp(6), dp(3), 0, dp(3));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logView.setText(""); }
        });
        logHead.addView(clear);
        logCard.addView(logHead);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF9BE89B);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(5), 0, 0);
        logScroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        right.addView(logCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(rightScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.382f));

        leftScroll.post(new Runnable() {
            @Override public void run() {
                int w = leftScroll.getWidth();
                if (w <= 0) return;
                int c = Math.max(3, w / dp(110));
                gridCellW = (w - dp(6) * c) / c;
                if (appsGrid != null && appsGrid.getColumnCount() != c) appsGrid.setColumnCount(c);
                renderRows();
            }
        });
        if (logView != null) logView.setText(logBuf);
        return page;
    }

    // ------------------------------------------------------------------- log

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    /** 日志同时落盘，方便 adb 侧排查（屏幕/焦点不可靠时尤其有用）。 */
    private void fileLog(String line) {
        try {
            File f = new File(getFilesDir(), "launcher.log");
            if (f.length() > 512 * 1024) {
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(("[" + now() + "] " + line + "\n").getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        setContentView(buildUi());
        reloadApps();
    }

    private void appendLog(final String line) {
        fileLog(line);
        final String stamped = "[" + now() + "] " + line + "\n";
        if (logBuf.length() < 512 * 1024) logBuf.append(stamped);
        ui.post(new Runnable() {
            @Override
            public void run() {
                logView.append(stamped);
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(s);
            }
        });
    }

    // ------------------------------------------------------------- root utils

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 同步执行 root 命令，返回 exit code + 合并后的 stdout/stderr。 */
    private Cmd root(String cmd) {
        Cmd r = new Cmd();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-M", "-c", cmd).redirectErrorStream(true).start();
            r.out = readStream(p.getInputStream());
            r.code = p.waitFor();
        } catch (Exception e) {
            r.out = "EXC: " + e;
            r.code = -1;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return r;
    }

    private void writeText(File f, String s) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(s.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            appendLog("! write " + f.getName() + ": " + e);
        }
    }

    private String readText(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            String s = readStream(fis);
            fis.close();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 assets 里的脚本同步到 filesDir，保证升级 APK 后脚本也跟着更新。 */
    private File materializeAsset(String name) {
        File out = new File(getFilesDir(), name);
        try {
            InputStream in = getAssets().open(name);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.close();
            in.close();
        } catch (Exception e) {
            appendLog("! asset " + name + ": " + e);
        }
        return out;
    }

    // ---------------------------------------------------------------- scripts

    // ------------------------------------------------------ root / shell 通道

    private static final String WPZ = "/storage/emulated/0/wpz";
    private static final String BRIDGE_STATUS = WPZ + "/status.txt";

    private final Object bridgeLock = new Object();
    private String pendingSeq = null;
    private String pendingLabel = null;

    private static final String REQ = WPZ + "/launch_req.txt";
    private static final String RES = WPZ + "/launch_res.txt";
    private static final String ALIVE = WPZ + "/.bridge_alive";
    /** null=还没探测过；true=su 可用；false=su 不可用（本 ROM 的 App 命名空间里没有 su） */
    private static Boolean suOk = null;

    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    /** su 是否可用（结果缓存）。 */
    private boolean rootOk() {
        if (suOk == null) {
            Cmd r = root("id");
            suOk = Boolean.valueOf(r.code == 0 && r.out != null && r.out.contains("uid=0"));
            appendLog("root 检查: su " + (suOk.booleanValue() ? "可用" : "不可用（" + shortMsg(r.out) + "）"));
        }
        return suOk.booleanValue();
    }

    private static String shortMsg(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    /** 网桥守护进程是否在跑（心跳文件 5 秒内更新过）。 */
    private boolean bridgeAlive() {
        File f = new File(ALIVE);
        return f.exists() && (System.currentTimeMillis() - f.lastModified()) < 20000;
    }

    /**
     * 执行 assets 里的脚本。
     * 1) su 可用 → su -c "sh 脚本 argv..."
     * 2) 否则 → 把参数交给 shell 守护网桥（wp_bridge.sh），读回结果
     */
    private void execScript(final String assetName, final String[] argv, final String label) {
        final File f = materializeAsset(assetName);
        appendLog("$ sh " + f.getName() + " " + join(argv));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (rootOk()) {
                        StringBuilder sb = new StringBuilder("sh ").append(f.getAbsolutePath());
                        for (String a : argv) {
                            sb.append(' ').append(a);
                        }
                        Cmd r = root(sb.toString());
                        logLines(r.out);
                        appendLog("— exit=" + r.code);
                    } else if (!bridgeAlive()) {
                        appendLog("!! su 不可用，且命令网桥没在运行 —— 无法执行");
                        appendLog("   让网桥起来（电脑上跑一次，重启后需再跑）：");
                        appendLog("   adb shell sh /data/local/tmp/wp/start.sh");
                        toast("需要 root 或网桥");
                    } else if ("wp_launch.sh".equals(assetName)) {
                        launchViaBridge(argv, label);
                    } else {
                        appendLog("!! 该操作需要 root（网桥不能做）：" + label);
                        toast("该操作需要 root");
                    }
                } catch (Throwable t) {
                    appendLog("!! 执行异常: " + t);
                } finally {
                    busy = false;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshStatusLater();
                        }
                    });
                }
            }
        }, "wp-script").start();
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private void logLines(String out) {
        if (out == null) {
            return;
        }
        for (String line : out.split("\n")) {
            if (!line.isEmpty()) {
                appendLog(line);
            }
        }
    }

    /** 走 shell 网桥：写请求文件 → 轮询结果文件。 */
    private void launchViaBridge(String[] argv, String label) {
        final String seq = String.valueOf(System.currentTimeMillis());
        String so = argv.length > 2 ? argv[2] : "1";
        writeText(new File(REQ), seq + "|" + so + "|" + argv[0] + "|" + argv[1]);
        synchronized (bridgeLock) {
            pendingSeq = seq;
            pendingLabel = label;
        }
        appendLog("… 已交给网桥，等结果（最长 200 秒；切后台会暂停，回前台自动续查）");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                awaitBridge(seq, label);
            }
        });
        t.start();
    }

    private void awaitBridge(String seq, String label) {
        long deadline = System.currentTimeMillis() + 200000L;
        while (System.currentTimeMillis() < deadline) {
            if (finishBridgeIfReady(seq, label)) {
                return;
            }
            if (!bridgeAlive()) {
                synchronized (bridgeLock) {
                    pendingSeq = null;
                }
                appendLog("!! 网桥心跳消失（被系统杀了？），重新执行 install_bridge.sh");
                toast("网桥已停止");
                return;
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
            }
        }
        synchronized (bridgeLock) {
            pendingSeq = null;
        }
        appendLog("!! 网桥 200 秒未返回结果，看 " + RES);
        toast("网桥超时，看日志");
    }

    /** 结果文件就绪则汇报一次（多线程/前后台都可能调用，用 pendingSeq 去重）。 */
    private boolean finishBridgeIfReady(String seq, String label) {
        String txt = readText(new File(RES));
        if (txt == null || !txt.startsWith("seq=") || txt.indexOf(seq) < 0) {
            return false;
        }
        synchronized (bridgeLock) {
            if (pendingSeq == null) {
                return true;
            }
            pendingSeq = null;
        }
        logLines(txt);
        appendLog("— 网桥完成");
        toast("已启动 " + label);
        return true;
    }

    // ------------------------------------------------------------------- apps

    private void reloadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> loaded = new ArrayList<AppEntry>();
                String json = null;

                File f = new File(APPS_JSON);
                if (f.exists() && f.canRead()) {
                    json = readText(f);
                }
                if (json == null || json.trim().isEmpty()) {
                    Cmd r = root("cat " + APPS_JSON + " 2>/dev/null");
                    if (r.code == 0 && r.out != null && !r.out.trim().isEmpty()) {
                        json = r.out;
                    }
                }

                if (json == null || json.trim().isEmpty()) {
                    appendLog("apps.json 读不到（不存在或没有权限）");
                } else {
                    int a = json.indexOf('[');
                    int b = json.lastIndexOf(']');
                    if (a >= 0 && b > a) {
                        try {
                            JSONArray arr = new JSONArray(json.substring(a, b + 1));
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) {
                                    continue;
                                }
                                AppEntry e = new AppEntry();
                                e.name = o.optString("name", "").trim();
                                e.exe = o.optString("exe", "").trim();
                                e.args = o.optString("args", "").trim();
                                if (e.exe.isEmpty()) {
                                    continue;
                                }
                                if (e.name.isEmpty()) {
                                    e.name = e.exe;
                                }
                                loaded.add(e);
                            }
                            appendLog("apps.json: " + loaded.size() + " 个条目");
                        } catch (Exception ex) {
                            appendLog("! JSON 解析失败: " + ex.getMessage());
                        }
                    } else {
                        appendLog("! apps.json 里找不到 JSON 数组");
                    }
                }

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        apps.clear();
                        apps.addAll(loaded);
                        renderRows();
                        emptyView.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                        publishDynamicShortcuts();
                        refreshStatusLater();
                        syncPanelList(loaded);
                    }
                });
            }
        }, "wp-load").start();
    }

    private void saveAppsThen(final List<AppEntry> newList, final Runnable after) {
        JSONArray arr = new JSONArray();
        for (AppEntry e : newList) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", e.name);
                o.put("exe", e.exe);
                o.put("args", e.args);
                o.put("icon", "");
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        final File tmp = new File(getFilesDir(), "apps.json.new");
        writeText(tmp, arr.toString());

        // 同步一份给 Wine 内的「任务面板」(wintask.exe)：TSV = name \t exe \t args
        StringBuilder tsv = new StringBuilder();
        for (AppEntry e : newList) {
            String args = e.args == null ? "" : e.args.replace(';', ' ').replace('\t', ' ');
            tsv.append(e.name.replace('\t', ' ')).append('\t')
               .append(e.exe).append('\t').append(args).append('\n');
        }
        final File tmpTsv = new File(getFilesDir(), "wintask_apps.new");
        writeText(tmpTsv, tsv.toString());

        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = readText(tmp);
                // 1) 有「所有文件访问」权限时可以直接写（不需要 su）
                boolean ok = body != null && writeAppsDirect(body);
                if (ok) {
                    appendLog("save apps.json: 直接写入成功（无需 root）");
                } else {
                    // 2) 退化成 root 路径
                    Cmd r = root("mkdir -p " + DATA_DIR
                            + " && cp -f " + tmp.getAbsolutePath() + " " + APPS_JSON
                            + " && chmod 666 " + APPS_JSON);
                    ok = r.code == 0;
                    appendLog("save apps.json: root 路径 exit=" + r.code + " " + r.out.trim());
                }
                if (!ok) {
                    toast("写入 apps.json 失败：在电脑上改 /storage/emulated/0/WinPlay/apps.json");
                }
                writePanelList(tmpTsv);
                if (after != null) {
                    ui.post(after);
                }
                reloadApps();
            }
        }, "wp-save").start();
    }

    /** 用当前列表生成 TSV（启动时也同步一次，避免用户没改列表就没有清单文件）。 */
    private void syncPanelList(final List<AppEntry> list) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder tsv = new StringBuilder();
                for (AppEntry e : list) {
                    String args = e.args == null ? "" : e.args.replace(';', ' ').replace('\t', ' ');
                    tsv.append(e.name.replace('\t', ' ')).append('\t')
                       .append(e.exe).append('\t').append(args).append('\n');
                }
                File tmp = new File(getFilesDir(), "wintask_apps.new");
                writeText(tmp, tsv.toString());
                writePanelList(tmp);
            }
        }, "wp-panel").start();
    }

    /** 把应用清单同步成 TSV，供 Wine 内的 wintask.exe 读取（wp_launch.sh 会拷进 C:\windows）。 */
    private void writePanelList(File src) {
        String body = readText(src);
        if (body == null) {
            return;
        }
        final String dstPath = DATA_DIR + "/wintask_apps.txt";
        try {
            FileOutputStream out = new FileOutputStream(dstPath);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            appendLog("wintask_apps.txt 已更新");
            return;
        } catch (Exception ex) {
            appendLog("wintask_apps.txt 直接写失败: " + ex);
        }
        Cmd r = root("cp -f " + src.getAbsolutePath() + " " + dstPath + " && chmod 666 " + dstPath);
        appendLog("wintask_apps.txt root 路径 exit=" + r.code);
    }

    /** 直接写 apps.json（带「所有文件访问」权限时可行，不依赖 su）。 */
    private boolean writeAppsDirect(String body) {
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File tmp = new File(dir, "apps.json.tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(body.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
            fos.close();
            File dst = new File(APPS_JSON);
            if (tmp.renameTo(dst)) {
                return true;
            }
            // rename 不行就退化成覆盖写
            FileOutputStream out = new FileOutputStream(dst);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.delete();
            return true;
        } catch (Exception ex) {
            appendLog("直接写失败: " + ex);
            return false;
        }
    }

    private void confirmDelete(final AppEntry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除条目")
                .setMessage("从 apps.json 移除：\n\n" + e.name + "\n" + e.exe
                        + "\n\n（只删列表项，不会删 prefix 里的文件）")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<AppEntry> next = new ArrayList<AppEntry>();
                        for (AppEntry x : apps) {
                            if (!(x.exe.equals(e.exe) && x.name.equals(e.name))) {
                                next.add(x);
                            }
                        }
                        saveAppsThen(next, null);
                        toast("已删除 " + e.name);
                    }
                })
                .show();
    }

    // ----------------------------------------------------------------- launch

    private void launchApp(String name, String exe, String args) {
        if (busy) {
            toast("上一次还在执行，稍等");
            return;
        }
        busy = true;
        appendLog("");
        appendLog("▶ " + name + "  [" + exe + "]");
        toast("正在启动 " + name);
        syncNoSteamFlag(steamBox != null && steamBox.isChecked());
        execScript("wp_launch.sh",
                new String[]{b64(exe), args == null || args.isEmpty() ? "-" : b64(args),
                        "0",   // 熄屏只是调试期的手段，正式使用一律不动屏幕
                        steamBox != null && steamBox.isChecked() ? "1" : "0",
                        panelBox != null && panelBox.isChecked() ? "1" : "0"},
                name);
    }

    /**
     * nosteam.flag：存在时 wp_nosteam.sh 会把引擎自己拉起的 Steam 立刻杀掉。
     * 这样即使用户点的是官方「PC 引擎 / 游戏中心」入口（它走 winplay://opensteam，
     * 必然拉起 steam.exe -gamepadui），屏幕上也不会出现 Steam。
     */
    private void syncNoSteamFlag(boolean steamOn) {
        try {
            File f = new File(DATA_DIR, "nosteam.flag");
            if (steamOn) {
                if (f.exists()) f.delete();
            } else {
                File d = new File(DATA_DIR);
                if (!d.exists()) d.mkdirs();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write('1'); fo.close();
                f.setReadable(true, false); f.setWritable(true, false);
            }
        } catch (Throwable t) {
            appendLog("! nosteam.flag 写入失败: " + t);
        }
    }

    /** 唤起 Wine 里的任务面板（wintask.exe）。面板被关掉/杀掉后用它叫回来。 */
    private void showPanel() {
        if (busy) {
            toast("上一次还在执行，稍等");
            return;
        }
        busy = true;
        appendLog("");
        appendLog("▶ 任务面板（Wine 内 wintask.exe）");
        execScript("wp_launch.sh",
                new String[]{b64("C:\\windows\\wt.bat"), "-", "0",
                        steamBox != null && steamBox.isChecked() ? "1" : "0", "1"},
                "任务面板");
    }

    // ------------------------------------------------------------- SAF import

    private void pickExe() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_EXE);
        } catch (Exception e) {
            toast("打不开文件选择器: " + e.getMessage());
            appendLog("! ACTION_OPEN_DOCUMENT: " + e);
        }
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String p = uri.getLastPathSegment();
            if (p != null) {
                int s = p.lastIndexOf('/');
                name = (s >= 0 ? p.substring(s + 1) : p);
            }
        }
        return name == null ? "picked.exe" : name;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String r = sb.toString();
        return r.isEmpty() ? "app" : r;
    }

    private static String stripExt(String f) {
        int d = f.lastIndexOf('.');
        return d > 0 ? f.substring(0, d) : f;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_EXE || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final String raw = queryDisplayName(uri);
        final String display = sanitize(raw);
        final String dirName = sanitize(stripExt(raw));
        if (raw.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            new AlertDialog.Builder(this)
                    .setTitle("安装 zip 便携版")
                    .setMessage("将解压并安装到：\nC:\\apps\\" + dirName + "\\\n\n"
                            + "会自动挑最大的 exe 做主程序，再注册到列表并可添加到桌面。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) { importZip(uri, raw); }
                    }).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("exe 需要先进 prefix")
                .setMessage("将要执行：\n\nC:\\apps\\" + dirName + "\\" + display
                        + "\n\nWine 只能运行 prefix 里的文件，所以 App 会用 root 把选中的文件"
                        + " 拷到：\n" + PREFIX + "/drive_c/apps/" + dirName + "/\n\n"
                        + "注意：只拷这一个 exe。若程序依赖同目录的 DLL/资源，"
                        + "请自己把整个文件夹放进 prefix 的 drive_c\\apps\\ 下。")
                .setNegativeButton("取消", null)
                .setPositiveButton("复制并启动", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        importAndLaunch(uri, display, dirName);
                    }
                })
                .show();
    }

    private void importAndLaunch(final Uri uri, final String fileName, final String dirName) {
        if (busy) {
            toast("上一次还在执行，稍等");
            return;
        }
        busy = true;

        final File tmp = new File(getFilesDir(), "upload.bin");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new IllegalStateException("openInputStream 返回 null");
                    }
                    OutputStream os = new FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                    os.close();
                    in.close();
                    appendLog("已读取选中文件 " + fileName + " (" + total + " B)");
                } catch (Exception e) {
                    appendLog("! 读取选中文件失败: " + e);
                    toast("读取失败");
                    busy = false;
                    return;
                }

                File dir = getFilesDir();
                writeText(new File(dir, "push_src.txt"), tmp.getAbsolutePath());
                writeText(new File(dir, "push_dir.txt"), dirName);
                writeText(new File(dir, "push_file.txt"), fileName);

                final File script = materializeAsset("push.sh");
                Cmd r = root("sh " + script.getAbsolutePath());
                for (String line : r.out.split("\n")) {
                    if (!line.isEmpty()) {
                        appendLog(line);
                    }
                }
                appendLog("— push exit=" + r.code);

                if (r.code != 0) {
                    toast("复制失败，看日志");
                    busy = false;
                    return;
                }

                final String winPath = "C:\\apps\\" + dirName + "\\" + fileName;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("已放入 prefix，开始启动");
                        busy = false;
                        addIfMissing(dirName, winPath);
                        launchApp(dirName, winPath, "");
                    }
                });
            }
        }, "wp-push").start();
    }

    private void addIfMissing(String name, String exe) {
        for (AppEntry e : apps) {
            if (e.exe.equalsIgnoreCase(exe)) {
                return;
            }
        }
        AppEntry e = new AppEntry();
        e.name = name;
        e.exe = exe;
        e.args = "";
        List<AppEntry> next = new ArrayList<AppEntry>(apps);
        next.add(e);
        saveAppsThen(next, null);
    }


    // -------------------------------------------------------- zip installer

    private void importZip(final Uri uri, final String zipName) {
        if (busy) {
            toast("上一次还在执行，稍等");
            return;
        }
        busy = true;
        final String dirName = sanitize(stripExt(zipName));
        appendLog("");
        appendLog("▶ 安装 zip: " + zipName + " -> C:\\apps\\" + dirName);
        new Thread(new Runnable() {
            @Override
            public void run() {
                File stage = new File(STAGE_ROOT, dirName);
                try {
                    deleteTree(stage);
                    if (!stage.mkdirs()) {
                        throw new IllegalStateException("无法创建 " + stage);
                    }
                    int n = unzip(uri, stage);
                    flattenSingleRoot(stage);
                    File main = findMainExe(stage);
                    if (main == null) {
                        throw new IllegalStateException("zip 里没有可用的 exe");
                    }
                    String rel = main.getAbsolutePath()
                            .substring(stage.getAbsolutePath().length() + 1);
                    appendLog("解出 " + n + " 个条目，主程序 " + rel
                            + " (" + main.length() + " B)");
                    installStaged(stage, dirName, rel);
                } catch (Exception e) {
                    appendLog("! 安装失败: " + e);
                    toast("安装失败：" + e.getMessage());
                    busy = false;
                }
            }
        }, "wp-zip").start();
    }

    private int unzip(Uri uri, File dst) throws Exception {
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new IllegalStateException("openInputStream 返回 null");
        }
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw));
        String base = dst.getAbsolutePath();
        byte[] buf = new byte[131072];
        int count = 0;
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            File out = new File(dst, name);
            String path = out.getAbsolutePath();
            if (!path.startsWith(base)) {
                continue;                       // zip-slip 防护
            }
            if (e.isDirectory()) {
                out.mkdirs();
            } else {
                out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.close();
            }
            count++;
            if ((count % 200) == 0) {
                appendLog("  … 已解出 " + count + " 个条目");
            }
        }
        zis.close();
        return count;
    }

    /** zip 里若只有一个顶层目录，把内容提上来（避免 C:\apps\x\x\app.exe）。 */
    private void flattenSingleRoot(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length != 1 || !kids[0].isDirectory()) {
            return;
        }
        File root = kids[0];
        File[] inner = root.listFiles();
        if (inner == null) {
            return;
        }
        for (File f : inner) {
            f.renameTo(new File(dir, f.getName()));
        }
        root.delete();
        appendLog("已展开单一顶层目录 " + root.getName());
    }

    private static final String[] EXE_BAN = {
            "unins", "uninst", "setup", "install", "update", "updater", "crash",
            "helper", "report", "repair", "restart", "elevate", "vc_redist",
            "dxsetup", "notepad++"};

    private File findMainExe(File dir) {
        List<File> all = new ArrayList<File>();
        collect(dir, all, 0);
        File best = null;
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".exe")) {
                continue;
            }
            boolean ban = false;
            for (String b : EXE_BAN) {
                if (n.contains(b)) {
                    ban = true;
                    break;
                }
            }
            if (ban) {
                continue;
            }
            if (best == null || f.length() > best.length()) {
                best = f;
            }
        }
        if (best == null && !all.isEmpty()) {
            for (File f : all) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    if (best == null || f.length() > best.length()) {
                        best = f;
                    }
                }
            }
        }
        return best;
    }

    private void collect(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 4) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else {
                out.add(f);
            }
        }
    }

    private void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    /** 把 staging 目录搬进 prefix 的 drive_c\apps\，并注册到 apps.json。 */
    private void installStaged(final File stage, final String dirName, final String rel) {
        int code;
        if (bridgeAlive()) {
            appendLog("走命令网桥安装（root，init 命名空间）");
            code = installViaBridge(stage, dirName);
            if (code == 0) {
                finishInstall(dirName, rel);
                return;
            }
            appendLog("网桥安装失败 rc=" + code + "，尝试 su -M");
        }
        if (rootOk()) {
            final File script = materializeAsset("install.sh");
            Cmd r = root("sh " + script.getAbsolutePath()
                    + " '" + stage.getAbsolutePath() + "' '" + dirName + "'");
            for (String line : r.out.split("\n")) {
                if (!line.isEmpty()) {
                    appendLog(line);
                }
            }
            code = r.code;
        } else {
            appendLog("su 不可用 -> 走命令网桥（需以 root 启动）");
            code = installViaBridge(stage, dirName);
        }
        appendLog("— install exit=" + code);
        if (code != 0) {
            toast("安装失败（看日志）");
            busy = false;
            return;
        }
        finishInstall(dirName, rel);
    }

    /** 安装成功后的收尾：注册 apps.json + 询问是否加桌面快捷方式。 */
    private void finishInstall(final String dirName, final String rel) {
        final String win = "C:\\apps\\" + dirName + "\\" + rel.replace('/', '\\');
        ui.post(new Runnable() {
            @Override
            public void run() {
                busy = false;
                toast("已安装到 " + win);
                addIfMissing(dirName, win);
                askShortcut(dirName);
            }
        });
    }


    /** 通过命令网桥安装（网桥以 root 启动时才有写 prefix 的权限）。 */
    private int installViaBridge(File stage, String dirName) {
        if (!bridgeAlive()) {
            appendLog("!! 网桥离线：先在电脑上跑 launcher/install_bridge.sh（root 启动）");
            toast("网桥离线，无法安装");
            return -3;
        }
        File req = new File(WPZ_DIR, "install_req.txt");
        File res = new File(WPZ_DIR, "install_res.txt");
        long seq = System.currentTimeMillis() / 1000;
        res.delete();
        writeText(req, seq + "|" + dirName + "|" + stage.getAbsolutePath() + "\n");
        appendLog("安装请求 seq=" + seq + " -> " + req.getAbsolutePath());
        for (int i = 0; i < 240; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            String body = readText(res);
            if (body == null || !body.contains("seq=" + seq)) {
                continue;
            }
            for (String line : body.split("\n")) {
                if (!line.isEmpty()) {
                    appendLog(line);
                }
            }
            int p = body.lastIndexOf("rc=");
            if (p >= 0) {
                try {
                    return Integer.parseInt(body.substring(p + 3).trim());
                } catch (Exception ignored) {
                }
            }
            return -4;
        }
        appendLog("!! 等安装结果超时（120 s）");
        return -5;
    }

    // ----------------------------------------------------------- shortcuts

    private void askShortcut(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("添加到桌面？")
                .setMessage("为「" + name + "」在桌面生成快捷方式，点一下即可启动。")
                .setNegativeButton("不用", null)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pinShortcut(name);
                    }
                }).show();
    }



    /**
     * 发布动态快捷方式：长按「WinPlay Launcher」图标即可看到每个已安装程序的入口。
     * （MIUI/HyperOS 的 requestPinShortcut 弹窗（AddItemActivity）在本机启动后立刻自行结束，
     *   桌面固定走不通，动态快捷方式是这台机器上唯一可靠的原生入口。）
     */
    private void publishDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < 25) {
            return;
        }
        try {
            ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
            if (sm == null) {
                return;
            }
            int max = Math.min(sm.getMaxShortcutCountPerActivity(), 5);
            List<ShortcutInfo> list = new ArrayList<ShortcutInfo>();
            for (AppEntry e : apps) {
                if (list.size() >= max) {
                    break;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("winplaylauncher://app/" + Uri.encode(e.name)));
                    i.setClass(this, LaunchActivity.class);
                    list.add(new ShortcutInfo.Builder(this, "app_" + e.name)
                            .setShortLabel(e.name)
                            .setLongLabel(e.name + " · WinPlay")
                            .setIcon(Icon.createWithBitmap(makeIcon(e.name)))
                            .setIntent(i)
                            .build());
                } catch (Throwable ignored) {
                }
            }
            sm.setDynamicShortcuts(list);
            appendLog("动态快捷方式已发布 " + list.size() + " 个（长按本应用图标可见）");
        } catch (Throwable t) {
            appendLog("! 动态快捷方式失败: " + t);
        }
    }

    /** 生成一个 96×96 的图标（MIUI 的固定快捷方式 UI 需要能渲染的位图）。 */
    private android.graphics.Bitmap makeIcon(String label) {
        int n = 96;
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                n, n, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1B6EF3);
        c.drawRoundRect(new android.graphics.RectF(0, 0, n, n), 22, 22, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(52);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        String ch = (label == null || label.isEmpty()) ? "W" : label.substring(0, 1).toUpperCase(Locale.ROOT);
        c.drawText(ch, n / 2f, n / 2f + 18, p);
        return bm;
    }

    private void pinShortcut(String name) {
        if (Build.VERSION.SDK_INT < 26) {
            toast("系统版本过低，不支持固定快捷方式");
            return;
        }
        try {
            ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                toast("桌面不支持固定快捷方式");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("winplaylauncher://app/" + Uri.encode(name)));
            i.setClass(this, LaunchActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ShortcutInfo si = new ShortcutInfo.Builder(this, "app_" + name)
                    .setShortLabel(name)
                    .setLongLabel(name + " · WinPlay")
                    .setIcon(Icon.createWithBitmap(makeIcon(name)))
                    .setIntent(i)
                    .build();
            sm.requestPinShortcut(si, null);
            appendLog("已请求固定快捷方式: " + name + "（系统会弹确认框）");
        } catch (Exception e) {
            appendLog("! 快捷方式失败: " + e);
            toast("快捷方式失败：" + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- status

    private void refreshStatusLater() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                boolean su = rootOk();
                sb.append(su ? "su: 可用" : "su: 不可用");

                File beat = new File(ALIVE);
                long age = beat.exists() ? (System.currentTimeMillis() - beat.lastModified()) / 1000 : -1;
                if (age >= 0 && age < 20) {
                    sb.append(" · 网桥: 在线");
                } else {
                    sb.append(" · 网桥: 离线（桌面跑 install_bridge.sh）");
                }

                // 引擎状态：su 可用就自己 pidof；否则读网桥写的 status.txt
                String pid = "";
                String ws = "";
                if (su) {
                    Cmd p = root("pidof " + ENGINE_PKG);
                    Cmd w = root("pidof wineserver");
                    pid = p.out == null ? "" : p.out.trim();
                    ws = w.out == null ? "" : w.out.trim();
                } else {
                    String st = readText(new File(BRIDGE_STATUS));
                    if (st != null) {
                        String[] parts = st.trim().split("\\|");
                        for (String part : parts) {
                            if (part.startsWith("engine=")) {
                                pid = part.substring(7).trim();
                            } else if (part.startsWith("wineserver=")) {
                                ws = part.substring(11).trim();
                            }
                        }
                    }
                }
                if (pid.isEmpty()) {
                    sb.append(" · 引擎: 未运行（点条目会自动拉起，约 10 秒）");
                } else {
                    sb.append(" · 引擎: pid ").append(pid);
                    sb.append(ws.isEmpty() ? "（wineserver 未起）" : " + wineserver");
                }
                sb.append(" · ").append(apps.size()).append(" 个 app");
                setStatus(sb.toString());
            }
        }, "wp-status").start();
    }

    // ------------------------------------------------------------------ debug

    /** 排查用：把 cmd2.txt 现状打一条日志。 */
    private void dumpCmdFile() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Cmd r = root("ls -l " + CMD_FILE + "; cat " + CMD_FILE);
                appendLog(r.out);
            }
        }, "wp-dump").start();
    }

    // -------------------------------------------------------------- diagnose

    /** 诊断：逐个试 exec，把 errno / 异常原因链全部打出来。 */
    /** 用 /system/bin/sh 探测（App 内可以 exec sh，见诊断结果）。 */
    private String shProbe(String script) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", script)
                    .redirectErrorStream(true).start();
            String out = readStream(p.getInputStream()).trim();
            int code = p.waitFor();
            return "rc=" + code + " " + out.replace("\n", " ; ");
        } catch (Exception e) {
            return "EXC " + e;
        }
    }

    /** 诊断：搞清为什么 App 里 exec 不到 su。 */
    private void diagnose() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    appendLog("");
                    appendLog("======== 诊断 ========");
                    appendLog("uid=" + android.os.Process.myUid()
                            + " pid=" + android.os.Process.myPid()
                            + " abi=" + android.os.Build.SUPPORTED_ABIS[0]);
                    appendLog("PATH=" + System.getenv("PATH"));
                    String sec = readText(new File("/proc/self/attr/current"));
                    appendLog("selinux=" + (sec == null ? "?" : sec.trim()));

                    appendLog("-- stat --");
                    appendLog("su exists=" + new File("/system/bin/su").exists()
                            + " canExec=" + new File("/system/bin/su").canExecute());
                    String[] binList = new File("/system/bin").list();
                    appendLog("/system/bin 条目数=" + (binList == null ? "null" : binList.length));
                    appendLog("-- sh 探测（App 命名空间）--");
                    String[] probes = {
                            "ls -ld /system/xbin /sbin /debug_ramdisk 2>&1",
                            "ls /system/bin | /system/bin/grep -iE '^(su|ksu|magisk)' 2>&1; echo end",
                            "ls -l /proc/1/root/system/bin/su 2>&1",
                            "ls -l /proc/1/exe 2>&1",
                            "cat /proc/1/attr/current 2>&1",
                            "/system/bin/grep ' /system/bin' /proc/1/mountinfo 2>&1",
                            "ls -l /data/adb/ksu/bin/su /data/adb/ksud 2>&1",
                            "/proc/1/root/system/bin/su -c id 2>&1"
                    };
                    for (String pr : probes) {
                        appendLog("$ " + pr.substring(0, Math.min(46, pr.length()))
                                + " => " + shProbe(pr));
                    }
                    appendLog("-- exec 尝试 --");
                    String[][] tries = {
                            {"/system/bin/su", "-c", "id"},
                            {"/system/bin/sh", "-c", "id"},
                            {"/system/bin/toybox", "id"}
                    };
                    for (String[] t : tries) {
                        StringBuilder cmdline = new StringBuilder();
                        for (String a : t) {
                            cmdline.append(a).append(' ');
                        }
                        cmdline.append('[').append(t.length).append("]");
                        try {
                            Process p = new ProcessBuilder(t).redirectErrorStream(true).start();
                            String out = readStream(p.getInputStream()).trim();
                            int code = p.waitFor();
                            appendLog("exec OK   " + cmdline + " code=" + code + " out=" + out);
                        } catch (Exception e) {
                            appendLog("exec FAIL " + cmdline + " " + e);
                        }
                    }
                    appendLog("======== 诊断结束 ========");
                } catch (Throwable t) {
                    appendLog("诊断异常: " + t);
                }
            }
        }, "wp-diag").start();
    }

    private String canonical(String p) {
        try {
            return new File(p).getCanonicalPath();
        } catch (Exception e) {
            return "?";
        }
    }
}
