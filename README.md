# WinPlay（小米 PC 引擎）在 Xiaomi Pad 7 Ultra 上的完整解锁记录

> 目标：本机跑起「全功能 PC 引擎」，等同小米平板 9 Pro Max 的状态；从 arm64 起步，逐步到 amd64。
> 设备：`jinghu` / 25032RP42C / XRING O1 / Mali-G925-Immortalis / Android 17 / 已 root（su 常驻）。

## 0. 结论速览

| 能力 | 状态 |
|---|---|
| 引擎脱离 Steam 启动（无 steam.exe 也能出桌面） | ✅ 约 40 s 到可用 |
| 任意 ARM64 exe（notepad/cmd/扫雷/Notepad++ ARM64） | ✅ 原生 |
| 任意 x86-64 exe（7-Zip / WinRAR / SumatraPDF / Notepad++ x64 / WPS 安装器） | ✅ FEX 转译 |
| i386/wow64（Steam 本体） | ✅ |
| **Electron 现代应用（VSCode 1.70 / 1.80.2 完整 UI）** | ✅ 需两处二进制补丁 + 三个开关 |
| WPS office6 主程序 | ⚠️ 起得来、拿到 surface，最终 hang（未解决） |
| 从平板本身点按启动（不依赖 PC/adb） | 🚧 启动器 App 开发中（`launcher/`） |

## 1. 引擎架构（已取证）

- `com.xiaomi.winplay` v457（`1.0_325134cda_r457`），appId 7100。
- Wine **10.5 定制版**（`wine-10.5-572-gf5135440de6 (Xiaomi WinPlay)`）+ experimental wow64 + **FEX-Emu**（`libwow64fex.dll` / `libarm64ecfex.dll`）+ DXVK 2.6.2 + vkd3d-proton。
- 图形：`wineandroid.drv` → **fastipc**（`memfd_create` + `eventfd` + `pidfd_open` + `epoll`，`/memfd:fastipc_sharedmem`）→ `ANativeWindow`/`dequeueBuffer`/`queueBuffer`。
- **fastipc fd 带 `O_CLOEXEC`**：fork/exec 不继承。每个 wine 进程都要自己注册回 app 内的 fastipc server，因此**只有经 `WindowChannel.runNewApp()` 创建的顶层进程才拿得到 Surface**；子进程/孙进程一律 `fastipc=0`（这就是「注入服务跑 wps.exe 出不了窗口」的根因）。
- Mali 支持无需怀疑：`libmivkhooker.so` 内含 spvtools 的 `CreateMaliOptimizationBarrierPass`，实跑日志 `Mi Vulkan Hooker for Wine initialized` + `OptimizationBarrierPass: scalar barriers = 11, vector barriers = 14`。

## 2. 启动通道

### 2.1 官方后门广播 `chen.test.run`（无需 root，任何 App 可发）
`WindowChannel.init()` 里 `registerReceiver(receiver, IntentFilter("chen.test.run"), 2 /*RECEIVER_EXPORTED*/)`，
收到广播就 `runNewApp("/sdcard/MiSide-GoldBerg/MiSideFull.exe", map, true)` —— **路径写死**。
```bash
su -c 'am broadcast -a chen.test.run'
```
缺点：只能跑 `/sdcard/MiSide-GoldBerg/MiSideFull.exe` 这一个文件名，且 `isUnixPath=true` 会让 wine 把路径转成 `\\?\unix\sdcard\...`，Electron 自定位会失败。仅适合快速验证。

### 2.2 Zygisk 模块 `winplay_run`（当前主力，已安装并生效）
注入 `com.xiaomi.winplay` 进程，反射调用
`MultiWindowManger.getWindowChannel()` → `WindowChannel.runNewApp(path, HashMap, isUnixPath)`，
支持**任意 DOS 路径**（`C:\apps\...`），这是 Electron 能跑起来的前提。
- 模块目录：`/data/adb/modules/winplay_run/zygisk/arm64-v8a.so`
- 命令文件：`/storage/emulated/0/wpz/cmd2.txt`，每行 5 段：
  ```
  exePath|isUnixPath|seq|args|envs
  C:\apps\7zip\7zFM.exe|0|1794933073||
  ```
  `seq` 必须比上次大（用 `date +%s`），`args`/`envs` 用 `;` 分隔。
- 结果日志：`/storage/emulated/0/wpz/res2.log`
- **注意**：`wine_run_app`（带 argv 的原生方法）被调用会让引擎进程直接崩，不要用；要传命令行参数请用 `.bat` 包装（见 §3.2）。

### 2.3 必须条件（踩过的坑）
- **屏幕亮 + `WineActivity` 在前台**，否则 `runNewApp` 静默无效（进程根本不出现）。
- 引擎启动 **>40 s**（无 Steam）或 60–90 s（带 Steam）才算可用；早发命令会丢。
- `dosdevices/` 只允许三条：`c: -> ../drive_c`、`d: -> /storage/emulated/0`、`z: -> /`。
  手工加 `dosdevices/unix` 会让引擎永久卡在「正在启动99%」。

## 3. 应用安装与启动

### 3.1 放进去
```bash
# 目录/zip 装法（自动 patch + 注册）
./wapp.sh install ./SomeApp-win-arm64.zip SomeApp

# 或者手工
adb push app.zip /sdcard/
su -c 'P=/data/user/0/com.xiaomi.winplay/files/prefix; mkdir -p $P/drive_c/apps/SomeApp && tar xf /sdcard/app.zip -C $P/drive_c/apps/SomeApp && chown -R 7100:7100 $P/drive_c/apps/SomeApp'
```

### 3.2 传命令行参数：用 `.bat` 包装
`runNewApp` 只吃路径，所以要参数就写个 bat（**必须 CRLF + 在 PC 上生成再 push**，`printf` 会把 `\a` 吃成响铃）：
```bat
@echo off
start "" "C:\apps\VSCode180\Code.exe" --no-sandbox --force-device-scale-factor=1 --disable-gpu
```
实测经 `bat → cmd → start` 链路启动的进程**仍能拿到 fastipc=1 + dmabuf surface**（已验证：7zFM 走 t1.bat 启动，91 fds / fastipc=1 / `dmabuf:VRI[WineActivity]#1`）。

### 3.3 启动
```bash
./wpctl.sh wake                     # 亮屏（亮度 1）
./wpctl.sh run 'C:\apps\SomeApp\App.exe'
./wpctl.sh ps                       # 看进程
./wpctl.sh shot /tmp/x.png          # 截图（会自动熄屏）
```

## 4. Electron / Chromium 现代应用的三个关键修复（本项目的核心成果）

Electron 在 Wine 上原本必死，实测有三层障碍，逐个击破：

### 4.1 `uisettings2_get_TextScaleFactor` 桩 → 21812 次调用 → GDI 句柄耗尽
日志特征：
```
fixme:ui:uisettings2_get_TextScaleFactor iface ... stub!      (×21812，指针每次 -0x6C0)
err:gdi:alloc_gdi_handle out of GDI object handles, expect a crash   (×5)
Unhandled exception code 80000003 (EXCEPTION_BREAKPOINT)
```
**修法**：加 `--force-device-scale-factor=1`。Chromium 的
`ui/display/win/screen_win.cc: GetMonitorScaleFactor()` 开头就 `if (Display::HasForceDeviceScaleFactor()) return ...`，
直接短路整条 WinRT/DPI 查询链，`uisettings2` 命中数从 21812 降到 **2**。
（把 prefix 版本改成 win7 也能绕过 —— `UwpTextScaleFactorImpl` 构造函数有 `GetVersion() < WIN10` 门 —— 但会触发「Windows 7 不再受支持」横幅，非必需。）

### 4.2 `EXCEPTION_ILLEGAL_INSTRUCTION`（0xC000001D）→ 二进制补丁
崩溃点反汇编定位：
```
2a4da3c:  d53b9d09   mrs x9, pmccntr_el0     ← EL0 读 PMU 计数器，Android 内核未开 PMUSERENR
```
整个 `Code.exe` 里就 **2 处**（Rt=9 / Rt=20）。补丁：改成读虚拟计数器 `cntvct_el0`：
```
0xd53b9d09 -> 0xd53be049      (mrs x9,  cntvct_el0)
0xd53b9d14 -> 0xd53be054      (mrs x20, cntvct_el0)
```
`wapp.sh` 的 `patch_pe()` 会自动扫所有 `0xd53b9d00|Rt` 变体并替换。

### 4.3 `EXCEPTION_STACK_OVERFLOW` → 抬高 PE 栈保留
```
err:seh:dispatch_exception code=c00000fd (EXCEPTION_STACK_OVERFLOW) stack 0x20990 (0x20000-0x21000-0x820000)
```
`Code.exe` 的 `SizeOfStackReserve = 0x800000`（8 MB）被 FEX/wine 下的深递归耗尽。补丁：PE32+ optional header `+0x48` 改成 `0x10000000`（256 MB）。`patch_pe()` 一并处理（PE32 用 128 MB）。

### 4.4 推荐开关组合（VSCode 1.70/1.80 实测通过）
```
--no-sandbox --force-device-scale-factor=1 --disable-gpu --disable-gpu-compositing \
--disable-features=VizDisplayCompositor --user-data-dir=C:\ud
```
（引擎自己还会追加 `--no-sandbox --in-process-gpu`。）

## 5. 实测矩阵

| 应用 | 架构 | 结果 | 备注 |
|---|---|---|---|
| `notepad.exe` / `cmd.exe` / `winemine` / `explorer` | ARM64 (0xaa64) | ✅ 完整 UI | 原生 |
| Notepad++ 8.7.1 | **ARM64** | ✅ 完整 UI | `shots/notepadpp-arm64.png` |
| Notepad++ 8.7.1 | x64 | ✅ | FEX |
| 7-Zip 24.08 `7zFM.exe` | x64 | ✅ 完整 GUI | `shots/vscode-1.70-7zip.png` 背景 |
| WinRAR 7.01 | x64 | ✅ | |
| SumatraPDF 3.5.2 | x64 | ✅ | |
| Steam 本体 + steamwebhelper | i386 + x64 | ✅ 商店页完整渲染 | 官方路径 |
| WPS 安装器（NSIS） | i386 stub | ✅ 能装（装出 12.1.0.25200） | 装完的主程序另说 |
| WPS office6 `wps.exe` | x64 | ⚠️ 起、拿 surface、建 `OpusApp` 窗口对象，最终 `anon_pipe_read` 挂死 | 未解决 |
| **VSCode 1.70.0**（Electron 19） | ARM64 | ✅ **完整 UI** | 三个补丁 |
| **VSCode 1.80.2**（Electron 22） | ARM64 | ✅ **完整 UI**（Win10 版本号下也 OK） | `shots/vscode-1.80-win10.png` |

## 6. 已知限制

1. **39 位用户虚拟地址空间**（`[stack]` @ `0x7fea389000`，上限 512 GB）。Electron ≥22 的 V8 sandbox 会尝试保留 1 TB；V8 有「部分保留」回退（1TB→…→8GB），加上 `--force-device-scale-factor=1` 与 Win7/8 版本号可强制走 8 GB 分支。实测 1.80.2 在 win10 下没有 `Failed to reserve` 也能跑。
2. 内存 16 GB 很紧：跑引擎 + 一个 Electron 应用后 free 常剩 100–400 MB。必要时 `kill -9` Steam/多余实例。
3. `wine_run_app`（argv 版）会让引擎崩溃 —— 只能用 `runNewApp` + bat 包装。
4. WPS office6 主程序未跑通（`anon_pipe_read` 挂死）。
5. 屏幕必须亮着才能发起新进程（这是引擎 Activity 生命周期决定的）。

## 7. 脚本

- `wpctl.sh` — 引擎控制：`boot`（无 Steam 起）/`bootsteam`/`stop`/`wake`/`run <winpath>`/`runu <unixpath>`/`ps`/`tail`/`layers`/`shot`/`off`/`dim`
- `wapp.sh` — 安装器：`install <zip|exe|dir> <name> [args]` / `list`
- `launcher/` — Android 启动器 App（构建中）

## 2026-11-18 更新：Electron 真跑通（VSCode 1.80.2，win10 版本号）

| 应用 | 架构 | 结果 |
|---|---|---|
| VSCode 1.80.2 | `0xaa64` Electron 22 | ✅ 完整 Welcome 页（Copilot 卡片/Walkthroughs/状态栏），`uisettings2` 只命中 2 次 |
| VSCode 1.70.0 | `0xaa64` Electron 19 | ✅ 同屏可与 7-Zip 共存 |
| VSCode 1.146（最新） | `0xaa64` Electron 3x | ❌ `allocate_virtual_memory ... size 15800000000 / 8000000000`（V8 sandbox 要 1.51 TB，设备仅 39 位 VA），win10/win7 均失败 |
| PuTTY 0.83 (x86) | `0x014c` | ✅ 92–105 fds，`fastipc=1`，wow64 通路正常 |
| Obsidian 1.7.7 (x64) | `0x8664` NSIS | ⏳ 安装器在 FEX 下解压极慢（250 MB 包，单核 75%） |

### 结论
- **Electron 能跑**，天花板在 **Electron 22（VSCode 1.80）**；Electron ≥25 的 V8 sandbox 需 1 TB 级连续 VA，39 位用户空间无解（无公开先例，见 docs/RESEARCH.md）。
- 三层修复缺一不可：`--force-device-scale-factor=1`（避免 DPI 路径打爆 65536 个 GDI 句柄）+ PMCCNTR→CNTVCT 补丁（EL0 读 `pmccntr_el0` 触发 SIGILL）+ `SizeOfStackReserve` 抬到 256 MB。
- 参数只能经 `.bat` 里 `start "" "exe" arg` 传递；直接调 `wine_run_app(argv)` 会抛异常（日志 `ERR wine_run_app threw`）甚至带崩引擎。
- `wpctl.sh run` 前置动作已加「亮屏 + `am start WineActivity`」：Activity 不在前台时 `runNewApp` **静默无效**（无进程、无日志），这是最容易误判失败的地方。

