package com.dsh.winplaylauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** MIUIX 风格的胶囊开关：44x26dp，白色圆钮，选中为品牌蓝。 */
public class MiuixSwitch extends View {

    public interface OnCheckedChange {
        void onCheckedChanged(MiuixSwitch v, boolean checked);
    }

    private boolean checked;
    private OnCheckedChange listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private static final int ON = 0xFF3482FF;
    private static final int OFF = 0xFFE4E4E8;
    private static final int THUMB = 0xFFFFFFFF;

    public MiuixSwitch(Context c) {
        super(c);
        setClickable(true);
    }

    public boolean isChecked() { return checked; }

    public void setChecked(boolean v) {
        if (checked == v) return;
        checked = v;
        invalidate();
    }

    public void setOnCheckedChange(MiuixSwitch.OnCheckedChange l) { listener = l; }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(dp(44), dp(26));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = h / 2f;
        r.set(0, 0, w, h);
        p.setColor(checked ? ON : OFF);
        c.drawRoundRect(r, rad, rad, p);

        float m = dp(3);
        float d = h - m * 2;
        float cx = checked ? (w - m - d / 2f) : (m + d / 2f);
        p.setColor(THUMB);
        c.drawCircle(cx, h / 2f, d / 2f, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            checked = !checked;
            invalidate();
            if (listener != null) listener.onCheckedChanged(this, checked);
            performClick();
        }
        return true;
    }
}
