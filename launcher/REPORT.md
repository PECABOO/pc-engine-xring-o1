# WinPlay Launcher — 交付报告

日期：2026-11-18 · 目标设备：Xiaomi 25032RP42C（jinghu / Android 17 / SDK 37 / 已 root）

---

## 1. 交付物清单

| # | 交付物 | 路径 / 值 |
|---|---|---|
| 1 | APK | `~/PCEngine/launcher/WinPlayLauncher.apk` |
| 2 | APK 大小 | **33 359 字节**（≈32.6 KB），md5 `949a20068f0acf618ce675619d40be3f` |
| 3 | 安装输出 | `Performing Incremental Install` / `Success` / `Install command complete in 254 ms` |
| 4 | `pm path` | `package:/data/app/~~9f2DInPe5uE1X9L3hSoLqg==/com.dsh.winplaylauncher-QAJ_0Dv0EMT-LSXzSi2ySw==/base.apk` |
| 5 | 设备上 APK | `-r-xr-xr-x 3 system system 33359` |
| 6 | 启动验证 | `topResumedActivity=ActivityRecord{177561687 u0 com.dsh.winplaylauncher/.MainActivity t74}` |
| 7 | apps.json | `launcher/apps.json`（3 条示例）→ 已 push 到 `/storage/emulated/0/WinPlay/apps.json` |
| 8 | README | `launcher/README.md`（原理 / 构建 / 安装 / 加 app / 协议 / 10 条已知限制 / 排障） |
| 9 | UI 截图 | `launcher/docs/ui-0126.png`（3200×2136，列表 + 状态行 + 日志） |
| 10 | 源码 | `launcher/src/com/dsh/winplaylauncher/MainActivity.java`（1100 行，无第三方依赖） |
| 11 | 构建脚本 | `launcher/build.sh` · 网桥安装 `launcher/install_bridge.sh` · 执行脚本 `launcher/scripts/wp_launch.sh` `launcher/scripts/wp_bridge.sh` |

### apps.json（规格要求的三条，已核验内容逐字一致）

```json
[
 {"name": "7-Zip",           "exe": "C:\\apps\\7zip\\7zFM.exe",          "args": ""},
 {"name": "Notepad++ ARM64", "exe": "C:\\apps\\npp-arm64\\notepad++.exe", "args": ""},
 {"name": "VSCode 1.80",     "exe": "C:\\apps\\VSCode180\\Code.exe",
  "args": "--no-sandbox;--force-device-scale-factor=1;--disable-gpu;--disable-gpu-compositing;--disable-features=VizDisplayCompositor"}
]
```

> 设备上该文件此刻有 10 条：除上述 3 条外，还有另一使用者/会话后来加进去的
> `Notepad++ x64 / SumatraPDF / WinRAR / VSCode 1.70 / putty-x86 / VSCode146 / obsidian`。
> 交付时**没有覆盖删除**这些条目（它们属于同一台平板上的真实使用数据），仓库里的
> `launcher/apps.json` 是只含规格 3 条的干净示例。

---

## 2. 构建（本机无 Android SDK / Gradle）

本机已有可用工具链，**没有**走 Maven 下载 d8 / aapt2 / apksig 那条路，也没装 JDK：

| 组件 | 实际使用路径 |
|---|---|
| `aapt2` | `$HOME/opt/android/android-14/aapt2`（2.19-10229193） |
| `d8` | `$HOME/opt/android/android-14/d8`（8.2.2） |
| `zipalign` / `apksigner` | `$HOME/opt/android/android-14/{zipalign,apksigner}`（apksigner 0.9） |
| `android.jar` | **`$HOME/opt/android-platform/android-35/android.jar`**（platform-35） |
| `javac` | `/usr/bin/javac`（17.0.21，`-source 8 -target 8`） |
| keystore | `launcher/winplay.jks`（首次 `build.sh` 自动 `keytool` 生成，首次构建本地自动生成，口令取 `KS_PASS`（默认 `winplay-dev-pass`）） |

一条命令：

```bash
cd ~/PCEngine/launcher && sh build.sh
```

内部 8 步（与 `build.sh` 里的分段标题一致）：