## 2026-11-18 02:00 现状：从「命令行能跑」到「平板点一下就开」

### 1. 有了一键启动器（APK）
`launcher/WinPlayLauncher.apk`（33 KB，`com.dsh.winplaylauncher`，纯 Java 零依赖）：
- 列表读 `/storage/emulated/0/WinPlay/apps.json`，点条目 → （必要时）亮屏 → 把 `WineActivity` 拉前台 → 写 `cmd2.txt`
  - **不会自动熄屏**：熄屏只是调试期我用的手段，正式使用一律不动屏幕（`wp_launch.sh` 第 3 参默认 0）
- 真机验证（我自己点的）：点「7-Zip」→ `launch_req.txt` 更新 → 网桥写 `cmd2.txt` = `C:\apps\7zip\7zFM.exe|0|1794936566||` → `res2.log` 报 `runNewApp returned OK` → `ps` 出现 `wine C:\apps\7zip\7zFM.exe`
- **本机特殊约束**：root 的 `su` 在 App 自己的 mount namespace 里不可见（`/proc/<pid>/root/system/bin/su` 对 Settings/SystemUI 也是 ENOENT）→ 启动器做了「su 直连 / uid 2000 网桥」双通道。**平板每次重启后要跑一次 `launcher/install_bridge.sh`** 才能用网桥通道。

### 2. 应用安装器（PC 侧）
`./wapp.sh install <zip|exe|dir> <name> [args...]`：解包 → 只 patch **可执行段**里的 stack/PMCCNTR → push → 注册进 apps.json。
`patch_pe.py` 单独可用；`.dll` 只抬栈、不碰 PMCCNTR（实测 `ffmpeg.dll` 有 3 处 4 字节巧合匹配，乱改会坏文件）。

### 3. 当前兼容矩阵（截至 02:00）
| 应用 | 架构 | 结果 |
|---|---|---|
| 7-Zip 24.08 / Notepad++ / SumatraPDF / WinRAR / PuTTY | x64 或 x86 | ✅ 完整 GUI |
| Notepad++ 8.7.1 | ARM64 | ✅ 最快，无 FEX |
| VSCode 1.70 / 1.80.2 | ARM64 Electron 19/22 | ✅ 完整界面（含 Copilot 推荐卡） |
| Obsidian 1.7.7 | ARM64 Electron 28 | ❌ `allocate_virtual_memory size 11000000000`（1.09 TB） |
| VSCode 1.146（最新） | ARM64 Electron 3x | ❌ 1.51 TB 同上 |
| WPS Office 12.1（x64/Qt5） | x64 | ❌ 起进程后卡 `anon_pipe_read`（等自己的 ksolaunch），无窗口 |
| LibreOffice 26.8.3（MSI 扁平解包） | x64 | ❌ `mergedlo.dll` 链接时缺 `libcrypto-3.dll`（MSI 里有改名表，裸解包名字对不上；补了同名副本仍报 not found，未解） |
| Steam（引擎自带） | i386 | ✅ 商店页完整渲染（曾用于验证 Mali+DXVK 通路） |

### 4. 结论
- **Electron 天花板 = Electron 22**（VSCode 1.80）：再新的版本 V8 sandbox 直接要 1.09–1.51 TB 连续虚拟地址，本机 39 位用户空间（512 GB）物理上不可能，win10/win7 都一样。
- **不需要 Steam**：`am start -n com.xiaomi.winplay/.WineActivity` 就能起桌面，40 s 可用；`winplay://opensteam` 只是顺带把 Steam 当壁纸。
- 参数必须走 `.bat`（`start "" "exe" args`）；直接 `wine_run_app(argv)` 会 `ERR wine_run_app threw`，甚至带崩引擎。

### 5. 踩坑补充（2026-11-18 01:50）
- **引擎「黑屏」状态**：反复 `am force-stop` + `kill -9 wine` 后，用 `am start -n .WineActivity` 起桌面可能得到一个「有 explorer 进程、屏幕全黑」的坏会话。可靠做法是回到 `am start -a android.intent.action.VIEW -d "winplay://opensteam?appid=0&devmode=1"`，45 s 后 Steam 商店页出现即健康（截图 >900 KB）。
- **PE32 的栈补丁只能抬到 16 MB**（`0x01000000`）：给 32 位 exe 设 128 MB（`0x08000000`）会让 wow64 启动直接失败（7-Zip x86 实测进程不出现）；`0x01000000` 正常。
- 判断「引擎是否真起来」不要只数 `explorer.exe` 进程（可能来自上一个会话），要看截图字节数或 `dumpsys SurfaceFlinger --list | grep wine`。

## 批量兼容测试（2026-11-18 夜）

新增 `test/wtest.sh` + `test/targets.tsv`：一条命令完成「下载 → 解包（含安装器）→ PE 补丁 → 推送 → 启动 → 判定 → 记录」。
判定用三项证据合成：进程存活 + fastipc 通道 + **屏幕像素签名变化**（比"进程数"可靠得多）。

```bash
./test/wtest.sh one vlc     # 单包
./test/wtest.sh batch       # 全量
./test/wtest.sh report      # 矩阵
```

**本轮 11 个新包结果见 [COMPAT.md](COMPAT.md)**：VLC / Audacity / MPC-HC / ScummVM / CPU-Z / Everything / FastStone / Node.js / Python / Git 全部 `render`；
未过的是 FFmpeg（包没下完）与 XnView MP（安装器私有封装解不开）。

关键结论：**Win32 原生 / Qt5 / Qt6 / SDL2 四大 UI 框架全通，x64 与 x86 都通，控制台程序（Node/Python/Git）可执行**——
开发链（git clone / npm install / python 脚本）在本机成立。

---

## 平板端一键安装（v2 启动器，2026-11-18）

**目标**：在平板上不碰电脑也能装 Windows 程序，并且默认不启动 Steam。

### 安装链路（实测通过）

```
App 选 zip/exe
  → 解压到 /storage/emulated/0/WinPlay/staging/<dir>      （App 自己，无需 root）
  → 写 /storage/emulated/0/wpz/install_req.txt           （seq|dirName|stagingPath）
  → 命令网桥（root，init mount namespace）跑 install.sh
  → cp -a 到 prefix/drive_c/apps/<dir> + chown 7100:7100
  → 写回 install_res.txt（rc=0）
App 收尾：注册 apps.json + 询问是否加桌面入口
```

关键点：
- **必须装在 `C:\\`（prefix）里**。实测 `D:\\WinPlay\\apps\\7zFM.exe`（/sdcard）在 `runNewApp` 里静默无效——
  引擎只接受 prefix 内的 C 盘路径。
- App 自己跑 `su` 在 root + 该 ROM 上会 `error=13 Permission denied`（su 不在 App 的 mount namespace），
  所以写 prefix 由**以 root 启动的命令网桥**代做；App 只写请求文件。
- 网桥必须用 root 起：`adb shell 'su -c "nohup setsid sh /data/local/tmp/wp/wp_bridge.sh &"'`
  （`launcher/install_bridge.sh` 已改成用 su 启动，并会顺带推送 `install.sh`）。

### 桌面入口（这台机器的限制）

`ShortcutManager.requestPinShortcut` 在本机 **不可用**：系统确实拉起了
`com.miui.home/.launcher.AddItemActivity`（`action=android.content.pm.action.CONFIRM_PIN_SHORTCUT`），
但 Flutter 版 MIUI 桌面在 ~30 ms 内自己 `finish()`（`sf.setBuffer but null`），快捷方式不会落地。

替代方案（已实现并验证）：
- App 启动时发布 **动态快捷方式**：长按「WinPlay Launcher」图标 → 直接看到已安装程序（最多 5 个，系统上限）。
- 快捷方式/外部调用的 URI：`winplaylauncher://app/<名字>` → `LaunchActivity` → `MainActivity` →
  `wp_launch.sh` → Zygisk 模块 `runNewApp`。实测 `adb shell am start -a android.intent.action.VIEW -d "winplaylauncher://app/testapp"`
  后 `wine C:\apps\testapp\putty.exe` 正常起来。

### Steam 开关

界面新增「启动引擎时附带 Steam（默认关：只起 Windows 桌面）」，写 SharedPreferences（`wp`/`steam`），
作为 `wp_launch.sh` 的第 4 个参数：
- `steam=0`（默认）：引擎没跑时 `am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity`，只起 Windows 桌面（约 40 s）
- `steam=1`：`am start -a android.intent.action.VIEW -d "winplay://opensteam?appid=0&devmode=1"`（约 60–90 s）

### 实测记录（2026-11-18 10:2x）

- `testapp.zip`（putty.exe 1.4 MB）→ 解压 1 条目 → 网桥安装 `rc=0` → `apps.json` 12 条 → 从 URI 启动成功（`wine C:\apps\testapp\putty.exe`，fastipc 正常）。
- 动态快捷方式：`dumpsys shortcut` 里已登记 5 条，`bitmapPath=/data/system_ce/0/shortcut_service/bitmaps/com.dsh.winplaylauncher/*.png`。

## 安装型程序（C:\Program Files\*）的启动要点

从启动器直接 `runNewApp("C:\Program Files\<App>\<App>.exe")` 会**秒退**：日志只到
`RtlSetCurrentDirectory_U dir L"C:\Program Files\<App>\"` 就没有任何 err 行——程序在
拿自己的 `GetModuleFileName` 目录前就退出了。

