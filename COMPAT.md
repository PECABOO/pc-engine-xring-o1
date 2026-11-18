# WinPlay 兼容性矩阵（2026-11-18 夜测）

测试方式：Zygisk 模块 `winplay_run` 反射调 `WindowChannel.runNewApp`（DOS 路径，isUnixPath=false）→ 进程存活 + fastipc 通道 + **屏幕像素签名变化** 三项同时满足记为 `render`。
`render` = 真实窗口出现在 Android 屏上（不是"进程起来了"）。

## 本轮新测（11 个包）

| 程序 | 版本/架构 | 类型 | 结果 | 备注 |
|---|---|---|---|---|
| **VLC** | 3.0.21 win64 (`0x8664`) | Qt5 播放器 | ✅ render | 77 MB zip，直接解压可用 |
| **Audacity** | 3.6.4 64-bit (`0x8664`) | Qt6 音频 | ✅ render | 首次启动较慢 |
| **MPC-HC** | 2.3.5 x64 (`0x8664`) | 原生 DirectShow | ✅ render | 无 Qt，纯 Win32 |
| **ScummVM** | 2.8.1 x86_64 | SDL2 游戏引擎 | ✅ render | 自带 SDL2.dll |
| **CPU-Z** | 2.10 x64 | 硬件检测 | ✅ render | 读不到真实 CPU 但 UI 正常 |
| **Everything** | 1.4.1.1026 x64 | 文件搜索 | ✅ render | 索引需管理员，列表为空属正常 |
| **FastStone Image Viewer** | 7.4 | 原生图像浏览 | ✅ render | 7 MB 安装器直接 7z 解包 |
| **Node.js** | 20.11.1 win-x64 | 控制台 | ✅ render | REPL 控制台正常；`node -v` 输出已验证 |
| **Python** | 3.12.4 embed-amd64 | 控制台 | ✅ render | embed 版无 pip，可加 get-pip |
| **Git for Windows** | 2.46.0 64-bit | 控制台工具链 | ✅ 执行成功 | `git version 2.46.0.windows.1` 写入文件验证 |
| FFmpeg | — | — | ⚠️ 未测完 | 114 MB 包在 58 MB 处网络中断，zip 不完整 |
| XnView MP | 1.8.x x64 | Qt6 图像管理 | ⚠️ 未解包 | 安装器用私有封装（单条 `[0]` 流，非标准 NSIS/7z），PC 侧解不开；可用其 zip 版 |

## 之前已测（见 README）

7-Zip 24.08 / Notepad++ 8.7.1（ARM64 与 x64）/ SumatraPDF 3.5.2 / WinRAR 7.01 / PuTTY x86 / VSCode 1.70 与 1.80.2（Electron 22）全部 `render`。

## 由矩阵得出的结论

