# `module/` 里的东西是什么

| 文件 | 说明 |
|---|---|
| `arm64-v8a.so` | **已在本机实测通过**的 Zygisk 模块二进制（54768 B）。模块 zip 里的 `zygisk/arm64-v8a.so` 就是它 |
| `winrun.cpp` | 参考实现（v1.0.0）。它与上面那个 .so **不是同一份源码的编译产物**：v1.0.0 里还保留了 `wine_run_app` 实验分支（该分支在实机上必抛异常，会导致引擎进程整体崩溃），发布的 .so 已移除该分支、只走 `runNewApp` |
| `zygisk.hpp` | Zygisk API v5 头文件，来自 [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk/blob/master/native/src/core/zygisk/api.hpp) |

## 为什么保留这个二进制

它是唯一经过完整验证的版本：在 `com.xiaomi.winplay` 进程内反射 `MultiWindowManger.getWindowChannel()` → `WindowChannel.runNewApp(path, HashMap, isUnixPath)`，从而绕开 `WineActivity` 的 `exported=false`。改动它需要重启 zygote 才能验证，风险高于收益，所以按「已验证二进制 + 参考源码」的方式保留。

## 编译参数（要自己重建时照这个来）

```bash
# 必须 -fno-gnu-unique：否则 GCC 会把 zygisk::internal::entry_impl<Mod>::api 发成
# STB_GNU_UNIQUE(10)，Zygisk Next 的内建 linker 只认 GLOBAL/WEAK，直接拒绝加载：
#   unexpected ST_BIND value: 10 ... preload failed
aarch64-linux-gnu-g++ -std=c++17 -O2 -fPIC -shared -nostdlib \
  -fno-exceptions -fno-rtti -fno-stack-protector -fno-threadsafe-statics \
  -fno-asynchronous-unwind-tables -fno-gnu-unique -w \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I. \
  winrun.cpp -o arm64-v8a.so \
  -Wl,--unresolved-symbols=ignore-all -Wl,-soname,winplay_run.so \
  -Wl,-z,max-page-size=0x4000 -Wl,-z,separate-code -Wl,-z,now -Wl,-z,relro \
  -L./stubs -Wl,--no-as-needed -lc -lm -ldl -llog
```

两个额外的坑：

- 源码里**不能出现 `atoi/atol/strtol`** —— Ubuntu 26.04 的 glibc 会把它们改写成 `__isoc23_strtol`，bionic 没有这个符号，加载时报 `cannot locate symbol "__isoc23_strtol"`
- 命令文件是 `/storage/emulated/0/wpz/cmd2.txt`（app 域写不了 `/data/local/tmp`），行格式 `path|isUnix|seq|args|envs`，`seq` 用 `date +%s` 单调递增