`wp_launch.sh` 已自动处理：路径以 `C:\Program Files\`/`C:\Program Files (x86)\`
开头时，先在 `C:\windows\_wpwrap.bat` 生成

```bat
@echo off
cd /d "C:\Program Files\<App>"
start "" "<App>.exe" <args>
```

再把 bat 交给 `runNewApp`。实测 WinRAR 7.01 走这条链路可以正常出主窗口
（`WinRAR (evaluation copy)`，标题下 `C:\Program Files\WinRAR`）。

> 仍建议给每个安装型程序单独建 bat（`C:\windows\<app>.bat`）并在 apps.json 里指向它，
> 这样能显式控制参数与工作目录，不必依赖自动包装。

## GPU 3D 通路（已实测打通）

| 项 | 结果 | 证据 |
|---|---|---|
| D3D11 设备创建 | ✅ `hr=0x0`，feature level **11_0** | 自编 `test/d3d/d3dtest.exe`：`C:\d3dtest.txt` |
| 显示适配器 | **Mali-G925-Immortalis**（vendor 0x13b5 / device 0xd8300010） | 同上，来自 `IDXGIAdapter::GetDesc` |
| 帧率 | **60.2 fps**（900×600，vsync 限） | 同上 `frames=361 in 6s` |
| OpenGL | **GL 4.6 Core**（Mesa 25.0.1） | `test/d3d/glinfo.exe`：`GL_VERSION = 4.6 (Core Profile) Mesa 25.0.1` |
| GL 后端 | **zink over Vulkan 1.3** | `GL_RENDERER = zink Vulkan 1.3(Mali-G925-Immortalis (ARM_PROPRIETARY))` |
| 3.2 Core 上下文 | ✅ 可创建 | `core3.2 ctx=0x2` → `CORE_GL_VERSION = 4.6 (Core Profile)` |

结论：图形栈是 `D3D → wined3d → OpenGL → zink → Vulkan → Mali`，
以及 `GL → zink → Vulkan → Mali`。**不是软件渲染**（llvmpipe 会报 `llvmpipe`，这里报 Mali）。

## 启动器应用矩阵（2026-11-18 全量复测）

全部 12 项均能被 `runNewApp` 拉起并存活（进程级验证）：

7-Zip / Notepad++ ARM64 / Notepad++ x64 / SumatraPDF / WinRAR 7.01 /
VSCode 1.80 / VSCode 1.70 / putty-x86 / VSCode146 / obsidian / seven32 / testapp

两条启动约定（已写进 `wp_launch.sh`，无需手工干预）：

1. **`C:\Program Files\*`** → 自动生成 `C:\windows\_wpwrap.bat`（`cd /d` + `start`）
2. **带参数的程序** → 自动生成 `C:\windows\_wpargs.bat`（参数写进 bat，避免走
   `wine_run_app`——该通道实测会抛异常 `ERR wine_run_app threw`）

## Minecraft（Fabric + Sodium）：帧率从 32 → 67 fps

### 为什么原版卡
| 指标 | 原版 1280×720 | Fabric+Sodium 1280×720 |
|---|---|---|
| MC 自报 fps | 32 | **67**（1280×720）／**52**（1604×1014，原版同级仅 26） |
| MC 自报 GPU | 11–23% | 23% |
| `javaw.exe` CPU | 277–285% | ~174% |
| 结论 | CPU 打满 | CPU 压力减半 |

原版瓶颈在 CPU 侧（zink 把每条 GL 调用翻译成 Vulkan + MC 原版区块渲染），GPU 长期 <25%。

### 安装（已焊进设备，可复现）
1. Fabric 安装器（PC 端，需 Java 17+）：
```bash
java -jar fabric-installer-1.1.0.jar client -dir /tmp/mcdir -mcversion 1.20.1 -loader 0.20.1 -noprofile
```
   要求 `-dir` 下已有 `versions/1.20.1/{1.20.1.jar,1.20.1.json}`（直接拿 bundle 里的 `client.jar` + `v1201.json`）。
2. 把新增库推到设备：`libraries/net/fabricmc/**`、`libraries/org/ow2/asm/**`、`versions/fabric-loader-0.20.1-1.20.1/`。
3. mods（Modrinth，1.20.1 + fabric）：
   - `sodium-fabric-0.5.13+mc1.20.1.jar`（971552 B）
   - `lithium-fabric-mc1.20.1-0.11.4.jar`（691483 B）
4. 启动 bat 用 `net.fabricmc.loader.impl.launch.knot.KnotClient` 作主类，classpath = 原版 58 项 + 8 个 Fabric 库（`asm 9.10.1`×5、`fabric-loader 0.20.1`、`intermediary`、`sponge-mixin 0.17.5`）。生成器见 `mc/mkbat.py`（`1` 表示用 Fabric）。

### 已注册到启动器
- **Minecraft 1.20.1** → `C:\windows\mc_fab_hi.bat`（1604×1014，Fabric+Sodium）
- **Minecraft 流畅版** → `C:\windows\mc_fab.bat`（1280×720，Fabric+Sodium，67 fps）

### 附带修掉的两个体验问题
- bat 里改用 `start "" javaw.exe ...` + `exit`：**不再弹出黑色控制台窗口**盖住游戏
- `--quickPlaySingleplayer "New World"`：直接从启动器进存档，省掉主菜单点击（`Play Selected World` 按钮在 wine 下点不动）

### 已知限制（Minecraft 侧）
- 32 位无关：`java.exe` PE machine = `0xaa64`，是 64 位 ARM64 原生 JVM，`-Xmx3G` 合法
- **设备用户 VA 只有 39 位（512 GB）**：Electron ≥25 类程序起不来（V8 sandbox 要 1.09–1.51 TB 预留），与 MC 无关
- 16 GB 内存没有单进程限额（`RLIMIT_AS/RSS = unlimited`、cgroup `memory.max = max`）；真正的压力来自 zram 已用 3.9/16 GB

### WPS / Office 套件
- 三个版本（WPS 12.1.0.29670、12.1.0.25200「WoA 内测」、11.1.0.10314）都试过，**均不出可用界面**
- 根因：WPS 的界面是 **CEF（`office6/addons/kcef` + `promecefpluginhost.exe`）**，CEF 在本引擎上合成失败 → 窗口空白；WPS 12 还额外撞上 wine 缺失的 WMI coclass `WbemRefresher`（`{c71566f2-561e-11d1-ad87-00c04fd8fdff}`）导致 C++ 异常
- 顺带修掉一个坑：WPS 的 `ldping64.exe` 崩溃会弹 Wine Debugger 模态框卡住整个会话 → 已在测试脚本里关闭崩溃对话框（见 `COMPAT.md`）

## Firefox / WPS 验证结论（2026-11-18）

- **Firefox 160.0.1 Windows-ARM64（原生）**：界面能起（标签栏/地址栏/欢迎页全部正常渲染），但**内容进程 `-contentproc` 起不来**，`file://` 与 http 页面都是空白 → 目前不可用。注意它的栈上限断言 `MOZ_RELEASE_ASSERT(stackSize <= 32MB)`，不能用 256 MB 栈补丁，必须 32 MB。
- **WPS Office**：12.1.0.29670 / 12.1.0.25200 / 11.1.0.10314（2019）三个版本全部不通过。根因是界面走 **CEF**（`kcef`+`promecefpluginhost.exe`）+ WMI 的 `WbemRefresher`（wine 未注册）→ 抛 `e06d7363` 后退出或只剩灰窗。详见 `COMPAT.md`。

## 如何把平板里的文件放进 Wine 的 C 盘

盘符映射（`prefix/dosdevices/`）：

| Wine | 实际路径 | 说明 |
|---|---|---|
| `C:\` | `/data/user/0/com.xiaomi.winplay/files/prefix/drive_c` | **唯一能被 runNewApp 启动的位置**，属主必须是 `7100:7100` |
| `D:\` | `/storage/emulated/0` | 只读参考；**uid 7100 读不到别的 App 建的文件**（实测 `Permission denied`） |
| `Z:\` | `/` | 同上，系统分区只读 |

### 方法 1（推荐，PC 侧一条命令）
```bash
./put.sh 本地文件 [C盘子目录]        # 默认 apps/_inbox
# 例：./put.sh ~/Downloads/setup.exe apps/foo
# 输出：✓ Wine 里路径: C:\apps\foo\setup.exe
```

### 方法 2（平板侧，无需 PC —— 用我做的启动器）
启动器 →「＋ 安装 / 运行（exe 或 zip）」→ 选 zip → 自动解压并装到 `C:\apps\<目录名>`（走 root 网桥，自动 chown）。
单个 exe 也可以直接选。

### 方法 3（平板侧，MT管理器/文件管理器 + root）
把文件复制到
`/data/user/0/com.xiaomi.winplay/files/prefix/drive_c/apps/`
然后 `chown -R 7100:7100` 该目录（否则 App 打不开/存不了盘）。

### 方法 4（Wine 内部，D: → C:）
前提：源文件对 uid 7100 可读。实测 **`/sdcard/Download` 不可读**（`copy` 报「拒绝访问」），
因为 Android 把这些文件记在别的 uid 名下（`u0_a210:media_rw 0660`），WinPlay 是系统 appId `7100`，不在组内。
只有 WinPlay 自己写的目录（或 root 建且 777 的目录）才能读。

### 让 Windows 程序直接读写内置存储（`share.sh`）

**实测结论：本机 wine 只暴露 `C:` 盘。** `dosdevices/` 里的 `d:`/`z:` 虽然是软链，但探测
`for %d in (C D E Z) do if exist %d:\` 只有 `C: OK`，D/E/Z 全部不存在；改链接目标、换相对软链、
重启 wineserver 都无效——这是 Xiaomi 定制 wine 的限制。所以只能把存储挂到 **C: 下的目录**。

```bash
./share.sh readall          # 整个内置存储 -> C:\sdcard（走 raw f2fs，绕开 FUSE）
./share.sh allow Download   # 给某个目录开可读（DCIM / Documents 各来一次）
./share.sh setup            # /sdcard/WinShare <-> C:\share（双向读写）
./share.sh publish          # Windows 写的文件对其他 App 可读
./share.sh status | off
```

实测（同一 wine 进程内）：
```
dir C:\sdcard\Download         → 列出真实文件（123云盘 / 1DMP / DSH MOBILE / *.zip …）
type C:\sdcard\Download\sd2.txt → rootmade      ← 读到内容
echo hello-final > C:\share\final.txt
                                 → /data/media/0/WinShare/final.txt 15 B，属主 product_hyperengine  ← 写入成功
```

原理：把 raw f2fs 层（`/data/media/0`）bind 进引擎的 mount namespace 并放开 `o+rX`，
wine（uid 7100）按普通 unix 权限即可读写，绕开 FUSE「文件属主 = 创建它的 App」的限制。

注意：① `/data/media` 需 `o+x`（已改，仅可进入、未放开读）；② 挂载在**引擎重启后失效**，
重跑 `setup`/`readall`；③ `readall` + `allow` 属放宽权限，`off` 可撤。

---

## 启动器自动挂载内置存储（`wp_mount.sh`）

**目标**：从启动器点任何一个程序，Windows 侧立刻能读写平板存储，不需要手动跑脚本。

**调用链**：

```
点图标 / winplaylauncher://app/<名字>
   → MainActivity.launchApp()
   → wp_launch.sh（网桥或 root 执行）
       ├─ 1 亮屏解锁（必要时）
       ├─ 1.5 / 1.6 包一层 bat（Program Files、带参数的程序）
       ├─ 2 引擎没起就拉起来，等 Windows 桌面就绪
       ├─ 3 确保 WineActivity 在前台（否则 runNewApp 静默无效）
       ├─ 3.6 wp_mount.sh          ← 本次新增：挂载 + 放权限
       ├─ 4 写 cmd2.txt（seq 递增）
       └─ 5 回读 res2.log 确认
```

**`wp_mount.sh` 做三件事**（全部幂等）：

1. `chmod o+x /data/media /data/media/0` —— 否则 uid 7100 连穿越都不行；
2. 给 `/data/media/0` 补 `o+rX`（**只做加法**）：首次全量、之后只处理 `-newer` 戳的新文件，
   在后台跑，**整棵 `Android/` 目录跳过**（别的 App 私有数据绝不碰）；
3. 在引擎的 mount namespace 里 bind：
   - `C:\sdcard` ← `/data/media/0`（整盘可读；写入仍受 MediaProvider 约束）
   - `C:\share`  ← `/data/media/0/WinShare`（双向读写，与 `/sdcard/WinShare` 同一个目录）

**为什么不会影响其他 App**：改的是 **raw f2fs inode**，只加 `o+r`/`o+x`，`owner`/`group` 一个字节没动；
而其他 App 走的是 **FUSE**（`/storage/emulated/0`），权限由 MediaProvider 按数据库决定。实测对照：

```
FUSE 视图（其他 App 看到）  -rw-rw----  u0_a210  media_rw   /sdcard/Download/sd2.txt   ← 与改动前一致
raw 视图（wine 看到）       -rw-rw-r--  u0_a210  media_rw   /data/media/0/Download/sd2.txt
其他 uid 写测试             adb shell 'echo x > /sdcard/Download/_permtest.txt'  → 成功
```

**实测（启动器点 7-Zip 后）**：

```
mount.log:  bind OK: C:\sdcard <- /data/media/0
            bind OK: C:\share  <- /data/media/0/WinShare
            perm: 首次全量 o+rX 完成          （5.4 万条目，< 1 s）
wine 内:    dir C:\sdcard\Download   → 123云盘 / 1DMP / *.zip …
            type C:\sdcard\Download\sd2.txt → rootmade
            echo launcher-auto-mount > C:\share\auto.txt → /data/media/0/WinShare/auto.txt 23 B
```

**手动入口**：

```bash
adb shell "su -c 'sh /data/local/tmp/wp/wp_mount.sh'"          # 立即挂载
adb shell "su -c 'sh /data/local/tmp/wp/wp_mount.sh status'"   # 看是否已挂
adb shell "su -c 'sh /data/local/tmp/wp/wp_mount.sh off'"      # 卸载
# PC 侧等价入口：./share.sh setup / readall / allow <dir> / status / off
```

**注意**：挂载随引擎进程存在，引擎重启后由下次启动自动补回；`o+rX` 是改 inode，重启后仍在。

---

## 打包成 root 模块（规划，暂不实现）

项目完成后按下面的边界切分，保证「每一步都能被一个 KSU 模块完整包含」：

```
winplay-pcengine/
├── module.prop                 # id=winplay_pcengine, version, description
├── customize.sh                # 安装期：解包、放文件、pm install APK、提示重启
├── post-fs-data.sh             # 早期：目录骨架 + /data/media 的 o+x（不依赖引擎）
├── service.sh                  # 开机后：等引擎起 → 起网桥 → wp_mount → 发布动态快捷方式
├── uninstall.sh                # 卸载：停网桥、删脚本、可选卸载 APK
├── system/priv-app/WinPlayLauncher/WinPlayLauncher.apk   # 启动器（或用 pm install）
├── zygisk/arm64-v8a.so         # winplay_run（反射调 WindowChannel.runNewApp）
└── common/
    ├── wp_bridge.sh  wp_launch.sh  wp_mount.sh  install.sh
    └── apps.json               # 预置应用清单（7-Zip / VSCode / MC …）
```

对应的「源码 → 模块」映射：

| 模块内路径 | 当前仓库来源 |
|---|---|
| `zygisk/arm64-v8a.so` | `module/`（源码 `winrun8.cpp` + `zygisk.hpp`，编译见《Zygisk 模块》一节） |
| `common/*.sh` | `launcher/scripts/*.sh`（**唯一来源**，不要再手改设备上的副本） |
| `system/priv-app/.../WinPlayLauncher.apk` | `launcher/build.sh` 产物 |
| `service.sh` 里的挂载/网桥逻辑 | `launcher/scripts/wp_mount.sh` + `wp_bridge.sh` |

**模块化时要守的三条**：

1. **不依赖 `adb` 与 `/data/local/tmp`**：脚本一律放 `$MODDIR/common`，用 `$MODDIR` 定位；
2. **不写死引擎 pid**：`pidof com.xiaomi.winplay` 每次现取（`service.sh` 里轮询等待）；
3. **所有 chmod / bind 都幂等**：`service.sh` 可能在开机、引擎重启后多次执行。

安装器与「一键装应用」留在 APK 里（需要 SAF 选文件、需要 UI），模块只负责**底座**：
Zygisk 通道 + 脚本 + 挂载 + 快捷方式发布。这样模块体积小、可复用，APK 可独立升级。

---

## 桌面图标？任务栏？——都不存在，用 `wintask.exe` 任务面板代替

### 实测结论一：wine 的 explorer 不画桌面图标

`C:\users\xiaomi\Desktop` 里**早就有** `Steam.lnk`、`WPS Office.lnk`、`LibreOffice 26.8.lnk`（安装器写的），
我又额外放了 `readme.txt` 和 `记事本.exe`，屏幕上**始终一片空白壁纸**——wine 的 `explorer.exe /desktop`
只画壁纸，没有 `SHELLDLL_DefView`/`SysListView32` 那套图标视图。所以「往桌面加图标」这条路在 wine 里走不通，
除非换 shell（Cairo Desktop / Blackbox 之类，都得额外跑一堆依赖），不划算。

### 实测结论二：最小化后确实找不回来（但可以修）

wine 窗口有 `−` 按钮，最小化后窗口消失，而这个引擎**没有任务栏**，也没有 Alt+Tab —— 程序就"没了"（进程还活着）。

### 解决办法：`wintask.exe`（常驻置顶小窗，自己写的 ARM64 原生程序）

```
┌ WinPlay Task Panel ─────────────┐
│ [W] C:\users\xiaomi\            │  ← 当前所有可见窗口（含最小化的，标 [min]）
│ [W] [min] PuTTY Configuration   │
│ [A] 7-Zip                       │  ← 应用清单（读 C:\windows\wintask_apps.txt）
│ [A] VSCode 1.80                 │
│ [A] Minecraft 流畅版            │
│  Run / Restore │ Minimize │ Refresh │
│  Exit          │ [W]=window  [A]=app │
└─────────────────────────────────┘
```

- **双击列表项** 或选中后按 `Run / Restore`：窗口项 → `ShowWindow(SW_RESTORE)` + `SetForegroundWindow`；应用项 → `CreateProcessW` 启动
- `Minimize`：把选中的窗口最小化（方便腾地方）
- `Refresh`：重新枚举（最小化的窗口会带 `[min]` 前缀）
- 判定最小化用 `GetWindowPlacement().showCmd ∈ {2,6,7}`，因为 `IsIconic()` 在这个 wine 上恒为 0
- 窗口 `WS_EX_TOPMOST | WS_EX_TOOLWINDOW`，可拖标题栏移动

**源码与编译**（`pcpanel/`，不需要 CRT，不依赖 MSVC）：

```bash
./pcpanel/build.sh        # llvm-dlltool 造 user32/kernel32 导入库 + clang --target=aarch64-pc-windows-msvc + lld-link
# 产物 pcpanel/wintask.exe 6656 B
adb push pcpanel/wintask.exe /data/local/tmp/ && \
adb shell "su -c 'cp /data/local/tmp/wintask.exe /data/user/0/com.xiaomi.winplay/files/prefix/drive_c/windows/ && chown 7100:7100 ...'"
```

**自动化（已焊进启动流程）**：`wp_launch.sh` 第 3.7 步会
1. 把 `wintask_apps.txt`（启动器 APK 维护的 TSV：`name⇥exe⇥args`）拷进 `C:\windows`；
2. 如果 `wintask.exe` 没在跑，就通过 module 拉起来（`C:\windows\wt.bat`）。

所以**从启动器点任何程序，面板都会自动出现**；APK 每次保存/加载 `apps.json` 都会顺带刷新这份 TSV
（`MainActivity.syncPanelList()` / `writePanelList()`）。

### 为什么不用 Android 悬浮窗

Android 侧的 overlay 只能把一个 App 拉到前台，**看不到 Wine 内部的 HWND**，无法还原某个被最小化的窗口；
而 `wintask.exe` 就住在 Wine 里，能直接 `ShowWindow`，这是唯一能"把最小化的窗口叫回来"的层级。
两者可以并存：overlay 负责"切 App"，`wintask` 负责"切窗口"。

### KSU 模块里的位置

```
winplay-pcengine/
└── common/
    ├── wintask.exe          # 由 pcpanel/build.sh 产出
    └── wintask_apps.txt     # 预置清单（运行时由 APK 覆盖）
service.sh 里：cp 到 $PREFIX/drive_c/windows/ + chown 7100:7100（引擎起来后执行一次）
```

### 面板的三种状态与「关掉之后怎么叫回来」

**① 没有关闭按钮。** 主窗口用 `WS_POPUP|WS_CAPTION`（**故意不带 `WS_SYSMENU`**），所以标题栏右侧根本没有 `×`，
点不到、也点不掉。要退出只能按下方的 `Exit` 按钮（或外部 `kill`）。

**② 可折叠成图标大小。** 底部 `<<` 按钮把窗口缩到 **70×90**，只留一个 `WP` 按钮浮在屏幕上；再点 `WP` 就展开回 400×580。
实现就是 `SetWindowPos` + 逐个 `ShowWindow(子控件, 0/1)`，没有额外进程。

**③ 三条唤起路径（任选）**

| 场景 | 做法 |
|---|---|
| 手机上点 | 启动器顶栏 **`▣ 面板`** 按钮 |
| 命令行 / 自动化 | `adb shell am start -n com.dsh.winplaylauncher/.MainActivity -e panel 1` |
| 顺手 | 从启动器点**任何**应用 —— `wp_launch.sh` 第 3.7 步发现 `wintask.exe` 没在跑就会先把它拉起来 |

所以即使你按了 `Exit`、或者用 `killall wintask.exe` 把它杀了，**下次点任何应用它自己就回来了**；
想立刻要它，点 `▣ 面板` 即可（约 3–5 秒出现）。

截图：`shots/wintask-collapsed.png`（折叠态，只有一个 `WP`）、`shots/wintask-expanded.png`（展开态）。

#### 任务面板的三个后续调整（19:20 用户反馈）

1. **折叠后贴右上角**（`ny=6`、`nx=SW-70-6`）：`collapse()` 里 `GetSystemMetrics(0/1)` 现算屏幕尺寸再 `SetWindowPos`；
   `expand()` 用 `GetWindowRect` 取折叠后的右边缘，向左展开 400 宽 —— 所以展开时始终"从角落长出来"，不会跑位。
2. **删掉 `Exit` 按钮**：面板不再提供退出入口（要收掉只能外部 `killall wintask.exe`，或从启动器关闭开关）。
3. **启动器里加了开关**（`CheckBox`「显示 Wine 任务面板」，`SharedPreferences: wp/panel`，默认开）：
   - 开：`wp_launch.sh` 第 3.7 步同步清单并拉起面板；
   - 关：先 `killall -9 wintask.exe`（脚本非 root 时自动走 `su -M`），再跳过拉起。
   实测日志：`panel: 已按设置关闭 wintask` / `panel: 拉起任务面板 seq=...`。

> 踩坑：这个 ROM 的 toybox `pidof` 对 PE 进程名不可靠（`wintask.exe` 在跑却返回空），
> 判活统一改成 `ps -A -o ARGS | grep -q "wintask[.]exe"`。

#### 资源管理器：wine 自带 `winefile.exe`，能用

`C:\windows\system32\winefile.exe`（483328 B，wine 自带 File Manager）实测可用，中文界面完整：

- 菜单：文件/编辑/查看/书签/工具/帮助；工具栏：新建文件夹、向上、后退、前进、刷新、删除
- 路径栏可直接输入 `C:\...`；列表列头：名称 / 大小 / 修改时间 / 创建时间 / 注释 / 文件夹 / 文件
- 状态栏「选定 0 / 16 个项目」；打开、删除、复制、粘贴、剪切、重命名都在菜单里（标准 MS 快捷键）
- 截图：`shots/winefile.png`

启动方式（已注册进 `apps.json`，名字「文件管理器 (winefile)」）：

```bat
@echo off
start "" "C:\windows\system32\winefile.exe" "C:\apps"
exit
```

配合 `wp_mount.sh` 挂好的 `C:\sdcard`（= `/data/media/0`），可以在 winefile 里直接浏览平板的整个内置存储。

#### 修掉面板里的中文乱码（19:52 用户反馈）

现象：`[A] Minecraft 流畅版` / `[A] 文件管理器 (winefile)` 显示成方框 + 竖条。

原因两层：
1. 列表项文本是我们自己从 `wintask_apps.txt`（UTF-8）读进来再转 UTF-16 的，**解码本身没问题**；
2. 真正的问题在**字体**：`LISTBOX`/`BUTTON` 用的是进程默认 GUI 字体，缺少 CJK 字形 → 所有中文变豆腐块。

修法（`wintask.c`）：`WM_CREATE` 里建完控件后调 `apply_fonts()`，
`CreateFontW(-15,…, L"Noto Sans CJK SC")`，失败依次退 `Microsoft YaHei` → `MS Shell Dlg`，
再 `SendMessageW(ctrl, WM_SETFONT(0x0030), (long long)f, 1)` 应用到列表、四个按钮、提示标签。
（`gdi32` 是新增的导入库：`g32.def` 里只导 `CreateFontW` / `DeleteObject`，`build.sh` 已加进链接。）

验证：同一份 TSV 下，`Minecraft 流畅版`、`文件管理器 (winefile)` 全部正常显示。截图 `shots/wintask-panel-cjk.png`。

> 踩坑（19:58 用户反馈「文件管理器打不开」）：`winefile.exe` 在 **`C:\windows\system32\`** 里，不在 `C:\windows\`。
> 第一次的 bat 写成 `C:\windows\winefile.exe`，cmd 直接报「无法将 ... 识别为内部或外部命令」(EXIT=9009)，
> 而 wine 日志里连 `build_module Loaded ... winefile.exe` 都没有 —— 因为根本没走到 loader。
> 正确路径下进程 `wine C:\windows\system32\winefile.exe C:\apps` 正常起来，界面见 `shots/winefile.png`
> （菜单 文件/磁盘/视图/选项/窗口/帮助，左侧树 + 右侧列表，状态栏「972 GB 中剩余 705 GB」）。

#### winefile 的「两个标题栏」是 MDI，不是 bug（20:05 用户反馈）

winefile 是 **MDI（多文档界面）** 程序，和 Windows 3.x 的文件管理器同源：

```
┌ Wine 文件管理器 ───────────────[框架窗口：外层标题栏 + 菜单 + 工具栏]─┐
│  ┌ [C:\apps] ─────────────[MDI 子窗口：内层标题栏 + 自己的 ×]──────┐ │
│  │  左侧树 + 右侧文件列表                                            │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

- 上面那条是**框架窗口**，下面那条是**子窗口**（每打开一个目录就是一个子窗口）；
- 点子窗口的 `×` = **关掉这个目录窗口**，不是关程序；框架还在，但里面空了 —— 这就是「关了就回不到原来的大小」的原因：
  那个子窗口对象已经销毁，尺寸/滚动位置都随之丢失，必须重新打开一个；
- 属于 winefile 自身的设计（`窗口(W)` 菜单里只有 `层叠 / 平铺 / 图标排列` 和子窗口列表，没有「复原上一个子窗口」）。

**更接近 Windows 资源管理器的替代品：`explorer.exe`（wine 自带 shell）**

```bat
@echo off
start "" "C:\windows\explorer.exe" "C:\apps"
exit
```

实测效果（`shots/explorer-apps.png`）：**只有一个标题栏**，标题就是当前目录名（`apps`），
下面是 前进/后退/向上 工具栏 + 「位置: apps」地址栏，左侧 `收藏夹 / 桌面 / 我的电脑 / 文档 / 回收站`，
右侧 `名称 / 大小 / 类型 / 修改日期 / 属性` 详细列表，空白处右键有 `刷新 / 粘贴 / 粘贴快捷方式 / 属性`。

已知限制：
- 目录**条目特别多**时会显示空白（`C:\sdcard` = `/data/media/0` 有 54733 项，实测空列表；`C:\apps` 这种几十项的正常）；
- 传入不存在的路径会退化成「桌面」视图（能看到 `我的电脑 / 文档 / 回收站` 和 Desktop 里的 `.lnk`）。

因此启动器里现在有两条：

| 名字 | 目标 | 特点 |
|---|---|---|
| `文件管理器 (Explorer)` | `C:\windows\xfm.bat` → `explorer.exe C:\apps` | 单标题栏、最接近资源管理器 |
| `文件管理器 (winefile / MDI 双栏)` | `C:\windows\wf.bat` → `system32\winefile.exe C:\apps` | 双栏树+列表，但 MDI 双子窗口 |

#### Explorer++ 1.4.0（x64，FEX）+ 官方中文语言包：可用（20:15）

- 来源：`https://github.com/derceg/explorerplusplus/releases/download/version-1.4.0/explorerpp_x64.zip`（2392706 B）
  语言包：`.../explorerpp_translations.zip`（407498 B，内含 `Explorer++ZH_CN.dll` / `Explorer++ZH_TW.dll` 等 30 余种）
- 部署：`C:\apps\explorerpp\`（`Explorer++.exe` 4100096 B，PE machine `0x8664` → 走 FEX），语言 DLL 与 exe 同目录
- **汉化开关是命令行参数，不是自动跟随系统**：
  ```bat
  start "" "C:\apps\explorerpp\Explorer++.exe" "C:\apps" --language ZH_CN
  ```
  选项优先级（官方 History 记载）：命令行 > 上次保存的设置 > 系统默认语言。
  **参数顺序要为「路径在前、`--language` 在后」**，否则 `C:\apps` 会被当成语言值吞掉（实测标题变成「我的电脑」）。
- 实测中文界面：标题 `apps-Explorer++`，菜单 `文件(F) 编辑(E) 选择(S) 视图(V) 操作(A) 转到(G) 书签(B) 工具(T) 帮助(H)`，
  `地址` / `文件夹` 栏，状态栏 `43 项目 / 1.21 KB / 705 GB 剩余 (73%)`。截图 `shots/explorerpp-zh.png`。
- 两个现存小毛病（wine 下的观感问题，不影响功能）：
  1. 左侧「文件夹」树默认宽度太窄，中文名被截成 `ir` / `P` / `t`，需要手动拖分隔条；
  2. 图标区底部有一条黑色未重绘区（首次绘制时出现，切换视图/滚动后一般会补上）。
- 启动器条目：「文件管理器 (Explorer++)」→ `C:\windows\epp.bat`（现在排第一）。

#### 任务面板新增 `Kill` 按钮（20:17 用户要求）

- 控件：`Kill`（id 1006），位置 `(58,490,100,34)`，和 `<<` 同一行；提示文字右移到 `(166,494,226,26)`。
- 行为：取列表当前选中项 → `GetWindowThreadProcessId` 拿到 PID →
  `OpenProcess(PROCESS_TERMINATE=1)` → `TerminateProcess(p,1)` → `CloseHandle` → `refresh()`。
  只有 `[W]` 窗口项可杀；`[A]` 应用项需先启动才有窗口。
- 与 `Minimize` 的区别：`Minimize` 只是 `ShowWindow(SW_MINIMIZE)`（还能还原），`Kill` 是**直接终止进程**（窗口和进程一起没）。
- 新增导入：`user32!GetWindowThreadProcessId`、`kernel32!OpenProcess`、`kernel32!TerminateProcess`（已写进 `u32b.def` / `k32c.def`）。
- 实测：选中 `[W] apps-Explorer++` → 点 `Kill` → `ps` 里 `wine C:\apps\explorerpp\Explorer++.exe` 立即消失，列表刷新后该项不再出现。
  截图 `shots/wintask-kill.png`。
- 按钮坐标（3200×2136 屏）：Kill 中心约 `(2540,1225)`；列表首行约 `y≈248`。

#### 应用列表删减 + 「面板里没有新应用」的真正原因（20:29）

- 删掉两条：`文件管理器 (winefile / MDI 双栏)`、`Minecraft 流畅版`（`apps.json` 16 → 14 条，`wf.bat` / `mc_fab.bat` 文件保留，随时能加回来）。
- **为什么之前 Explorer++ 没出现在面板里**：面板读的是 `C:\windows\wintask_apps.txt`，
  这个 TSV 由**启动器 App** 从 `apps.json` 生成；我用 adb 直接改 `apps.json` 时，TSV 还是旧的，
  而 `wp_launch.sh` 第 3.7 步只是把已有 TSV 复制进 prefix（不会自己解析 JSON）。
- 修法（已编进 APK）：`MainActivity.onResume()` 里补一次 `reloadApps()`，
  回到前台就重读 `apps.json` 并重写 `wintask_apps.txt`。
  验证：`su -c ': > /storage/emulated/0/WinPlay/wintask_apps.txt'`（清空）→ 打开启动器 → `wc -l` = **16**。
- 注意：`am start` 到已经在运行的实例**不会触发 onResume**（走 `onNewIntent`），
  需要 `am force-stop` 再 `am start`，或者手动把 App 切到后台再切回来。
- 当前面板 14 条：Explorer++ / 7-Zip / Notepad++ ARM64 / Notepad++ x64 / SumatraPDF / WinRAR 7.01 /
  VSCode 1.80 / VSCode 1.70 / putty-x86 / VSCode146 / obsidian / seven32 / testapp / Minecraft 1.20.1。

#### 面板改到左上角 + 折叠态去掉标题栏（20:50 用户要求）

- 展开默认位置：`(6, 60)`（左上角，y=60 避开 Android 状态栏）；折叠位置：`(6, 6)`（真角落）。
- `expand()`：`GetWindowRect` 取折叠后的 `r[0]`，`nx=r[0], ny=60`，**向右**长到 `400×580`（原来是从右边缘向左长）。
- 折叠态不再保留标题栏：`SetWindowLongW(h, GWL_STYLE, WS_POPUP|WS_VISIBLE)` + `SetWindowPos(..., SWP_FRAMECHANGED)`，
  窗口 `70×74`，里面只有一个 `WP` 方块 —— 之前标题被裁成「WinPl」的丑陋现象消失；展开时恢复 `WS_CAPTION`。
- 顺带修了个真 bug：`expand()` 里漏了 `MoveWindow(g_btnKill, 58,490,100,34)` 与标签的新位置，
  导致展开后 `Kill` 压住提示文字；现已与 `WM_CREATE` 的布局一致。
- 实测坐标（3200×2136 屏，wine 逻辑 1600×1068，2× 缩放）：
  - 折叠：窗口 `(12,12)-(152,160)`，`WP` 中心约 `(80,80)`
  - 展开：`<<` 中心约 `(80,1216)`，`Kill` 中心约 `(235,1216)`，`Run/Minimize/Refresh` 一行在 `y≈1173`
- 截图：`shots/wintask-left-expanded.png`、`shots/wintask-left-collapsed.png`。

#### 折叠态去掉外围白边（20:53 用户要求）

- 原因：折叠时窗口是 `70×74`，而 `WP` 按钮只有 `64×64`，右侧 6 px、下方 10 px 露出的是**窗口背景刷**
  （`wc.brush = COLOR_WINDOW`），看起来就是「灰框外的一圈白边」。
- 修法：折叠尺寸直接设成 **`64×64`**，与按钮完全重合：
  ```c
  SetWindowPos(g_self,0,6,6,64,64, SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED);
  ```
  `WS_POPUP` 本身不画边框，所以窗口边界与按钮外沿贴合，白色背景无处露出；
  剩下那圈灰线是**按钮自身的 3D 凸起边**（`BUTTON` 类的默认绘制），属于按钮外观的一部分。
- 实测：`shots/wintask-collapsed-noborder.png`（折叠，纯 WP 方块，无白边）；展开仍从 `(6,60)` 向右长到 400×580。

#### 折叠按钮 `WP` 真正居中（21:02 用户要求）

标准 `BUTTON` 的文字由系统按 `WM_SETFONT` 的字体重心画，wine 里这个 64×64 的方块会出现「靠上偏左」，改字号也治不好。
改成**自绘按钮**（`BS_OWNERDRAW`）后完全可控：

```c
g_mini = CreateWindowExW(0, L"BUTTON", L"WP",
          WS_CHILD | WS_VISIBLE | BS_OWNERDRAW(0x0B), 0,0,64,64, h, (void*)1005, g_inst, 0);

/* WM_DRAWITEM */
FillRect(hdc, rc, GetSysColorBrush(COLOR_BTNFACE=15));
DrawEdge(hdc, r2, EDGE_RAISED=5, BF_RECT=0xF);            // 保留按钮立体边
SelectObject(hdc, g_fontMini);                             // 19px 粗体 Noto Sans CJK
SetBkMode(hdc, 1 /*TRANSPARENT*/);
DrawTextW(hdc, L"WP", -1, rt, DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_NOPREFIX);
```

- `g_fontMini = CreateFontW(-19,0,0,0,700,...,L"Noto Sans CJK SC")`：粗体、比列表字体大一号。
- **wine 的 `DT_VCENTER` 有约 4 px 偏上**（按 `tmHeight` 而非实际字形居中），所以绘制前把 rect 下移 4 px：`rt={rc[0], rc[1]+4, rc[2], rc[3]+4}`。
- 新增导入：`user32!FillRect`、`user32!DrawEdge`、`user32!DrawTextW`、`user32!GetSysColorBrush`、`gdi32!SelectObject`、`gdi32!SetBkMode`。
- 截图：`shots/wintask-wp-centered.png`。

#### 回退：`WP` 恢复系统绘制（21:04 用户反馈）

- 用户反馈：早前一版（系统绘制）的字体位置才是对的，后一版（自绘 + 4px 下移）偏了 → 撤销下移，
  `g_mini` 回到普通 `BUTTON`（`WS_CHILD|WS_VISIBLE|WS_TABSTOP`），文字位置完全交回系统：
  ```c
  g_mini = CreateWindowExW(0,L"BUTTON",L"WP",WS_CHILD|WS_VISIBLE|0x00010000u,0,0,64,64,h,(void*)1005,g_inst,0);
  ```
- `WM_DRAWITEM` 分支保留但不再触发（普通按钮不发该消息，且 `<<` 虽同为 id 1005 也不自绘），删掉它也无副作用。
- 现在折叠态与 `t5c.png` 完全一致：`64×64` 白底按钮 + 系统居中文字，四周无白边。
- 截图：`shots/wintask-wp-t5c-style.png`。
- 结论记录：**wine 下自绘 `DrawTextW(DT_VCENTER)` 与系统按钮绘制在同一 64×64 方块里落点不同**，
  视觉上系统版本更讨喜；以后调这个按钮不要再动 `BS_OWNERDRAW`。

#### 把 Wine 桌面壁纸换成深灰（21:09 用户要求）

三个注册表值 + 一张图，改完**必须重启一次引擎会话**（`explorer.exe /desktop` 只在启动时读一次）：

```bat
reg add "HKCU\Control Panel\Desktop" /v Wallpaper      /t REG_SZ /d "C:\windows\wall_darkgray.bmp" /f
reg add "HKCU\Control Panel\Desktop" /v WallpaperStyle /t REG_SZ /d 2 /f   :: 2 = 拉伸
reg add "HKCU\Control Panel\Desktop" /v TileWallpaper  /t REG_SZ /d 0 /f
reg add "HKCU\Control Panel\Colors"  /v Background     /t REG_SZ /d "48 48 48" /f   :: #303030 兜底
```

- BMP 用 python 现生成：`1600×1068`、24 位、纯 `#303030`，文件 `C:\windows\wall_darkgray.bmp`（5126454 B）。
  （`WallpaperStyle=2` 会拉伸到屏幕，所以尺寸不必和设备一致。）