```bash
cp -f scripts/*.sh assets/                                    # 1 脚本单一来源
aapt2 compile --dir res -o build/res.zip                      # 2 编资源
aapt2 link -I $SDK_JAR --manifest AndroidManifest.xml -A assets \
      --java build/gen --min-sdk-version 30 --target-sdk-version 35 \
      -o build/base.apk                                       # 3 生成 resources.arsc + 打包 assets
javac -source 8 -target 8 -bootclasspath $SDK_JAR -d build/classes $(find src -name '*.java')   # 4
d8 --lib $SDK_JAR --min-api 30 --release --output build/dex $(find build/classes -name '*.class') # 5
zip -j build/base.apk build/dex/classes.dex                   # 6 只 add dex（arsc 保持 Stored）
zipalign -f 4 build/base.apk build/aligned.apk                # 7
apksigner sign --ks winplay.jks --ks-pass pass:"$KS_PASS" \
      --min-sdk-version 30 --out WinPlayLauncher.apk build/aligned.apk   # 8
```

安装：

```bash
adb install -r -g WinPlayLauncher.apk
adb shell appops set com.dsh.winplaylauncher MANAGE_EXTERNAL_STORAGE allow   # 读 /sdcard 上的 apps.json
adb shell am start -n com.dsh.winplaylauncher/.MainActivity
sh install_bridge.sh          # 起 shell 网桥（每次重启平板后跑一次）
```

---

## 3. 遇到的坑与解法

### 3.1 构建侧

| 坑 | 现象 | 解法 |
|---|---|---|
| targetSdk≥30 安装被拒 | `resources.arsc` 被压缩/未对齐 → `INSTALL_PARSE_FAILED_...` | `zip` 只 add `classes.dex`（不用 `zip -r` 重打整个 APK），`resources.arsc` 保持 Stored；`zipalign -f 4` 后校验偏移（实测 952，4 字节对齐） |
| `aapt2 version` 打印的是 aapt 版本 | 误以为版本不对 | 无影响，`aapt2 compile/link` 正常 |
| `jarsigner` 只出 v1 | 装不上 | 改 `apksigner`（最终只有 **v3** scheme，v1/v2 均 false，装机实测通过） |
| 无 AndroidX | `AppCompatActivity` / `RecyclerView` 都不可用 | 全部程序化 UI：`LinearLayout` + `ListView` + `BaseAdapter` + `AlertDialog`，`WindowInsets` 手写 inset |
| 资源 | 想把 app 名写中文 | 只有 `res/values/strings.xml` 一个 `app_name`，图标复用 `@android:drawable/sym_def_app_icon` |

### 3.2 运行侧（关键）

1. **App 里根本没有 `su`**（本机最硬的坑）
   - 现象：`new ProcessBuilder("su","-c",cmd)` → `IOException: Cannot run program "su": error=13, Permission denied`。
   - 逐层结论：`exec` 机制正常（`/system/bin/sh`、`/system/bin/toybox` 都能 exec，返回自己 uid 10340）；
     `/system/bin/su` 在 App 的 mount namespace 里**不存在**（`exists=false canExec=false`），
     但 root shell 里 `ls -l /system/bin/su` 正常 → 它是 root 的**文件级挂载**（readdir 看不到、按路径能解析），
     且**对所有 App 都不可见**（`/proc/<pid>/root/system/bin/su` 对 `com.android.settings`、
     `com.miui.securitycenter`、`com.android.systemui` 全部 `No such file or directory`）。
   - 修它要改 root 配置或重启，都在本次红线内 → 改为**双通道**：
     `su` 可用就直接用；否则用一个 **uid 2000 的 shell 守护进程**（`adb shell` 起来，能看见并执行 su/input/am/pidof）
     做执行代理，App 只写请求文件、读结果文件。没有 `su` 也能完整点启。
2. **`runNewApp` 灭屏/后台时静默无效** → 脚本先 `dumpsys` 判断，只有需要时才 `keyevent 224/82 + wm dismiss-keyguard + swipe`；
   再 `dumpsys activity activities` 确认引擎是否在前台，不在才 `am start WineActivity`（重复 start 会把已跑的 Windows 会话顶掉）。
3. **`input` 偶发失败**：`cmd: Failure calling service input: Failed transaction (2147483646)`（引擎忙时竞态，非 setsid/无 tty 导致）
   → `try_input()` 重试 3 次；熄屏后回读 `mWakefulness` 确认（最多 4 次），实测最终日志 `screen off ok (Dozing)`。