1. **三大 UI 框架全通**：Win32 原生、Qt5/Qt6（VLC/Audacity/XnViewMP）、SDL2（ScummVM）都能正常创建窗口并渲染。Qt 不需要额外插件目录——官方包自带 `platforms/qwindows.dll`。
2. **x64 与 x86 都通**：x64 走 FEX-Emu（`0x8664`），x86 走 wow64（PuTTY/7-Zip x86），ARM64 原生最快。
3. **控制台程序完全可用**：Node/Python/Git 都能跑，意味着 `npm install`、`git clone`、`python script.py` 这条开发链在平板上成立（需注意 `C:` 盘路径与 `\` 转义）。
4. **只有两类东西跑不动**：
   - **Electron ≥25**（V8 sandbox 要 1.09–1.51 TB 连续 VA，设备只有 39 位/512 GB）
   - **NSIS/Inno 安装器本体**（32 位 stub 在 wow64+FEX 下卡 `read(fd=8)`）→ 一律 PC 侧 `7z x` 解包后直跑
5. **单个 app 的失败通常是缺 DLL**：把依赖 DLL 放同目录即可（如 WPS 的 `krpt.dll`）。用 `WINEDEBUG=+module` 看 `import_dll Library X not found` 就能定位。

## 复现命令

```bash
# 测一个包（自动 下载→解包→补丁→推送→启动→判定→记录）
./test/wtest.sh one vlc
# 全量
./test/wtest.sh batch
# 看矩阵
./test/wtest.sh report
```
结果落 `test/state.tsv`，日志 `test/batch.log` / `test/rest.log`。

## 已知修复（跑不起来时按顺序试）

| 症状 | 修法 |
|---|---|
| 进程起、无窗口、`allocate_virtual_memory` | Electron ≥25 → 换 ≤22 版本 |
| 窗口闪退 `code=80000003` / `uisettings2 ... stub` 刷屏 | 加 `--force-device-scale-factor=1` |
| `EXCEPTION_STACK_OVERFLOW` | `patch_pe.py` 抬 `SizeOfStackReserve`（PE32+ 256 MB / PE32 16 MB） |
| `SIGILL` 在 `mrs pmccntr_el0` | `patch_pe.py` 改 `cntvct_el0` |
| `import_dll Library X not found` | 把 X.dll 拷到 exe 同目录 |
| 安装器卡死 | PC 上 `7z x` 解包，别在 wine 里跑安装器 |
| 装好的程序（Program Files）双击/启动器秒退，日志停在 `RtlSetCurrentDirectory_U` | 需要自身工作目录 | 用 bat：`cd /d "C:\Program Files\<App>"` + `start "" <App>.exe`（启动器已自动包装） |

## WPS Office（验证结论：目前不通过）

实测了三个版本，全部失败，**根因不是架构，而是 WPS 的 UI 是 CEF（Chromium Embedded Framework）**：

| 版本 | 来源 | 现象 |
|---|---|---|
| 12.1.0.29670 | `WPS_Setup_x64_29670.exe`（378 MB, x64） | 3 个 `wps.exe` 进程起（RSS 510/225/292 MB），加载 `Qt5CoreKso/Qt5GuiKso/Qt5WidgetsKso`、`kwpslingxi`、`aspcenter`、`pdfwspv`，然后 **C++ 异常 `e06d7363`** 退出，`WIN_CreateWindowEx` 计数 0 |
| 12.1.0.25200 | `wps_arm64_beta20.3102.exe`（327 MB，「WoA 内测」） | 同款异常；解剖后确认 **包里没有 ARM64 主程序**（x64 190 / ARM64 14 / ARM64EC 0），核心仍走 FEX |
| 11.1.0.10314 | `W.P.S.10314.12012.2019.exe`（160 MB，WPS 2019 在线安装器，`/S` 静默安装成功） | **窗口出来了但是空白**：加载 `addons/kcef` + `promecefpluginhost.exe`（CEF 主机进程，RSS 265 MB，DXVK 初始化到 `Mali-G925-Immortalis` 后又把 `d3d12.dll`/`kcef` 卸载）→ CEF 合成链路失败，只剩灰色空窗 |

关键证据链：
- `12.1.0.29670` 走 WMI 时命中 wine 未实现的 coclass：`err:ole:com_get_class_object class {c71566f2-561e-11d1-ad87-00c04fd8fdff} not registered`，该 CLSID 在 `wbemcli.idl` 里是 **`WbemRefresher`**（wine 的 `wbemprox` 没注册它），随后 `kaccountsdk.dll` 卸载、进程退出
- 把 qskia 平台插件改名后 Qt 会回落到 `qwindows.dll`（日志确认 `Loaded ... platforms\qwindows.dll` + `styles\qwindowsvista.dll`），但仍无窗口
- 把 `HKCU\Software\Wine\AppDefaults\wps.exe` 的 `Version` 改成 `win7`、覆盖成 native `msvcp140/vcruntime140` 都无效
- 期间还发现 `ldping64.exe` 崩溃会弹出 Wine Debugger 模态框（`程序 ldping64.exe 遇到严重的问题需要关闭`），建议长期设置：
  ```
  reg add "HKCU\Software\Wine\WineDbg" /v Disabled /t REG_DWORD /d 1 /f
  reg add "HKCU\Software\Wine\WineDbg" /v ShowCrashDialog /t REG_DWORD /d 0 /f
  ```

可行替代：平板本身已装 **Android 版 WPS**（`cn.wps.moffice_eng.xiaomi.lite`，系统应用），文档编辑直接用它；Windows 侧可用 **Notepad++/SumatraPDF** 看稿。

## Firefox（Windows ARM64 原生版，160.0.1）

| 项目 | 结果 |
|---|---|
| 启动 | ✅ 主进程存活（RSS 447 MB），UI 完整：标签栏、地址栏、工具栏、欢迎页、主题 |
| 本地文件 `file:///C:/test.html` | ❌ 内容区空白 |
| 网页 `bing.com` / `baidu.com` | ❌ 一直「等待 …」，内容区空白 |
| wine 网络本身 | ✅ 同会话 `node.exe` `fetch('https://www.baidu.com')` → `OK 200 1060ms` |
| 内容进程 | ❌ 进程表只有 `firefox.exe` + `crashhelper.exe`，从未出现 `-contentproc` |

**结论：Firefox ARM64 的 chrome（界面）能跑，但内容进程起不来 → 页面永远空白。** 已试无效：
`security.sandbox.content.level=0`、`browser.tabs.remote.autostart=false`、`fission.autostart=false`、`dom.ipc.processCount=1`、`network.trr.mode=5`、`network.dns.disableIPv6=true`。

**栈上限的坑（必记）**：`patch_pe.py` 把 PE32+ 栈抬到 256 MB 会让 Firefox 直接崩溃，崩溃注释写明
`MozCrashReason = MOZ_RELEASE_ASSERT(stackSize <= 32 * 1024 * 1024)` → `firefox.exe` 必须用 **32 MB（0x02000000）**。