- 只改 `Colors\Background` 也能变灰，但**必须有 Wallpaper 或该颜色存在**，否则 wine 用内置默认蓝。
- **生效时机**：`am force-stop com.xiaomi.winplay` → 重新点开引擎（约 45–50 s）。
  只重启 `explorer.exe /desktop` 有时不刷新（旧桌面窗口仍持有旧背景）。
- 实测：`shots/wallpaper-darkgray.png`（整屏深灰，状态悬浮窗与 GPU 面板照常）。
- 想换回蓝色：把 `Background` 删掉（`reg delete "HKCU\Control Panel\Colors" /v Background /f`）并把 `Wallpaper` 指回空。

#### 为什么从零启动引擎会把 Steam 带起来（已修，21:13）

不是引擎的行为，是**你自己 prefix 里的注册表残留**——早期为了「让引擎在没有 Steam 的情况下也能有桌面」做实验时，往

```
HKCU\Software\Microsoft\Windows\CurrentVersion\Run
```

写了一条：
```
"Steam"="\"C:\\Program Files (x86)\\Steam\\steam.exe\" -silent"
```

wine 的 `explorer.exe /desktop` 启动时会执行 `Run` 键下的所有条目，于是每次开会话都把 Steam 静默拉起来（`-silent`，所以是后台悄悄起）。