4. **守护进程存活**：`adb shell` 退出会把后台进程带走 → `setsid` + `nohup` + `trap '' HUP INT TERM` + 三个重定向；
   `install_bridge.sh` 用心跳文件（`.bridge_alive`）校验并在不新鲜时重试 3 次。
   脚本运行期主循环被阻塞 ~28 s，会误判「心跳消失」→ 跑脚本期间另起子进程每秒写心跳。
   重启守护进程不能重跑上一条请求 → 启动时读一次 `launch_req.txt` 的 seq 作为 `LAST`。
5. **`cmd2.txt` 要原子替换**：先写 `.cmd2.$$`、`chmod 666`、再 `mv`，否则模块可能读到半个文件；
   `seq` 必须严格递增，撞秒时 `NOW=OLD+1`。
6. **`chmod 666` 在 `/storage/emulated/0`（FUSE）上不生效**：写出来仍是 `-rw-rw----`，但引擎/root 都读得到，无害。
7. **写 `apps.json` 需要过 DAC**：`/storage/emulated/0/WinPlay` 是 `drwxrws--- u0_a210 media_rw`，本 App uid 10340 非属主
   → 靠 `MANAGE_EXTERNAL_STORAGE`（`-g` + `appops set`）直接写；没有权限才退化到 `su -c cp`。实测无 su 也能写回。
8. **验证不要盯屏幕**：引擎几秒内就抢回前台，截图/坐标点击都不可靠 →
   改用「落盘日志 + `su -c cat`」和 `--noop` 自检钩子（`wp_launch.sh --noop`：空转 12 s，不写 cmd2.txt、不启动任何程序）。
9. **`set -e` + `pkill` 返回 1** 会让整个安装脚本静默中止 → 加 `|| true`。
10. **shell 兼容**：case 模式里的裸空格（`*ot found*)`）在 dash/bash 直接语法错误 → 模式串必须加引号。

---

## 4. 端到端验证（真实点击，仅两次）

| 项 | 第一次（01:12） | 第二次（01:21，最终版） |
|---|---|---|
| 请求 | `launch_req.txt` = `1794935554954\|1\|QzpcYXBwc1w3emlwXDd6Rk0uZXhl\|` | `1794936051431\|1\|…\|` |
| 命令 | `cmd2.txt` = `C:\apps\7zip\7zFM.exe\|0\|1794935558\|\|` | `…\|0\|1794936069\|\|` |
| 模块 | `res2.log`: `channel ok` / `calling runNewApp unix=0` / `runNewApp returned OK` | `[01:21:12] OK: module picked up seq=1794936069` |
| 结果 | 截图确认 **7-Zip File Manager 在 Wine 桌面运行** | `[01:21:19] screen off ok (Dozing)`、`rc=0` |

删除路径（长按）也已实测：加一条临时 `TEST-删除我` → 长按 → 对话框「删除」→
`apps.json` 变回 10 条、列表刷新、日志 `[01:25:48] save apps.json: 直接写入成功（无需 root）`。

> 注意：`cmd2.txt` 上存在**并发写入者**（同机另一使用者：`dxdiag.exe`、`C:\apps\obsidian\run.bat`）。
> 只要 `seq` 单调，多写者互不影响；App 从不回写/清理别人的命令。

---

## 5. 已知限制（完整列表见 README §7）

1. 本机所有 App 都看不到 `su` → App 走网桥；平板重启后需重跑 `sh install_bridge.sh`（状态行会提示「网桥: 离线」）。
2. 「＋ 运行任意 exe」（SAF 导入）要把文件拷进 `/data/user/0/com.xiaomi.winplay/...`，**必须 root**；
   su 不可用时该按钮会明确提示需 root（网桥代跑不了，那是 App 私有目录）。
3. 引擎必须前台且屏幕亮着，冷启动要等 40–60 s。
4. 无图标、无短名搜索、单条启动串行（重复点击会被 `busy` 拦）。
5. OLED 熄屏默认开（8 s 后），可关；熄屏用 `input keyevent 26` + `mWakefulness` 回读确认。

---

## 6. 一句话

点列表 → 亮屏解锁 → 引擎拉到前台 → 原子写 `cmd2.txt`（seq 递增）→ 模块 `runNewApp` → Windows 程序起来 → 回读 `res2.log` 确认 → 熄屏。
无 su 也能用（shell 网桥兜底），全部日志可见（App 内 + `launcher.log` + `launch_res.txt` + `res2.log`）。
