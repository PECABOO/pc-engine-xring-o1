# WinPlay Launcher

在小米平板（25032RP42C / jinghu / Android 17 / 已 root（su 常驻））上「点一下就启动 Windows 程序」的极简 App。

- 包名：`com.dsh.winplaylauncher`
- minSdk 30 / targetSdk 35，单 Activity，纯 Java，**零第三方依赖、零 AndroidX**（只用 framework 的 `ListView` / `LinearLayout` / `JSONArray`）
- 体积 ~33 KB

---

## 1. 它是怎么跑起来的

平板里装的是小米「PC 引擎」`com.xiaomi.winplay`（内含 Wine 10.5 + FEX），配合 Zygisk 模块 `winplay_run`。
模块在引擎进程内每 500 ms 轮询一个命令文件，发现新命令就调 `WindowChannel.runNewApp(...)`：

```
/storage/emulated/0/wpz/cmd2.txt
exePath|isUnixPath|seq|args|envs
例：C:\apps\7zip\7zFM.exe|0|1794935558|--foo;--bar|
```

所以 App 的全部工作就是：**把屏幕点亮 → 把引擎的 `WineActivity` 拉到前台 → 写好这一行命令**。

### 三条执行链路（App 自动选）

| 顺序 | 链路 | 条件 | 说明 |
|---|---|---|---|
| 1 | **su** | `su -c id` 返回 `uid=0` | 直接跑 `assets/wp_launch.sh`，最稳 |
| 2 | **网桥** | `/storage/emulated/0/wpz/.bridge_alive` 心跳新鲜（<20 s） | App 只写请求文件，由 shell 身份的守护进程代跑脚本 |
| 3 | 都没有 | — | 状态行提示「网桥: 离线（桌面跑 install_bridge.sh）」 |

**为什么需要网桥**：本机（HyperOS + 已 root）上，`/system/bin/su` 是**文件级挂载**，对所有 App 的
mount namespace 都不可见（`/proc/<pid>/root/system/bin/su` 对 Settings / SystemUI / 安全中心 同样是
`No such file or directory`），而 `adb shell`（uid 2000）能看见也能执行。这不是本 App 的问题，是设备侧
per-app mount namespace 快照的现状。在不 reboot、不改系统分区、不动 `/data/adb` 的前提下，用
`shelld` 守护进程代理执行是最省事的通路。

---

## 2. 目录结构

```
launcher/
├── AndroidManifest.xml           # 无自定义 layout/icon，只用 framework 资源
├── res/values/strings.xml        # 仅 app_name（为了生成 resources.arsc）
├── src/com/dsh/winplaylauncher/MainActivity.java
├── scripts/
│   ├── wp_launch.sh              # 真正的启动脚本（亮屏/解锁/拉前台/写 cmd2.txt/熄屏）
│   └── wp_bridge.sh              # shell 身份守护进程（心跳 + 请求/结果文件）
├── assets/                       # build.sh 从 scripts/ 复制过来，打包进 APK
├── apps.json                     # 示例列表（3 条：7-Zip / Notepad++ / VSCode 1.80）
├── build.sh                      # aapt2 + javac + d8 + zipalign + apksigner
├── install_bridge.sh             # 推脚本 + 起守护进程（每次重启平板后跑一次）
├── winplay.jks                   # 签名用 keystore（首次构建本地自动生成，口令见 `KS_PASS`）
└── WinPlayLauncher.apk           # 产物
```

构建环境（本机已有，无需 Android SDK / Gradle）：

| 组件 | 路径 |
|---|---|
| build-tools | `$HOME/opt/android/android-14/`（aapt2 / d8 / zipalign / apksigner） |
| android.jar | `$HOME/opt/android-platform/android-35/android.jar` |
| JDK | `javac 17.0.21` |

---

## 3. 构建

```bash
cd ~/PCEngine/launcher
sh build.sh
```

流程（`build.sh` 里就这几步）：

1. `cp scripts/*.sh assets/` —— 脚本单一来源，避免 assets 与 scripts 两份漂移
2. `aapt2 compile --dir res -o build/res.zip`
3. `aapt2 link -I $SDK_JAR --manifest AndroidManifest.xml -A assets --java build/gen \
   --min-sdk-version 30 --target-sdk-version 35 -o build/base.apk`
4. `javac -source 8 -target 8 -bootclasspath $SDK_JAR -d build/classes ...`
5. `d8 --lib $SDK_JAR --min-api 30 --release --output build/dex build/classes/**.class`
6. `zip -j build/base.apk classes.dex`（`resources.arsc` 保持 Stored + 4 字节对齐，targetSdk≥30 的硬要求）
7. `zipalign -f 4` → `apksigner sign --min-sdk-version 30` → `apksigner verify`