**清除**（`$P = /data/user/0/com.xiaomi.winplay/files/prefix`）：

```bash
adb shell "su -c 'am force-stop com.xiaomi.winplay; killall -9 wineserver'"
adb exec-out su -c "cat $P/user.reg" > /tmp/user.reg     # 备份到 PC
# 删掉以 "Steam"=" 开头、含 steam.exe -silent 的那一行
adb push /tmp/user.reg /data/local/tmp/user.reg.new
adb shell "su -c 'cp -f /data/local/tmp/user.reg.new $P/user.reg; chown 7100:7100 $P/user.reg; chmod 644 $P/user.reg'"
```

> 注意：**设备上的 `sed` 删不掉这种行**（一行里十几层反斜杠，转义必错）→ 拉到 PC 用 python 改再 push 回去是最稳的。
> 改的时候 wine 必须停（`am force-stop` + `killall wineserver`），否则 wine 退出时会把内存里的注册表写回去覆盖你的修改。

验证：重启引擎后 `ps -A -o ARGS | grep -i steam` 无输出，桌面仍是深灰。

**如果你想要 Steam**：不要恢复这条 `Run` 键，用启动器的「启动时附带 Steam」勾选项（走 `winplay://opensteam?appid=0&devmode=1`），或者直接点 apps.json 里的 Steam 条目。

#### 「从零启动引擎还是会带起 Steam」——真因不是 Run 键，而是官方入口本身（21:22）

两个独立问题，先后踩到：

**① 残留的 `Run` 键**（上一节）→ 症状是 `steam.exe -silent`，已删。

**② 官方入口 `winplay://opensteam`**（本节）→ 症状是
```
wine C:\Program Files (x86)\Steam\steam.exe -gamepadui -noverifyfiles -nobootstrapupdate \
     -skipinitialbootstrap -norepairfiles -disable-winh264 -vrdisable -cef-force-gpu -cef-disable-breakpad
```
这串参数是**引擎自己拼的**，字符串在 dex 的 `com.xiaomi.winplay.utils.SteamUtil`（`d2.txt` 第 345208 行 `const-string " -gamepadui"`、345216 行 `" -noverifyfiles -nobootstrapupdate -skipinitialbootstrap -norepairfiles"`）。

对照实验（同一台设备，清场后各起一次）：

| 入口 | 结果 |
|---|---|
| `am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity`（无 extras） | 只有 `wineserver` + `explorer.exe /desktop`，**无 steam** |
| `am start -a android.intent.action.VIEW -d "winplay://opensteam?appid=0&devmode=1"` | 上面那串 `steam.exe -gamepadui ...` 立刻出现 |
| `cmd package resolve-activity -c android.intent.category.LAUNCHER com.xiaomi.winplay` | **No activity found**（引擎没有桌面图标，入口由游戏中心/快捷方式发 intent） |

→ 也就是说：**「Steam Big Picture 当桌面」是小米这个引擎的设计**，你点官方「PC 引擎 / 游戏中心」入口，它就是在开 Steam。想只要桌面，只有绕开那个入口。

**解法：`wp_nosteam.sh` 守卫 + `nosteam.flag`**

- 脚本 `/data/local/tmp/wp/wp_nosteam.sh`：每 3 s 检查 `/storage/emulated/0/WinPlay/nosteam.flag`，存在就 `ps | awk '/[s]team\.exe|[s]teamwebhelper|[s]teamerrorreporter/'` 拿 PID 全部 `kill -9` 并写 `/storage/emulated/0/wpz/nosteam.log`
- flag 由启动器的「启动引擎时附带 Steam」勾选框维护（`MainActivity.syncNoSteamFlag()`）：**不勾 = 写 flag（拦截）**，勾上 = 删 flag（放行）
- 桥 `wp_bridge.sh` 起来时会顺手拉起守卫；`install_bridge.sh` 会 push 这个脚本

实测（官方入口启动）：

```
[21:20:18] guard up pid=21338
[21:20:18] killed steam: 19933 19997 20075 20215 20225 20268 20469
ps -A -o ARGS | grep -iE "steam" → 只剩守卫自己
explorer.exe /desktop → 仍在
```

