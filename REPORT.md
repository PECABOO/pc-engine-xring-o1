# 小米平板 7 Ultra（XRING O1 / Mali-G925）跑 Windows 程序 —— 通宵攻坚报告

> 时间：2026-11-17 20:20 → 2026-11-18 01:45（PC 通过 adb 远控平板，全程未重启系统、未改系统分区）

## 一句话结论
**官方 PC 引擎（Wine 10.5 + WOW64 + FEX + DXVK）已经能在这台 Mali 平板上跑任意 exe**：桌面、窗口、输入、x86/x64/ARM64 三种架构、甚至 Electron 22 级的现代应用（VSCode 1.80.2）都在真机上验证过了。剩下的是「安装器」和「Electron >22」两类硬限制。

## 关键突破（按时间顺序）

| # | 突破 | 说明 |
|---|---|---|
| 1 | `chen.test.run` 广播后门 | `WindowChannel.init()` 用 `registerReceiver(receiver, filter, 2)`（RECEIVER_EXPORTED）注册，任何 uid 都能触发；handler 里硬编码 `/sdcard/MiSide-GoldBerg/MiSideFull.exe` + `runNewApp(..., isUnixPath=true)` |
| 2 | 引擎 mount namespace 内 bind mount | App 由 zygote fork，独立 namespace：必须 `nsenter -t <引擎pid> -m -- mount --bind`，全局 bind 无效 |
| 3 | Zygisk 模块 `winplay_run` 打通 | 在引擎进程里反射调 `WindowChannel.runNewApp(path, HashMap, isUnixPath)`，**支持任意 DOS 路径**（`C:\apps\...`），彻底绕开 `WineActivity exported=false`。编译必须 `-fno-gnu-unique`（否则 Zygisk Next 内建 linker 报 `unexpected ST_BIND value: 10`），且不能引用 `atoi/strtol`（glibc 会换成 `__isoc23_strtol`，bionic 无此符号） |
| 4 | `--force-device-scale-factor=1` | Chromium 的 `GetMonitorScaleFactor()` 被短路，绕开 WinRT `UISettings2::GetTextScaleFactor` 桩 → GDI 句柄不再耗尽（21812 次 → 2 次） |
| 5 | PMCCNTR 补丁 | Android 内核不给 EL0 读 `pmccntr_el0` → `SIGILL(0xC000001D)`。把 `d53b9d09/d53b9d14` 改成 `d53be049/d53be054`（改读 `cntvct_el0`） |
| 6 | 栈保留抬高 | `Code.exe` `SizeOfStackReserve=8 MB` → `0x10000000`（256 MB），否则 `EXCEPTION_STACK_OVERFLOW` |
| 7 | 一键启动器 | 平板上的 `com.dsh.winplaylauncher`（33 KB APK）点图标即启动，8 秒后自动熄屏护 OLED |

## 真机验证过的应用
| 应用 | 架构 | 结果 |
|---|---|---|
| 7-Zip 24.08 / Notepad++ / SumatraPDF / WinRAR / PuTTY | x64 / x86 | ✅ 完整 GUI |
| Notepad++ 8.7.1 ARM64 | `0xaa64` | ✅ 最快（无 FEX） |
| **VSCode 1.70 / 1.80.2** | ARM64 Electron 19/22 | ✅ 完整界面（Welcome、Copilot 卡、状态栏） |
| Steam（引擎自带） | i386 | ✅ 商店页完整渲染 |

截图见 `shots/`。

## 明确跑不动的（附原因）
| 目标 | 原因 |
|---|---|
| VSCode 1.146、Obsidian 1.7.7（Electron ≥25） | V8 sandbox 要 **1.09–1.51 TB 连续虚拟地址**；设备 39 位用户空间 = 512 GB，物理不可能。`--js-flags=--random-seed` 只多试一次 512 GB 仍失败 |
| WPS Office 12.1（x64/Qt5） | 进程起、拿到 fastipc、甚至建出 `OpusApp` 主窗口和 surface，但卡在 `anon_pipe_read`（等自己的 ksolaunch），无窗口、无日志 |
| LibreOffice（MSI 扁平解包） | `mergedlo.dll` 依赖 `libcrypto-3.dll`，MSI 有改名表（`libcrypto_3.dll` → `libcrypto-3.dll`），手补同名副本后 wine 仍报 not found；改走 `msiexec /qn` 能装成功（1.4 GB），但 `soffice.bin` 起来后自行退出 |
| Inno Setup / NSIS 类安装器 | 32 位 stub 在 wow64+FEX 下卡 `read(fd=8)` 死锁；解决方式是**PC 上解包**（`7z x`）后 `wapp.sh install` |

## 关键机制备忘
- **只有 `runNewApp` 创建的顶层进程能拿到 Surface**：fastipc fd 带 `O_CLOEXEC`，子进程重新注册；孙进程（如 wps.exe 的 UI 线程）建的 window_surface 不会被 Java 侧接管。
- **`WineActivity` 必须在前台**：否则 `runNewApp` 静默无效（无进程、无日志）。`wpctl.sh run` 已内置「亮屏 + `am start WineActivity`」。
- **不需要 Steam**：`am start -n com.xiaomi.winplay/.WineActivity` 就能起桌面（40 s）；`winplay://opensteam` 只是把 Steam 当壁纸。
- **参数只能走 `.bat`**：`wine_run_app(argv)` 会 `ERR wine_run_app threw`，严重时带崩引擎。
- **`dosdevices/` 只能有 c:/d:/z:**，多加 `unix` 会让引擎永久卡「正在启动99%」。

## 工具
| 文件 | 用途 |
|---|---|
| `wpctl.sh` | boot/stop/wake/run/shot/off/dim —— 引擎与会话控制 |
| `wapp.sh` | `install <zip|exe|dir> <name> [args]`：解包 → patch → push → 注册到 apps.json |
| `patch_pe.py` | 只改可执行段的 stack/PMCCNTR 补丁 |
| `launcher/` | 平板端 APK + 构建脚本 + 网桥守护进程 |
| `module/` | Zygisk 模块源码与产物 |

## 下一步建议
1. **装应用的正确姿势**：PC 上 `7z x` 解包 → `./wapp.sh install <dir> <名字>` → 平板启动器里点开。不要在 wine 里跑安装器。
2. 需要新版 Electron 应用时，挑 **Electron ≤22** 的历史版本（VSCode ≤1.80）。
3. WPS 若要跑，需要单独攻「ksolaunch 管道握手」或改用 Linux 版 WPS（引擎的另一条 MSLgRdp 路线）。