签名用 `winplay.jks`（首次运行自动 `keytool` 生成）。注意 `apksigner 0.9` 只出 **v3** scheme，
v1/v2 都是 false —— 装机实测没问题。

## 4. 安装 + 起网桥

```bash
adb install -r -g WinPlayLauncher.apk
adb shell am start -n com.dsh.winplaylauncher/.MainActivity

# 网桥（每次重启平板后跑一次；装/改脚本后也要重跑）
sh install_bridge.sh
```

`install_bridge.sh` 做的事：`adb push scripts/*.sh /data/local/tmp/wp/` → `chmod 755` →
`pkill` 旧进程 → `nohup setsid sh /data/local/tmp/wp/wp_bridge.sh </dev/null >/dev/null 2>&1 &` →
校验 `.bridge_alive` 心跳是否新鲜（不新鲜自动重试 3 次）。

脚本里 `trap '' HUP` + `setsid` 是必须的，否则 adb shell 一退出守护进程就被带走；重定向 stdin 也是必须的，
否则 `adb shell` 会一直挂在 pty 上不返回。

另外首次安装后建议给一次「所有文件访问」权限，App 才能直接读 `/storage/emulated/0/WinPlay/apps.json`：

```bash
adb shell appops set com.dsh.winplaylauncher MANAGE_EXTERNAL_STORAGE allow
```

（没有这个权限时 App 会退化成 `su -c cat`，su 不可用时就只能看到空列表。）

---

## 5. apps.json：怎么加新 app

列表文件：`/storage/emulated/0/WinPlay/apps.json`

```json
[
  {"name": "7-Zip",          "exe": "C:\\apps\\7zip\\7zFM.exe",       "args": ""},
  {"name": "Notepad++ ARM64","exe": "C:\\apps\\npp-arm64\\notepad++.exe", "args": ""},
  {"name": "VSCode 1.80",    "exe": "C:\\apps\\VSCode180\\Code.exe",
   "args": "--no-sandbox;--force-device-scale-factor=1;--disable-gpu;--disable-gpu-compositing;--disable-features=VizDisplayCompositor"}
]
```

- `exe` 是 **Windows 路径**（`C:\...`），注意 JSON 里反斜杠要写成 `\\`
- `args` 用 `;` 分隔（协议就是这么定的，不是空格）
- `icon` 字段可留空（MVP 不读图标）
- 改完在 App 里点「⟳ 重载」，或重开 App

### 加一个 Windows 程序的三条路

1. **已经躺在 prefix 里**：直接往 apps.json 加一条，写对应的 `C:\apps\...` 路径即可。
   prefix 里现成的目录：`/data/user/0/com.xiaomi.winplay/files/prefix/drive_c/apps/`
   （`7zip`、`npp-arm64`、`VSCode180`、`code180.bat` 等）

2. **要把 exe 拷进 prefix**（需要 root / adb）：
   ```bash
   adb push your.exe /data/local/tmp/your.exe
   adb shell su -c 'D=/data/user/0/com.xiaomi.winplay/files/prefix/drive_c/apps/your; \
     mkdir -p $D && cp -f /data/local/tmp/your.exe $D/ && \
     chown -R 7100:7100 $D && chmod -R 777 $D'
   ```
   然后在 apps.json 里写 `C:\apps\your\your.exe`。prefix 属主是 **7100:7100**（引擎的 uid），
   chown 漏了 Wine 会读不到文件。

3. **App 里「＋ 运行任意 exe」**：走 SAF 选任意 exe，App 用 root 把它 `cp` 到
   `drive_c/apps/<文件名>/` 并 `chown -R 7100:7100`，然后按同一流程启动 `C:\apps\<名字>\<文件名>`。
   这条链路**必须有 root**（要写 `/data/user/0/...`），网桥兜底跑不了。

长按条目 = 从 apps.json 里删除并立即写回（先直接写，靠「所有文件访问」权限；不行才退化成 `su -c cp`）。
只删列表项，不删 prefix 里的文件。实测在没有 su 的机器上也能正常写回（日志会打
`save apps.json: 直接写入成功（无需 root）`）。

> 这个文件可能是多人/多工具共管的（本机实测有另一个使用者在改它）。
> 编辑前先自己 `cp apps.json apps.json.bak-$(date +%H%M)`。

---

## 6. wp_launch.sh 做的事（一次点击的完整时序）

