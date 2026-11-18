package com.dsh.winplaylauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 桌面快捷方式落点：winplaylauncher://app/<name>
 * 收到后把启动请求转给 MainActivity（带 auto_launch），自己立刻结束，
 * 这样快捷方式点下去不会留下一个空壳页面。
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String name = null;
        Uri d = getIntent() == null ? null : getIntent().getData();
        if (d != null) {
            String p = d.getLastPathSegment();
            if (p != null && !p.isEmpty()) {
                name = Uri.decode(p);
            }
            if (name == null) {
                String q = d.getQueryParameter("name");
                if (q != null && !q.isEmpty()) {
                    name = q;
                }
            }
        }
        if (name == null) {
            String extra = getIntent() == null ? null : getIntent().getStringExtra("auto_launch");
            if (extra != null && !extra.isEmpty()) {
                name = extra;
            }
        }
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (name != null) {
            i.putExtra("auto_launch", name);
        }
        startActivity(i);
        finish();
    }
}