屏幕：`shots/wallpaper-darkgray.png` 那种深灰桌面，没有 Steam、没有 CEF 子进程。

**代价与取舍**：Steam 会先起来再被 3 s 内杀掉（会有一次 ~200 MB 的内存尖峰与一次 CEF 进程组创建/销毁）。要真正「一次都不起」，只能不用官方入口——用启动器（它走 `WineActivity`，本来就不带 Steam）。

## 为什么"用启动器打开"也会带起 Steam（已修）

现象：启动器里没勾「附带 Steam」，点任意应用后引擎仍走 `winplay://opensteam`，Steam Big Picture 占屏。

根因不是勾选框，是**参数位次左移**：

```
App 拼命令行 →  sh wp_launch.sh <b64exe>   0 0 1
                                          ↑ 空 args 占位被 shell 吃掉
脚本收到        $1=<b64exe> $2=0 $3=0 $4=1
脚本解读        EXE=b64exe  SCREENOFF=0（对）  STEAM=1（错！本该 0）
```

`STEAM=1` 时脚本走 `am start -a VIEW -d "winplay://opensteam?appid=0&devmode=1"`，Steam 就起来了。这也解释了日志里"args 非空的应用 steam=0、args 为空的应用 steam=1"的交替现象。

修法（两处，互为保险）：

1. App：空 args 传哨兵 `-`，`showPanel()` 同样
2. `wp_launch.sh`：识别 `-` 为"空参数"，并对 `$2 ∈ {0,1,2,""}` 的旧左移形态兜底（打印 `WARN 参数左移已兜住`）

```sh
_RAW2="${2-}"
case "$_RAW2" in
  -)            ARGS="" ;;
  0|1|2|"")     ARGS="" ;;   # 占位丢失
  *)            ARGS=$(b64d "$_RAW2") ;;
esac
if [ "$_RAW2" = "0" ] || [ "$_RAW2" = "1" ]; then
    SCREENOFF="$_RAW2"; STEAM=${3:-0}; PANEL=${4:-1}
else
    SCREENOFF=${3:-0}; STEAM=${4:-0}; PANEL=${5:-1}
fi
```

重装 APK 后必须清掉 App 缓存的脚本（`materializeAsset` 只在文件缺失时写入）：

```bash
rm -f /data/user/0/com.dsh.winplaylauncher/files/*.sh
```

验证：清场后 `winplaylauncher://app/7-Zip` → 日志 `steam=0`，88 秒内 `steam.exe` 计数恒 0，`7zFM.exe` 在 t=64 s 出现。

> 附：守卫脚本 `wp_nosteam.sh` 仍然保留。它挡的是**官方入口**（`winplay://opensteam`，引擎在 `SteamUtil` 里拼 `-gamepadui` 参数）——那条路是引擎设计，改不掉，只能"起来就杀"。

## 首启时 WP 小方块压在面板左上角（已修）

现象：引擎第一次启动、面板刚出现时，展开的面板左上角多出一个 `WP` 小方块，正好盖住列表第一行。

根因：`g_mini`（折叠态那个 64×64 的 WP 按钮）创建时带了 `WS_VISIBLE`：

```c
g_mini=CreateWindowExW(0,L"BUTTON",L"WP",
    WS_CHILD|WS_VISIBLE|0x00010000u|0x0000000Bu /*BS_OWNERDRAW*/,
    0,0,64,64,h,(void*)1005,g_inst,0);
```

它按 (0,0) 定位、64×64，且因为是**最后创建**的子窗口，z 序在最上层 → 展开态下就一直浮在列表左上角。`collapse()` 里 `ShowWindow(g_mini,1)`、`expand()` 里 `ShowWindow(g_mini,0)` 都是对的，唯独"创建即可见"这一步漏了。

修法：创建时不带 `WS_VISIBLE`，只在折叠时显式显示：

```c
g_mini=CreateWindowExW(0,L"BUTTON",L"WP",
    WS_CHILD|0x00010000u|0x0000000Bu /*无 WS_VISIBLE：只在折叠时显示*/,
    0,0,64,64,h,(void*)1005,g_inst,0);
```

验证（重编 9216 B → push 到 `C:\windows\wintask.exe` → kill 旧进程 → 重新拉起）：
- 展开态：列表从第一行 `[W] C:\users\xiaomi\` 开始，左上角无任何方块（`shots/wintask-first-start-fixed.png`）
- 折叠 → 只剩 WP 方块、无白边；再点 WP → 恢复完整面板，方块消失（`shots/wintask-collapsed-fixed.png`）

> 坐标备忘（3200×2136 设备 / wine 逻辑 1600×1068，2×）：面板窗口在逻辑 `(6,60)`，客户区顶边再加约 44 逻辑像素（标题栏），所以 `<<` 按钮点击点约为设备 **(60,1224)**，`Kill` 为 **(235,1224)**；折叠后 WP 点击点为设备 **(80,80)**。

## 启动耗时：为什么以前要一分钟，现在 9 秒（已优化）

旧脚本在拉起引擎后有一句固定等待：

```sh
WAIT=55                    # 带 Steam 时 70
log "cold start: waiting ${WAIT}s for wine desktop"
sleep $WAIT
```

这个 55 秒来自早期环境：那时 `WINEDEBUG=+file,+process,+seh` 还开着，`wine_logs/` 涨到 8.8 GB，引擎确实要 40–70 秒才出桌面。日志清掉、Steam 不再参与之后，**引擎本身 1–3 秒就绪**，但脚本还在傻等 55 秒 —— 这就是"点了以后很久才出窗口"的全部原因。

改成事件驱动（轮询桌面进程，不再固定 sleep）：

```sh
j=0
while [ $j -lt 90 ]; do
    j=$((j + 1)); sleep 1
    [ "$(ps -A -o ARGS 2>/dev/null | grep -c '[e]xplorer.exe /desktop')" -ge 1 ] && break