```
1. 读 dumpsys power / dumpsys window
   → 只有「不是 Awake」或「锁屏中」才注入 keyevent 224 + keyevent 82 + wm dismiss-keyguard + swipe
   （避免在桌面误拉通知栏）
2. pidof com.xiaomi.winplay
   → 没进程：am start WineActivity，最多等 60 s；冷启动再多等 55 s 让 Wine 桌面起来
3. dumpsys activity activities 看 topResumedActivity
   → 引擎不在前台就 am start（灭屏时 runNewApp 静默无效，必须前台 + 屏幕亮）
4. OLD=$(cut -d'|' -f3 cmd2.txt)；NOW=$(date +%s)；NOW<=OLD 时 NOW=OLD+1
   printf '%s|0|%s|%s|\n' "$EXE" "$NOW" "$ARGS" > .cmd2.$$ && chmod 666 && mv -f
   （tmp + rename 原子替换，防止模块读到半个文件）
5. sleep 4 → grep "cmd seq=$NOW" res2.log → 判定模块是否真的处理了这条命令
6. screenoff=1 时：sleep 4 → input keyevent 26，并用 mWakefulness 回读确认（最多 4 次重试）
```

`input` 注入在本 ROM 上偶发 `Failure calling service input: Failed transaction (2147483646)`，
脚本里的 `try_input()` 会重试 3 次并且不打崩流程。

---

## 7. 已知限制 / 坑

1. **App 内看不到 `su`**（本机现状）：`/system/bin/su` 对所有 App 的 namespace 都不可见。
   要让 App 直接用 root，需要动 root（给 `com.dsh.winplaylauncher` 关掉「卸载模块」/加白名单）
   或重启，这些都不在本次「不 reboot / 不改系统分区 / 不动 /data/adb」的范围内 —— 所以默认走网桥。
2. **网桥要每次重启后重跑**：`sh install_bridge.sh`。守护进程被系统杀掉后 App 状态行会显示「网桥: 离线」。
3. **引擎必须在前台且屏幕亮着**：`runNewApp` 在灭屏/后台时静默无效。脚本已经处理（拉前台 + 亮屏）。
4. **seq 必须严格递增**：模块只认比上次大的 seq。脚本用 `date +%s`，撞车时自己 +1。
   如果设备上有别的程序也在写 `cmd2.txt`（本机实测存在并发写入者），两条命令会各自被处理，
   只要 seq 单调即可，互不影响。
5. **`chmod 666` 在 `/storage/emulated/0`（FUSE）上不生效**：写出来的文件仍是 `-rw-rw----`，
   但引擎和 root 都读得到，不影响。
6. **`input` 偶发失败**：见上，已加重试；熄屏会回读 `mWakefulness` 确认，失败会写日志。
7. **`apps.json` 需要「所有文件访问」权限**：`MANAGE_EXTERNAL_STORAGE`（`-g` 安装 + `appops set`）。
8. **没有图标**：MVP 用 `@android:drawable/sym_def_app_icon`，`icon` 字段暂不解析。
9. **一次只能跑一个启动流程**：App 里 `busy` 标志会挡住连点；引擎把 `WineActivity` 抢到前台后
   App 会被系统冻结，轮询线程停摆 —— 回到前台时 `onResume()` 会补查一次结果写入日志。
10. **`res2.log` 是模块自己写的**：App 只读它来判断「模块有没有收下这条命令」，
    真正的窗口是否出现要看屏幕/引擎。

## 8. 排障

- App 内日志：界面底部日志区 + 落盘 `/data/user/0/com.dsh.winplaylauncher/files/launcher.log`
  （`adb shell su -c "tail -n 50 /data/user/0/com.dsh.winplaylauncher/files/launcher.log"`）
- 网桥日志：`/storage/emulated/0/wpz/bridge.log`、请求/结果 `launch_req.txt` / `launch_res.txt`
- 引擎侧：`/storage/emulated/0/wpz/res2.log`（`cmd seq=...` / `channel ok` / `runNewApp returned OK`）
- 命令文件现状：`/storage/emulated/0/wpz/cmd2.txt`
- 「诊断」按钮：把 uid / PATH / SELinux context / `/system/bin` 里有没有 su / exec 实测结果全打到日志里
- 「＋ 运行任意 exe」：SAF 选文件 → 拷进 prefix → 启动（要 root）
- 只自检桥链路、不启动任何 Windows 程序：把 exe 传 `--noop`，脚本会空转 12 s 后退出
  ```bash
  adb shell 'echo "$(date +%s)000|1|$(echo -n --noop | base64)|" > /storage/emulated/0/wpz/launch_req.txt'
  ```
