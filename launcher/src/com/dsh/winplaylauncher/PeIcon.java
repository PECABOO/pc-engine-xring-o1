package com.dsh.winplaylauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.BitmapShader;
import android.graphics.Shader;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 从 Windows PE 的 .rsrc 里取应用自带图标。
 * 只用标准库：解析 PE 段表 → RT_GROUP_ICON / RT_ICON → 组装 ICO 映像 → 解码。
 * PNG 压缩的图标走 BitmapFactory；老式 DIB 图标自己解（32/24/8/4/1 bpp）。
 */
final class PeIcon {

    private PeIcon() {}

    /** 解析失败返回 null（调用方回落成首字母占位）。 */
    static synchronized Bitmap load(File pe) {
        lastError = "";
        if (pe == null || !pe.isFile()) { lastError = "no file"; return null; }
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(pe, "r");
            byte[] hdr = new byte[0x1000];
            f.readFully(hdr);

            int lfanew = i32(hdr, 0x3C);
            if (lfanew <= 0 || lfanew + 0x100 >= hdr.length) { lastError = "lfanew " + lfanew; return null; }
            if (hdr[lfanew] != 'P' || hdr[lfanew + 1] != 'E') { lastError = "no PE sig"; return null; }

            int nsec = u16(hdr, lfanew + 6);
            int optSize = u16(hdr, lfanew + 20);
            int opt = lfanew + 24;
            int magic = u16(hdr, opt);
            if (magic != 0x10b && magic != 0x20b) return null;

            int secTab = opt + optSize;
            int rsrcVA = -1, rsrcSize = 0, rsrcPtr = -1;
            for (int i = 0; i < nsec; i++) {
                int s = secTab + i * 40;
                String name = new String(hdr, s, 8, "ISO-8859-1").trim();
                if (name.startsWith(".rsrc")) {
                    rsrcSize = i32(hdr, s + 16);   // SizeOfRawData
                    rsrcVA = i32(hdr, s + 12);     // VirtualAddress
                    rsrcPtr = i32(hdr, s + 20);    // PointerToRawData
                    break;
                }
            }
            if (rsrcVA < 0 || rsrcSize <= 0 || rsrcPtr <= 0) { lastError = "no .rsrc (nsec=" + nsec + ")"; return null; }
            if (rsrcSize > 16 * 1024 * 1024) rsrcSize = 16 * 1024 * 1024;

            sRsrcVA = rsrcVA;
            byte[] rsrc = new byte[rsrcSize];
            f.seek(rsrcPtr);
            f.readFully(rsrc);

            int grpId = findFirstId(rsrc, 14);   // RT_GROUP_ICON
            if (grpId < 0) { lastError = "no RT_GROUP_ICON"; return null; }
            byte[] grp = dataOf(rsrc, 14, grpId);
            if (grp == null || grp.length < 6) { lastError = "empty group"; return null; }

            int count = u16(grp, 4);
            int bestW = -1, bestId = -1;
            for (int i = 0; i < count; i++) {
                int e = 6 + i * 14;
                if (e + 14 > grp.length) break;
                int w = grp[e] & 0xFF;
                if (w == 0) w = 256;
                int bpp = u16(grp, e + 6);
                int id = u16(grp, e + 12);
                int score = w * 1000 + bpp;
                if (score > bestW) { bestW = score; bestId = id; }
            }
            if (bestId < 0) {
                // GRPICONDIR 里没条目（有些精简打包的 exe 会这样）→ 直接用第一个 RT_ICON
                bestId = findFirstId(rsrc, 3);
            }
            if (bestId < 0) { lastError = "no icon id"; return null; }

            byte[] img = dataOf(rsrc, 3, bestId);   // RT_ICON
            if (img == null || img.length < 16) { lastError = "no RT_ICON " + bestId; return null; }

            if (img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P') {
                return BitmapFactory.decodeByteArray(img, 0, img.length);
            }
            return decodeDib(img);
        } catch (Throwable t) {
            lastError = "exc " + t;
            return null;
        } finally {
            if (f != null) try { f.close(); } catch (Exception ignored) {}
        }
    }

    /** 圆角化 + 缩放到指定边长。 */
    static Bitmap round(Bitmap src, int sizePx, float radiusPx) {
        return round(src, sizePx, radiusPx, null);
    }

    /** 底板 + 内缩图标 + 右下角架构角标（ARM64 / x64 / x86）。 */
    static Bitmap round(Bitmap src, int sizePx, float radiusPx, String badge) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // 1) 先铺白色圆角底板 —— 圆角只作用于底板，不作用于图标本身
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFFFFFFF);
        c.drawRoundRect(new RectF(0, 0, sizePx, sizePx), radiusPx, radiusPx, bg);

        // 2) 图标按等比缩放塞进底板内缩区域（12% 边距），四角永远不会碰到圆角
        float inset = sizePx * 0.12f;
        float box = sizePx - inset * 2f;
        float scale = Math.min(box / (float) src.getWidth(), box / (float) src.getHeight());
        float w = src.getWidth() * scale, h = src.getHeight() * scale;
        RectF dst = new RectF((sizePx - w) / 2f, (sizePx - h) / 2f,
                              (sizePx + w) / 2f, (sizePx + h) / 2f);
        Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, null, dst, fg);

        // 右下角架构角标
        if (badge != null && badge.length() > 0) {
            float ts = sizePx * 0.145f;                    // 字号 ≈ 17% 边长
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(ts);
            // 角标不超右半幅：太宽就缩字号（ARM64 在 48px 图标上必须缩）
            // 字号固定，不再为长标签缩字 —— 缩字会让 ARM64 和 x64 看起来不一样大；
            // 改成让胶囊按文字宽度变宽（上限 58% 边长，仍留在圆角内）。
            float tw = tp.measureText(badge);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float th = fm.descent - fm.ascent;
            float padX = ts * 0.26f, padY = ts * 0.12f;
            float bw = tw + padX * 2f, bh = th + padY * 2f;
            float m = sizePx * 0.085f;                    // 离边距（避开圆角弧线）
            RectF br = new RectF(sizePx - bw - m, sizePx - bh - m, sizePx - m, sizePx - m);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(0xCC000000);
            float rr = bh * 0.34f;
            c.drawRoundRect(br, rr, rr, bp);
            c.drawText(badge, br.left + padX, br.top + padY - fm.ascent, tp);
        }
        return out;
    }

    /** 只读 PE 头判断架构：ARM64 / x64 / x86，读不到返回 null。 */
    static String archOf(File pe) {
        if (pe == null || !pe.isFile()) return null;
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(pe, "r");
            byte[] h = new byte[0x400];
            f.readFully(h);
            int lfanew = i32(h, 0x3C);
            if (lfanew <= 0 || lfanew + 6 >= h.length) return null;
            if (h[lfanew] != 'P' || h[lfanew + 1] != 'E') return null;
            int machine = u16(h, lfanew + 4);
            if (machine == 0xAA64) return "ARM64";
            if (machine == 0x8664) return "x64";
            if (machine == 0x014C) return "x86";
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (f != null) f.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- PE 资源

    // .rsrc 的 RVA 基准：资源数据项里的 RVA 是镜像绝对 RVA，需减掉它才是数组下标
    // 注意：load() 必须 synchronized —— 这个字段是静态的，多个提取线程并发会互相覆盖。
    private static int sRsrcVA;
    static String lastError = "";

    private static int findFirstId(byte[] r, int type) {
        int nNamed = u16(r, 12), nId = u16(r, 14);
        for (int i = 0; i < nNamed + nId; i++) {
            int e = 16 + i * 8;
            if (e + 8 > r.length) return -1;
            int id = u16(r, e);
            if (id == type) {
                int off = i32(r, e + 4) & 0x7FFFFFFF;
                if (off + 16 > r.length) return -1;
                int cnt = u16(r, off + 12) + u16(r, off + 14);
                if (cnt <= 0) return -1;
                int e2 = off + 16;
                if (e2 + 8 > r.length) return -1;
                return u16(r, e2);   // 第一个名字 ID
            }
        }
        return -1;
    }

    private static byte[] dataOf(byte[] r, int type, int nameId) {
        int nNamed = u16(r, 12), nId = u16(r, 14);
        for (int i = 0; i < nNamed + nId; i++) {
            int e = 16 + i * 8;
            if (e + 8 > r.length) return null;
            if (u16(r, e) != type) continue;
            int off = i32(r, e + 4) & 0x7FFFFFFF;
            int cnt = u16(r, off + 12) + u16(r, off + 14);
            for (int j = 0; j < cnt; j++) {
                int e2 = off + 16 + j * 8;
                if (e2 + 8 > r.length) return null;
                if (u16(r, e2) != nameId) continue;
                int off2 = i32(r, e2 + 4) & 0x7FFFFFFF;
                if (off2 + 16 > r.length) return null;
                int langCnt = u16(r, off2 + 12) + u16(r, off2 + 14);
                if (langCnt <= 0) return null;
                int e3 = off2 + 16;
                // e3 是「语言」这一层的 8 字节条目：Name(4) + OffsetToData(4)
                // 再跳一次才到 IMAGE_RESOURCE_DATA_ENTRY（RVA/Size/CodePage）
                int de = i32(r, e3 + 4) & 0x7FFFFFFF;
                if (de < 0 || de + 16 > r.length) return null;
                int rva = i32(r, de);
                int size = i32(r, de + 4);
                int off3 = rva - sRsrcVA;
                if (off3 < 0 || size <= 0 || off3 + size > r.length) return null;
                byte[] out = new byte[size];
                System.arraycopy(r, off3, out, 0, size);
                return out;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- DIB 解码

    private static Bitmap decodeDib(byte[] d) {
        int biSize = i32(d, 0);
        int w = i32(d, 4);
        int h = i32(d, 8);
        int planes = u16(d, 12);
        int bpp = u16(d, 14);
        int comp = i32(d, 16);
        if (w <= 0 || w > 1024 || h == 0 || comp != 0) return null;
        boolean topDown = h < 0;
        int hh = Math.abs(h) / 2;   // ICO 里高度是 XOR+AND 两倍
        if (hh <= 0 || hh > 1024) return null;

        int stride = ((w * bpp + 31) / 32) * 4;
        int pixOff = biSize;
        long need = (long) pixOff + (long) stride * hh;
        if (need > d.length) return null;

        int[] pal = null;
        int palCount = 0;
        if (bpp <= 8) {
            palCount = (biSize >= 40 && i32(d, 32) > 0) ? i32(d, 32) : (1 << bpp);
            if (palCount > 256 || pixOff + palCount * 4 > d.length) return null;
            pal = new int[palCount];
            for (int i = 0; i < palCount; i++) {
                int o = pixOff + i * 4;
                int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            pixOff += palCount * 4;
        }
        if ((long) pixOff + (long) stride * hh > d.length) return null;

        int[] px = new int[w * hh];
        boolean anyAlpha = false;
        for (int y = 0; y < hh; y++) {
            int row = pixOff + (topDown ? y : (hh - 1 - y)) * stride;
            for (int x = 0; x < w; x++) {
                int argb;
                if (bpp == 32) {
                    int o = row + x * 4;
                    int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF, a = d[o + 3] & 0xFF;
                    if (a != 0) anyAlpha = true;
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                } else if (bpp == 24) {
                    int o = row + x * 3;
                    int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF;
                    argb = 0xFF000000 | (r << 16) | (g << 8) | b;
                } else if (bpp == 8) {
                    argb = pal[d[row + x] & 0xFF];
                } else if (bpp == 4) {
                    int v = d[row + x / 2] & 0xFF;
                    argb = pal[(x % 2 == 0) ? (v >> 4) : (v & 0xF)];
                } else if (bpp == 1) {
                    int v = d[row + x / 8] & 0xFF;
                    argb = pal[(v >> (7 - (x % 8))) & 1];
                } else {
                    return null;
                }
                px[y * w + x] = argb;
            }
        }
        if (bpp == 32 && !anyAlpha) {
            for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
        }

        // AND 掩码才是 Win32 图标真正的透明通道（XOR 位图后面紧跟 1bpp 掩码，位=1 表示透明）
        // 只认 alpha 的话，很多 exe 图标会出现整片黑底/黑边
        int maskOff = pixOff + stride * hh;
        int maskStride = ((w + 31) / 32) * 4;
        if ((long) maskOff + (long) maskStride * hh <= d.length) {
            for (int y = 0; y < hh; y++) {
                int mrow = maskOff + y * maskStride;          // 掩码同样自下而上
                int drow = (hh - 1 - y) * w;
                for (int x = 0; x < w; x++) {
                    int bit = (d[mrow + (x >> 3)] >> (7 - (x & 7))) & 1;
                    if (bit == 1) px[drow + x] &= 0x00FFFFFF;  // alpha → 0
                }
            }
        }
        Bitmap bm = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        bm.setPixels(px, 0, w, 0, 0, w, hh);
        return bm;
    }

    // ---------------------------------------------------------------- 小工具

    private static int u16(byte[] b, int o) {
        if (o < 0 || o + 2 > b.length) return 0;
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int o) {
        if (o < 0 || o + 4 > b.length) return 0;
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