done
log "cold start: desktop ready after ${j}s"
: > /storage/emulated/0/wpz/res2.log 2>/dev/null   # 清一次，便于按 seq 判读
```

实测（启动器点 7-Zip，秒表从 `am start` 开始到 `7zFM.exe` 进程出现）：

| 场景 | 之前 | 现在 |
|---|---|---|
| 引擎未启动（冷启动） | ~64 s | **9 s** |
| 引擎已在跑（热启动） | ~7 s | **4 s** |

冷启动 9 秒的分解（`launcher.log`）：引擎 pid 1 s → 桌面就绪 1 s → 挂载 <1 s → 面板 3 s → 发命令到进程出现 4 s。

> 想再快只能让引擎常驻（启动器不退出、不 force-stop）。引擎跑着时点应用 = 4 秒，和普通 Android 应用冷启动一个量级。

## 启动器改版：MIUIX（HyperOS）风格

原界面是 `LinearLayout + ListView + 原生 CheckBox` 的调试面板长相（灰底、直角、系统按钮）。现在整层 UI 重写成 MIUIX 观感，功能逻辑一行没动。

### 结构

```
ScrollView(#F2F3F7)
└── LinearLayout(padding 14dp)
    ├── 大标题「PC 引擎」31sp bold + 副标题 12sp 灰
    ├── 主胶囊按钮「＋ 安装 / 运行（exe 或 zip）」#3482FF / 白字 / 圆角 14dp
    ├── 次级胶囊：▣ 面板（浅蓝底蓝字）、⟳ 重载、诊断（10% 黑底）
    ├── 状态卡（白卡 18dp 圆角，12sp 灰字）
    ├── 应用行 × N（白卡 18dp 圆角 + 6dp 间隙）
    │     40dp 圆角方块头像（首字符 + 名字散列固定色）
    │     名称 16sp / 路径 11sp 灰 / 右侧 › 
    ├── 选项卡（白卡）：附带 Steam、Wine 任务面板，右侧 MiuixSwitch
    └── 日志卡（#1B1B1D / 18dp 圆角 / 等宽 10sp 绿字 / 右上「清空」）
```

### 新增文件

`launcher/src/com/dsh/winplaylauncher/MiuixSwitch.java` —— 44×26dp 胶囊开关，自绘：

```java
ON = 0xFF3482FF; OFF = 0xFFE4E4E8; THUMB = 0xFFFFFFFF;
onDraw(): drawRoundRect(圆角=h/2) + drawCircle(白钮，随 checked 左右移动)
onTouchEvent(ACTION_UP): checked = !checked; invalidate(); listener.onCheckedChanged(...)
```

`MainActivity` 侧改动：

- 字段 `CheckBox steamBox/panelBox` → `MiuixSwitch`，监听从 `CompoundButton.OnCheckedChangedListener` 换成 `MiuixSwitch.OnCheckedChange`
- `buildUi()` 全量重写：`ListView` + `AppAdapter` 删除，改 `appsBox`（LinearLayout）里手工生成行，`reloadApps()` 调 `renderRows()`
- `card()` / `pill()` / `switchRow()` / `appRow()` 四个绘制辅助
- Manifest：`Theme.Material.Light.DarkActionBar` → **`Theme.Material.Light.NoActionBar`**（去掉系统标题栏）
- `onCreate` 里设 `statusBarColor=#F2F3F7`、`navigationBarColor=#FFFFFF`、`SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|LIGHT_NAVIGATION_BAR`（浅色栏配深色图标）

### 两个布局坑（都踩过）

1. **`ListView` 的 header 以 UNSPECIFIED 宽度测量** → header 内的 `match_parent` / `layout_weight` 全部失效，按钮被拉成几千像素、正文溢出屏幕。改用 `ScrollView + LinearLayout`，宽度由父容器精确给出，一次解决。
2. `InsetDrawable` 只能做"内缩背景"，做不出真正的卡片间隙 —— 现在用「行背景 = 白圆角卡 + `bottomMargin 6dp`」，间隙由 margin 提供，圆角才看得见。

### 验证

- 截图：`shots/launcher-miuix-top.png`、`shots/launcher-miuix-bottom.png`
- 点击 7-Zip 行 → `launcher.log`：`▶ 7-Zip  [C:\apps\7zip\7zFM.exe]` / `$ sh wp_launch.sh QzpcYXBwc1w3emlwXDd6Rk0uZXhl - 0 0 1`（哨兵 `-` 生效）→ 6 秒后 `7zFM.exe` 出现
- 开关状态持久化正常（Steam 关 / 面板开，重启 App 后保持）

## 应用图标：从 exe 的 .rsrc 里抠出来（**已停用**，见文末「列表形式」）

启动器不再一律显示首字母 —— 有自带图标就显示自带图标，没有才回落首字母。

### 怎么做的

新增 `launcher/src/com/dsh/winplaylauncher/PeIcon.java`，纯标准库，不依赖任何第三方：

1. `root("cp '<prefix>/drive_c/...' '<app files>/pe_<md5>.tmp' && chown <uid> ...")` 把目标 exe 拷一份到启动器私有目录（Root 才能读 `com.xiaomi.winplay` 的 prefix）
2. 解析 PE：`e_lfanew → COFF → OptionalHeader → 段表`，找到 `.rsrc` 段（`VirtualAddress / SizeOfRawData / PointerToRawData`）
3. 走三层资源目录：`RT_GROUP_ICON(14)` → 组 ID → `RT_ICON(3)` → 语言层 → `IMAGE_RESOURCE_DATA_ENTRY`，取出图标数据
4. 解 `GRPICONDIR` 选最大的一张（`width*1000 + bpp` 打分）
5. 解码：PNG 压缩的（Vista+ 常见）直接 `BitmapFactory`；老式 DIB 自己解（32/24/8/4/1 bpp，行按 4 字节对齐、下半行序、ICO 高度是双倍）
6. 出图：**白色圆角底板 + 图标等比内缩 12% 居中**（圆角只切底板，图标四角永不被裁），存 `files/icons/<md5>.png`

### 缓存与回落

- 目录：`/data/user/0/com.dsh.winplaylauncher/files/icons/`
- `md5.png` = 提取成功；`md5.none` = 确认没有图标（避免每次渲染重试导致死循环）
- 提取是**首次渲染时后台线程**做的，行先显示首字母占位，成功后 `renderRows()` 重绘
- 目标不是 PE（比如 `C:\windows\mc_fab_hi.bat`、`epp.bat` 这类包装脚本）→ 直接判定无图标

### 踩过的两个解析坑

1. **资源数据项的 RVA 是镜像绝对 RVA**，不是 `.rsrc` 段内偏移 —— 必须 `off = rva - rsrcVA`，否则永远越界
2. **语言层条目后面还有一跳**：`IMAGE_RESOURCE_DIRECTORY_ENTRY` 的 `OffsetToData` 指向 `IMAGE_RESOURCE_DATA_ENTRY`（里面才是 RVA/Size/CodePage），少跳这一步取到的是语言 ID

### 验证

截图 `shots/launcher-icons.png`：VSCode（蓝）、PuTTY（双显示器）、Obsidian（紫宝石）、7-Zip（7z）、testapp（PuTTY）、Minecraft（`mc_fab_hi.bat` → 首字母 M 占位）。

`launcher.log` 里留痕：

```
图标: 7-Zip 已提取 (256x256)
图标: Minecraft 1.20.1 失败(lfanew 775106862)，用首字母
```

## 启动器改为两栏布局（左 0.618 应用网格 / 右侧其余全部）

按用户要求：**应用放左边、横行排列，占左侧 0.618；标题 / 按钮 / 状态 / 开关 / 日志全部放右侧 0.382**。

### 结构

```
LinearLayout(HORIZONTAL, #F2F3F7)
├── ScrollView  weight 0.618   →  「应用」标题 + GridLayout(appsGrid)
└── ScrollView  weight 0.382   →  PC 引擎 / 副标题 / ＋安装运行 / ▣面板 ⟳重载 诊断
                                  / 状态卡 / 开关卡(Steam, 任务面板) / 运行日志
```

单个应用格子（`appCell`）：48dp 圆角图标（真图标，取不到则首字母） + 名称 12sp 两行省略，整格可点（启动）、长按（删除）。

### 关键坑：GridLayout 的权重不给 `width=0` 的孩子分配宽度

第一版照搬 LinearLayout 的写法：

```java
lp = new GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f),   // ← 权重
        GridLayout.spec(GridLayout.UNDEFINED));
lp.width = 0;
```

结果每个格子 `boundsInScreen` 是 `Rect(86,182 - 86,437)` —— **宽度 0**，屏幕左侧整片空白（`uiautomator dump` 里能看到 14 个 `Skipping invisible child`）。GridLayout 的 spec 权重只在“有剩余空间可分配”时起作用，不像 LinearLayout 那样用 `width=0` 撑开。

修法：**按列数自己算固定宽度**

```java
appsGrid.post(new Runnable() { public void run() {
    int w = appsGrid.getWidth();
    int c = Math.max(3, w / dp(110));      // 每格约 110dp，窄屏也不挤
    gridCellW = (w - dp(6) * c) / c;
    if (c != appsGrid.getColumnCount()) appsGrid.setColumnCount(c);
    renderRows();
}});
...
lp.width = gridCellW > 0 ? gridCellW : ViewGroup.LayoutParams.WRAP_CONTENT;
lp.setMargins(dp(3), dp(3), dp(3), dp(3));
```

### 验证

截图 `shots/launcher-two-column.png`：左侧 7 列 × 2 行共 14 个应用（Explorer++ / 7-Zip / Notepad++ ARM64 / Notepad++ x64 / SumatraPDF / WinRAR 7.01 / VSCode 1.80 / VSCode 1.70 / putty-x86 / VSCode146 / obsidian / seven32 / testapp / Minecraft 1.20.1），图标全部来自 exe 自带；右侧标题、蓝色主按钮、三枚胶囊、状态卡、两个 MiuixSwitch、深色日志卡齐全。

### 竖屏（最终）：同样左右分栏

两种方向都保持「左 0.618 应用网格 / 右 0.382 其余」，不再按方向换布局：

```java
final boolean portrait = getResources().getConfiguration().orientation
        == Configuration.ORIENTATION_PORTRAIT;
page.setOrientation(LinearLayout.HORIZONTAL);          // 永远左右

page.addView(leftScroll,  new LinearLayout.LayoutParams(0, MATCH_PARENT, 0.618f));
page.addView(rightScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 0.382f));
```

竖屏（2136 宽 ≈ 949dp）时：左栏 ≈586dp → 网格 5 列 × 3 行；右栏 ≈363dp，够放标题 + 主按钮 + 三枚胶囊 + 两张卡 + 日志。只做两处微调：

```java
right.setPadding(dp(portrait ? 4 : 8), 0, dp(2), 0);
title.setTextSize(portrait ? 22 : 26);
```

`AndroidManifest.xml` 的 `android:configChanges="orientation|screenSize|keyboardHidden|uiMode"` 会阻止 Activity 重建，所以必须有：

```java
@Override public void onConfigurationChanged(Configuration cfg) {
    super.onConfigurationChanged(cfg);
    setContentView(buildUi());
    reloadApps();
}
```

日志用 `StringBuilder logBuf`（上限 512 KB）兜住，`buildUi()` 末尾 `logView.setText(logBuf)`，旋转不丢。

网格列数由 `w / dp(110)` 在 `post()` 里按实际宽度算（见上方「GridLayout 的权重不给 width=0 的孩子分配宽度」），方向变化会自动重算。

### 两个方向的验证

- 横屏 `shots/launcher-landscape.png`：左 7 列 × 2 行、右标题/按钮/状态/开关/日志
- 竖屏 `shots/launcher-portrait.png`：左 5 列 × 3 行、右完整面板（标题 22sp、状态说明换 3 行）

复现命令（设备侧强制方向）：

```bash
adb shell "wm user-rotation lock 0"   # 竖屏
adb shell "wm user-rotation lock 1"   # 横屏
adb shell "wm user-rotation free"     # 恢复自动
```

### 图标不被圆角裁切

第一版直接用 `BitmapShader + drawRoundRect` 把图标**裁**成圆角 —— 圆角处会啃掉图标本身（7-Zip 的黑色方块、Notepad++ 的绿色方块四角都被切掉一块）。

改成「底板 + 内缩」两层：

```java
// 1) 白色圆角底板（圆角只作用于它）
Paint bg = new Paint(ANTI_ALIAS_FLAG);
bg.setColor(0xFFFFFFFF);
c.drawRoundRect(new RectF(0, 0, sizePx, sizePx), radiusPx, radiusPx, bg);

// 2) 图标等比缩进 88% 的居中区域
float inset = sizePx * 0.12f;
float box = sizePx - inset * 2f;
float scale = Math.min(box / src.getWidth(), box / src.getHeight());
float w = src.getWidth() * scale, h = src.getHeight() * scale;
c.drawBitmap(src, null, new RectF((sizePx-w)/2f, (sizePx-h)/2f, (sizePx+w)/2f, (sizePx+h)/2f), fg);
```

效果见 `shots/launcher-icons-plate.png`：满幅图标（7-Zip / Notepad++）现在四周留白、四角完整；本来就带透明边距的图标（VSCode / Obsidian）同样居中不贴边。

**注意**：提取结果带渲染，改渲染逻辑后必须清缓存重来：

```bash
adb shell "su -c 'rm -f /data/user/0/com.dsh.winplaylauncher/files/icons/*'"
```

## 列表形式：去掉图标与首字母（最终版）

中途试过纯文字列表版：去掉图标、去掉首字母方块，只留名称与路径；后来因为辨识度差又改回图标网格。

左栏从「网格 + 图标」改回**纯文字列表**：

```
LinearLayout(vertical, appsList)
└── 每行：白底圆角卡（12dp 圆角），左侧 16dp / 上下 12dp 内边距
        ├── 名称     16sp  #0D0D0D  单行省略
        └── exe 路径  11sp  #8A8A8E  单行、从中间省略
        行间距 6dp（bottomMargin）
```

- 点击整行 = 启动（`launchApp`），长按 = 删除确认
- `PeIcon` / `appIcon` / `letterAvatar` / `ensureIcon` 仍留在源码里但**不再被调用**，`files/icons/` 缓存也不再生成；想看回图标版把那几行 `appCell` 改回去即可
- 左右分栏比例（0.618 / 0.382）与方向处理保持不变

截图：

- 横屏 `shots/launcher-list-landscape.png`（左 14 行文字列表，右标题/按钮/开关/日志）
- 竖屏 `shots/launcher-list-portrait.png`（左列宽度收窄后路径仍单行省略，右栏文字自动换行）

## 图标黑边/黑底的真因：AND 掩码

用户反馈：图标外面/背后有一圈黑，Windows 里看不到。原因不在渲染，而在**解码**。

### Win32 图标的透明是两份数据

ICO 里一张图 = **XOR 位图**（真正的颜色，32bpp 时带 alpha）+ **AND 掩码**（1bpp，位=1 表示透明）。老图标（24bpp/8bpp/4bpp/1bpp）根本没有 alpha 通道，透明完全靠 AND 掩码。

我第一版只读 alpha：

```java
if (bpp == 32 && !anyAlpha) {
    for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;   // ← 把「透明」也涂成不透明
}
```

对于 alpha 全 0 的 32bpp DIB（PuTTY、7-Zip 的 48×48 都是这种），这一步把整张图变成**不透明**，而背景像素的 RGB 是 0,0,0 —— 于是屏幕上是「黑方块 + 图标」。Windows 之所以正常，是因为它同时应用了 AND 掩码。

### 修法：解完 XOR 位图后再套 AND 掩码

```java
int maskOff = pixOff + stride * hh;          // 掩码紧跟 XOR 位图
int maskStride = ((w + 31) / 32) * 4;
if ((long) maskOff + (long) maskStride * hh <= d.length) {
    for (int y = 0; y < hh; y++) {
        int mrow = maskOff + y * maskStride;   // 掩码同样自下而上
        int drow = (hh - 1 - y) * w;
        for (int x = 0; x < w; x++) {
            int bit = (d[mrow + (x >> 3)] >> (7 - (x & 7))) & 1;
            if (bit == 1) px[drow + x] &= 0x00FFFFFF;   // alpha → 0
        }
    }
}
```

修完 PuTTY、7-Zip、testapp 这些 48×48 DIB 图标立刻正常：两个显示器、黑色 7z 方块、深色终端，四周都是白底板而不是黑方块。

### 另一个并发 bug（顺带修掉）

`PeIcon.load()` 里的 `.rsrc` 基址是 **static 字段** `sRsrcVA`，而图标是多个线程并行提取的 → 互相覆盖，出现「同一个 exe 这次成功、下次 `empty group`」的随机失败（Notepad++ x64、SumatraPDF 都中过招）。

修法一行：

```java
static synchronized Bitmap load(File pe) { ... }
```

### 验证

- 14 条里 11 条取到真图标（Notepad++ 两个版本、SumatraPDF、WinRAR、VSCode ×3、Obsidian、7-Zip、PuTTY、seven32、testapp）
- 3 条是 `.bat` 包装（Explorer++ / Minecraft / …），无 PE → 首字母占位
- 截图 `shots/launcher-icons-final.png`、放大版 `shots/launcher-icons-final-zoom.png`

清缓存重算（改解码逻辑后必做）：

```bash
adb shell "su -c 'rm -f /data/user/0/com.dsh.winplaylauncher/files/icons/*'"
```

## 图标右下角嵌入架构角标（ARM64 / x64 / x86）

需求：每个图标右下角标出应用架构（ARM64 / x64 / x86），并且**没有自带图标的条目也要标**。

### 怎么判定架构

只读 PE 头 4 字节，代价极小：

```java
static String archOf(File pe) {
    int lfanew = i32(h, 0x3C);
    int machine = u16(h, lfanew + 4);
    if (machine == 0xAA64) return "ARM64";
    if (machine == 0x8664) return "x64";
    if (machine == 0x014C) return "x86";
    return null;
}
```

`.bat` 包装没有 PE 头 —— 从脚本正文里用正则挑第一个 `C:\...\*.exe`，再对那个 exe 读 PE 头（Explorer++ 的 `epp.bat` 因此能显示 `x64`，Minecraft 的 bat 指向 `java.exe` → `ARM64`）。解析结果进 `archCache`，`archPending` 防重复。

### 有图标：画进位图

`PeIcon.round(src, size, radius, badge)` 在透明底板上画完图标后，右下角画一个黑底白字小胶囊：

```java
float ts = sizePx * 0.145f;                      // 字号 ≈ 14.5% 边长
float maxW = sizePx * 0.40f;
while (tp.measureText(badge) > maxW && ts > sizePx * 0.085f) { ts *= 0.92f; tp.setTextSize(ts); }
float m = sizePx * 0.085f;                       // 离边距，避开圆角弧线
c.drawRoundRect(new RectF(sizePx - bw - m, sizePx - bh - m, sizePx - m, sizePx - m), rr, rr, bp);
c.drawText(badge, br.left + padX, br.top + padY - fm.ascent, tp);
```

顺带把出图尺寸提到 **2 倍（96dp 图，屏上 48dp）**，否则 48px 位图上的 7px 文字糊成一团。

### 无图标：叠一个 TextView

首字母方块是 View 不是位图，所以用 `FrameLayout` 套一层：

```java
FrameLayout box = new FrameLayout(this);
box.addView(letterAvatar(e.name), new FrameLayout.LayoutParams(sizePx, sizePx));
TextView b = new TextView(this);            // 8sp 粗体、白字、#CC000000 圆角底
FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
        Gravity.END | Gravity.BOTTOM);
blp.rightMargin = dp(2); blp.bottomMargin = dp(2);
box.addView(b, blp);
```

架构值来自 `archOfApp(e)`（后台线程解析，拿到后 `renderRows()` 重绘；没解析出来就先不画角标）。

### 另一个必须修的 bug：缓存命中时被重复套底板

```java
// 旧：读到的缓存 PNG 已经是「底板 + 图标 + 角标」成品，又 round 了一次
bm = PeIcon.round(b, sizePx, dp(14));
// 新：直接用
bm = b;
```

否则会被二次内缩 —— 图标显得又小一圈、四角多一层白边，而且角标会被再缩一次。

### 验证

`shots/launcher-icons-badge.png`：14 个格子全部带角标 —— 7-Zip `x64`、Notepad++ ARM64 `ARM64`、Notepad++ x64 `x64`、SumatraPDF `x64`、WinRAR `x64`、VSCode ×3 `ARM64`、Obsidian `ARM64`、PuTTY `x86`、seven32 `x86`、testapp `x86`，以及**无图标的「文件管理器 (Explorer++)」也显示 `x64`**（从 `epp.bat` 里解析出 `Explorer++.exe`）。

### 角标大小必须统一

第一版对长标签（`ARM64`）自动缩字号来塞进 40% 宽度，结果「有图标的 ARM64」比「无图标的 x64」明显小一号。改成**字号固定、胶囊按文字变宽**：

```java
float ts = sizePx * 0.145f;        // 字号恒定，不再 shrink
float tw = tp.measureText(badge);  // 宽度自适应，最长 ARM64 ≈ 58% 边长
float bw = tw + padX * 2f;
```

无图标的 `TextView` 分支用同一套数值（`COMPLEX_UNIT_PX` 设 px 而不是 sp，`setIncludeFontPadding(false)`，padding `0.26*ts / 0.12*ts`，圆角 `(ts+2*padY)*0.34`，边距 `0.085*sizePx`），两边渲染出来才一致。

## 排查记：SumatraPDF 报「Looks like corrupted installation」

用户报告「SumatraPDF 打不开」。现象是一个标题为 **SumatraPDF installer** 的弹窗：

> Looks like corrupted installation of SumatraPDF.
> Learn more at www.sumatrapdfreader.org/docs/Corrupted-installation

### 它不是本体的错误对话框，而是**安装器**的

`$P/drive_c/apps/sumatra/SumatraPDF.exe` 当时是 **8246744 B**。而官方 3.5.2 的 64 位便携版本体是 **16065496 B**，8.2 MB 那个是 `SumatraPDF-3.5.2-64-install.exe`（安装器）。我用 `wapp.sh` 安装时按「去掉 unins/setup 等关键字后取体积最大的 exe」挑主程序 —— 那个包里只有一个 exe，于是把**安装器**改名成了 `SumatraPDF.exe`。安装器发现自己被改名、找不到内嵌载荷，就吐这句「corrupted installation」。

判据（不用跑就知道）：
- 便携版本体 ≥ 16 MB、安装器 ≈ 8 MB
- PE 里能 `grep -a Nullsoft` / `grep -a "Inno Setup"` 的一律是安装器
- 真正的 `SumatraPDF.exe` 启动后标题是 `SumatraPDF` 或文档名，不会出现 `SumatraPDF installer`

### 修法

```bash
curl -sL -o /tmp/sm2/s.zip https://www.sumatrapdfreader.org/dl/rel/3.5.2/SumatraPDF-3.5.2-64.zip
unzip -q s.zip   # → SumatraPDF-3.5.2-64.exe 16065496 B, machine 0x8664
adb push SumatraPDF-3.5.2-64.exe /data/local/tmp/sm.exe
su -c 'cd $P/drive_c/apps/sumatra && mv -f SumatraPDF.exe SumatraPDF.exe.installer.bak \
      && cp -f /data/local/tmp/sm.exe SumatraPDF.exe && rm -f SumatraPDF.exe.realigned \
      && chown 7100:7100 SumatraPDF.exe'
```

重启该进程后：启动页正常（`SumatraPDF v3.5.2 64-bit` + 「经常打开的文件」+「打开文档…」），截图 `shots/sumatra-portable.png`；打开 `C:\apps\sumatra\wptest.pdf`（401 B 手写 PDF）后正确渲染出 `WinPlay on Xiaomi Pad 7 Ultra`，页码 `1/1`，标签页 `Home` / `wptest.pdf`，截图 `shots/sumatra-open-pdf.png`。

### 顺手全量体检

对 `$P/drive_c/apps/*/*.exe` 扫 `Nullsoft` / `Inno Setup` 特征，命中四个，只有第一个是历史误装：

```
INSTALLER   57320  apps/faststone/uninst.exe          ← 卸载器，正常
INSTALLER 256875728 apps/obs/Obsidian.exe             ← 安装器残留（未注册，可删）
INSTALLER 160836704 apps/wps19/setup.exe              ← WPS 2019 在线安装器
INSTALLER 327444024 apps/wpsarm/wps_arm64.exe         ← WPS ARM64 内测包（就是安装器）
```

`apps.json` 里 14 条注册项的 exe 已全部复核，除 SumatraPDF 外都是本体。

### 教训写进 wapp.sh 的判定里

主程序候选若 `grep -a` 命中 `Nullsoft` / `Inno Setup`，应当直接判为安装器并改走「7z 解包取载荷」分支，而不是改名当本体 —— 下一步给 `wapp.sh` 加这条判断。

## 排查记：SumatraPDF 窗口没有关闭按钮

用户报告：PDF 窗口的关闭键消失，无法正常关闭。

### 现象与判据

SumatraPDF 3.5.2 的顶层窗口在 wine 里只画出一条**黑的标题带、右侧没有任何按钮**（最小化/最大化/关闭都没有），所以只能靠任务管理器杀。取证：

- `winfix.exe` 打出窗口矩形与样式：`st=382664704 (0x16CE0000) 40,40,760,900 SumatraPDF` —— `0x16CE0000` 里 **`WS_SYSMENU(0x00080000)` 明明在**，但 wine 没有画关闭框
- 试过 `SetWindowLongW(GWL_STYLE, st | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX)` + `SWP_FRAMECHANGED` 强制重绘 → 依然不画
- 在「关闭框本该在」的位置点屏幕（窗口右上角 wine 770,50）→ 无反应，说明按钮**根本不存在**，不是黑底黑字看不见
- 同一台机上 NSIS 安装器的对话框（`SumatraPDF installer`）标题栏是有 `×` 的 → wine 的标题栏绘制本身没问题，是 SumatraPDF 自己的框架走了非标准路径

结论：这在 wine 下改不动（要改得动得 hook 它的 `WM_NCPAINT`/`WM_NCCALCSIZE`）。改成**在启动器侧给一个可靠的关闭入口**。

### 方案一：任务面板加 `Close` 按钮（已实现）

`pcpanel/wintask.c` 新增按钮 id 1007，给选中的窗口发 `WM_CLOSE`（比原来的 `Kill`=TerminateProcess 温和，程序能正常存盘退出）：

```c
if (id == 1007) {
    int i = (int)SendMessageW(g_list, LB_GETCURSEL, 0, 0);
    if (i >= 0 && i < g_hwn) PostMessageW(g_hw[i], WM_CLOSE, 0, 0);
    refresh();
}
```

布局：第二行变成 `<< | Kill | Close`（`Kill` 58,490,100,34；`Close` 166,490,100,34），提示文字挪到 `(58,532)`。

实测：选中 `[W] SumatraPDF` → 点 `Close` → `ps` 里 `26111 wine C:\apps\sumatra\SumatraPDF.exe` 立刻消失（进程正常退出，不是被 kill）。截图 `shots/wintask-close-button.png`。

### 方案二：顺带修掉「窗口跑到屏幕外」

SumatraPDF 还带一个毛病：它记住的窗口尺寸是 `825×1068`，而 wine 桌面只有 `1600×1068`，再加上 y=60 的偏移，底部 60 px 永远在屏幕外（也就是标题栏贴在最上沿、下面被切）。

新增 `pcpanel/winfix.c` → `C:\windows\winfix.exe`（ARM64，4–6 KB）：

- `EnumWindows` 遍历可见顶层窗口，打印 `st=<style> x,y,w,h title` 到 `C:\winfix.log`
- 窗口矩形超出桌面（`x<0 || y<0 || x+w>dw || y+h>dh`）就 `SetWindowPos` 夹回桌面内
- 对没有 `WS_SYSMENU` 的窗口补上 `SYSMENU|MINBOX|MAXBOX` 并 `SWP_FRAMECHANGED`
- `MV_WATCH=1` 时进入 3 秒轮询的守护模式；`MV_TITLE`/`MV_X`/`MV_Y`/`MV_W`/`MV_H` 可定点摆某个窗口

`wp_launch.sh` 新增第 **3.8 步**：若 `winfix.exe` 不在跑，就用 `_wpfix.bat`（`set MV_WATCH=1` + `start "" winfix.exe`）把它拉起来 —— 之后任何程序开窗过大都会被自动夹回屏幕。

### 顺带修的假 [min]

面板原来用 `GetWindowPlacement().showCmd ∈ {2,6,7}` 判最小化，wine 里这个值经常说谎（SumatraPDF 明明是可见的普通窗口也报 2，列表里显示 `[W] [min] SumatraPDF`）。加了矩形兜底：

```c
int r[4]; if (GetWindowRect(h, r)) {
    if (r[0] > -10000 && r[1] > -10000 && r[2] > r[0] && r[3] > r[1]) return 0;  // 有正常矩形就不算最小化
}
```

### 两个小回归（`winfix` 与任务面板的相互影响）

1. **任务面板右上角叉号又冒出来**：`winfix` 给「缺 `WS_SYSMENU` 的窗口」补样式时，把**任务面板自己也补上了** —— 而面板是故意去掉 `WS_SYSMENU` 才没有叉号的。修法：`winfix` 里按类名跳过，`RegisterClassW` 用的类名就是 `WTaskPanel`：

```c
const u16* cn=L"WTaskPanel";
int same=1; for(int k=0;k<10;k++){ if(c[k]!=cn[k]){ same=0; break; } if(!cn[k]) break; }
if(same) return 1;   /* 自己的窗口一律不碰 */
```

2. **桌面窗口被标成 `[min]`**：wine 里 `GetWindowPlacement` 对桌面窗口（标题 `C:\users\xiaomi\`）也返回 `showCmd=2`。原来的兜底是「矩形正常就不算最小化」，但桌面窗口的矩形确实异常。改成按标题判：标题以反斜杠结尾的视为桌面，不打 `[min]`。注意要在把标题拼进 `g_tmp` **之前**判断——第一版写成检查 `g_tmp` 的末字符，而那时 `g_tmp` 还只有 `"[W] "`，所以没生效。

---

## KernelSU 模块：刷进去就有一套能用的 PC 引擎

`ksu/winplay-pcengine/` 是一个可直接刷入的 KernelSU 模块。目标只有一个：**同款机型 + 已 root，刷模块 + 给应用授权后，点图标就能跑 Windows 程序**，不需要 adb、不需要手动敲命令。

### 前置条件

| 项 | 要求 |
|---|---|
| 设备 | 同款机型（`25032RP42C` / XRING O1）——引擎的 native 库与 fastipc 通道跟机型绑定 |
| root | KernelSU（`ksud` 可用） |
| Zygisk | **Zygisk Next（`zygisksu`）已启用**——模块要靠它把 `zygisk/arm64-v8a.so` 注入引擎进程 |
| 引擎 | `com.xiaomi.winplay` 已安装（同款机自带的「PC 引擎」；没有就用完整包，见下） |

### 安装

```bash
./make-bundle.sh                     # 生成 dist/winplay-pcengine-v1.0.0.zip（84 KB）
```

把 zip 传到平板，打开 **KernelSU → 模块 → 从本地安装**，选它，重启。

### 刷入过程中它自己做的事

`customize.sh`（安装时，root）：

1. 校验 `com.xiaomi.winplay` 与 `com.xiaomi.gamecenter` 在不在；缺引擎时若模块里有 `apks/winplay.apk` 就装上，否则直接中止并说明原因
2. `pm install` 本仓库的启动器 `common/WinPlayLauncher.apk`（装不上就留个标记，开机后再补）
3. 授权：`MANAGE_EXTERNAL_STORAGE`、`REQUEST_INSTALL_PACKAGES`、`POST_NOTIFICATIONS`、读写外部存储
4. **停用旧模块** `winplay_run` / `winplay_fix`（否则同一个 exe 会被注入两遍）

`service.sh`（每次开机，late_start）：

1. 等 `sys.boot_completed`
2. `chmod o+x /data/media`（让 uid 7100 能穿越到 raw 目录，FUSE 视图不受影响）
3. 把 `wintask.exe` / `winfix.exe` 下发到 `prefix/drive_c/windows/`（`chown 7100`）
4. 首次运行把 `apps.json` 播种到 `/storage/emulated/0/WinPlay/`
5. 以 root 拉起 **命令网桥** `wp_bridge.sh` 与 **no-Steam 守卫**，并每 30 s 看护一次（死了就重拉）

网桥是这套方案的关键：**App 自己不需要 root 权限**。App 只往 `/storage/emulated/0/wpz/launch_req.txt` 写一行 `seq|screenoff|b64exe|b64args`，root 网桥读到后执行 `wp_launch.sh`（亮屏 → 确保引擎在前台 → 冷启动等待 → 写 `cmd2.txt` → 等 Zygisk 桥回执），结果写回 `launch_res.txt`。

### 为什么 zip 只有 84 KB，不带小米的 APK

`com.xiaomi.winplay` 的 `base.apk` 是 **483 MB**、游戏中心 **56 MB**：

- GitHub 单文件硬上限 100 MB，放不进仓库
- 这两个是小米的闭源分发包，不适合由本仓库再分发

同款机型刷机时它们本来就在系统里（或用户自己装过一次），所以轻量包只做「校验 + 自动化」。

要在**另一台同款设备离线安装**时，用完整包——它会把 APK 从当前设备 pull 出来打进 zip：

```bash
./make-bundle.sh --full              # dist/winplay-pcengine-v1.0.0-full.zip（约 560 MB）
```

模块里的 `apks/` 目录就是给完整包用的：`customize.sh` 发现引擎缺失时优先装它。

### 实测记录（本机）

```
$ ksud module install /data/local/tmp/wpmod.zip
- 已检测到 PC 引擎 (com.xiaomi.winplay)
- 已检测到游戏中心 (com.xiaomi.gamecenter)
- 启动器已安装
- 已停用旧模块 winplay_run（重启后生效，可手动删除）
- Module installed successfully!

$ sh /data/adb/modules_update/winplay_pcengine/service.sh
[11-19 00:46:06] service start (boot_completed after 0s)
[11-19 00:46:06] permissions ensured for com.dsh.winplaylauncher
[11-19 00:46:06] bridge started pid=3252

# 从启动器点 7-Zip（引擎是停的，冷启动）
t=8s   steam=0  7zip=1  bridge=1     ← 8 秒出窗口，且没有把 Steam 带起来
```

### 卸载

KernelSU 里删模块即可。`uninstall.sh` 只停网桥与守卫，**不删 Wine prefix、不删你的应用数据**；想连启动器一起删，刷入前在模块目录放一个 `remove_app` 文件。

### 许可

本仓库（含模块脚本、启动器源码、面板工具）以 **GPL-3.0** 发布，见 `LICENSE`。模块本身不含小米的任何二进制；`--full` 打出来的完整包仅供你在自己的设备上自用，不要再分发。
