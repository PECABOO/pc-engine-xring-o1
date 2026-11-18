#!/system/bin/sh
# KernelSU 安装阶段（root 身份跑一次）。这里做四件事：
#   1) 校验同款设备所需的组件在不在（不自带 483 MB 的引擎 APK，见 README）
#   2) 装启动器并授权
#   3) 关掉旧版模块，避免双份 Zygisk 注入
#   4) 打印后续步骤
SKIPUNZIP=0
ENGINE=com.xiaomi.winplay
GAME=com.xiaomi.gamecenter
APP=com.dsh.winplaylauncher

ui_print "- WinPlay PC Engine (XRING O1)"
ui_print "- 设备: $(getprop ro.product.model) / $(getprop ro.board.platform)"

# ---- 1) 引擎与游戏中心 ----
if pm path "$ENGINE" >/dev/null 2>&1; then
    ui_print "- 已检测到 PC 引擎 ($ENGINE)"
else
    if [ -f "$MODPATH/apks/winplay.apk" ]; then
        ui_print "- 安装内置的 PC 引擎 APK（约 483 MB，可能需要 1-2 分钟）"
        pm install -r -d "$MODPATH/apks/winplay.apk" || abort "! PC 引擎安装失败"
    else
        abort "! 未找到 com.xiaomi.winplay。\n  同款设备请先在「游戏中心 → PC 引擎」里下载安装一次；\n  或用 make-bundle.sh 生成带 APK 的完整包。"
    fi
fi

if pm path "$GAME" >/dev/null 2>&1; then
    ui_print "- 已检测到游戏中心 ($GAME)"
elif [ -f "$MODPATH/apks/gamecenter.apk" ]; then
    ui_print "- 安装内置的游戏中心 APK"
    pm install -r -d "$MODPATH/apks/gamecenter.apk" || ui_print "! 游戏中心安装失败（不影响启动器）"
else
    ui_print "- 未检测到游戏中心（只影响从游戏中心进 PC 引擎，不影响启动器）"
fi

# ---- 2) 启动器 ----
if pm install -r "$MODPATH/common/WinPlayLauncher.apk" >/dev/null 2>&1; then
    ui_print "- 启动器已安装"
else
    ui_print "- 安装阶段 pm 不可用，改为开机后自动安装"
    touch "$MODPATH/.install_app"
fi
appops set "$APP" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
appops set "$APP" REQUEST_INSTALL_PACKAGES allow 2>/dev/null
pm grant "$APP" android.permission.POST_NOTIFICATIONS 2>/dev/null
pm grant "$APP" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
pm grant "$APP" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null

# ---- 3) 旧模块（会把同一个 exe 注入两遍）----
for m in winplay_run winplay_fix; do
    if [ -d "/data/adb/modules/$m" ] && [ ! -f "/data/adb/modules/$m/disable" ]; then
        touch "/data/adb/modules/$m/disable"
        ui_print "- 已停用旧模块 $m（重启后生效，可手动删除）"
    fi
done

# ---- 4) 收尾 ----
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/common" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
mkdir -p /storage/emulated/0/wpz
chmod 0777 /storage/emulated/0/wpz 2>/dev/null
ui_print "- 完成。重启后：打开「WinPlay 启动器」→ 授予「所有文件访问」→ 点应用即可运行。"
ui_print "- 前置条件：Zygisk Next (zygisksu) 已启用；引擎首次启动需要 40-60 s。"
