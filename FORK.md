# Tyranor-Next（个人 Fork · 仅内嵌运行时）

本仓库是 [Weiss-UltimateSavior/Tyranor-Next](https://github.com/Weiss-UltimateSavior/Tyranor-Next) 的个人 Fork，目标是在**不改上游 `main`、不向上游提 PR** 的前提下，维护一个**纯内嵌运行时**分支：去掉外部 APK 委托与外部模拟器跳转，只保留内嵌运行时/兼容层/引擎，便于构建可安装的测试版 APK 做真机验证。

- Fork 地址：<https://github.com/kk208p/Tyranor-Next>
- 裁剪分支：[`feat/embedded-only`](https://github.com/kk208p/Tyranor-Next/tree/feat/embedded-only)
- 分支基线：`0dfb71ff`（= upstream/main）
- 分支状态：ahead 7、behind 0（与上游 `main` 对比）
- 详细改动说明：[`docs/fork-embedded-only-changes.md`](docs/fork-embedded-only-changes.md)

## 分支包含的提交

| 提交 | 内容 |
| --- | --- |
| `3afa09fb` | 移除 Ren'Py 外置模块 |
| `6198fddb` | 移除 RPG Maker RGSS（XP/VX/VX Ace/mkxp-z）外置模块 |
| `a54e3453` | 移除 Ruffle（Flash）外置模块 |
| `f9baac85` | 移除 Winlator（YU-RIS / CatSystem2 / PC）委托与 PC 手动添加入口 |
| `cb8cfa2e` | 移除 PSP / Nintendo Switch 外置模拟器跳转 |
| `cd65be89` | 删除 `core/engine/external/` 共享脚手架 |
| `426273e3` | 删除 Manifest 外置 `<queries>`、Winlator 设置入口与残余入口 |

## 裁剪后保留的能力

- 内嵌运行时/引擎：Kirikiri（Kirikiroid2 / krkrsdl3）、ONScripter、Artemis、TyranoBuilder、Siglus、RealLive / AVG32 / UK2、FVP、System35、System4、RPG Maker MV/MZ、VN/WebOther、Ethornell/BGI（实验）。
- 游戏库：扫描/识别、分类、最近游戏、快捷启动、封面刮削、存档管理、单游戏设置。
- 设置：首页/外观/主题、语言、引擎全局设置与单游戏覆盖（含字体、渲染、内存等）。
- UI：首页、游戏、引擎、设置四个页面与相关弹窗。

## 构建与验证

```bash
# 构建（默认 debug）
./gradlew assembleDebug --no-daemon
# 产物
app/build/outputs/apk/debug/app-debug.apk
```

- APK：`171,630,260` B（164MB），版本 `1.62`（versionCode `1062000`），sha256 `e32c6cefa48b9aaa316f81ec453893c361d8ec4141abd0e4cc190244f5c88170`。
- JVM 单元测试：`./gradlew testDebugUnitTest --no-daemon`。Linux CI 为绿；Windows 本机存在平台差异基线（0 个新增失败，详见改动说明）。
- 真机验证（vivo PD2338 / V2338A，Android 16）：安装/启动、UI、扫描识别、内嵌引擎启动（真实 Kirikiri 游戏 + 合成 MV 工程）、外置入口消失、存档与字体设置均已验证。
- 环境要求与上游 `README.md` 一致（JDK 17 CI、`android-37.0`/`android-36`、build-tools 37.0.0、NDK 28.0.13004108、CMake 3.22.1）。

## 上游同步

`main` 保持与上游一致；裁剪只存在于 `feat/embedded-only`：

```bash
git switch main
git fetch upstream
git merge --ff-only upstream/main
git push origin main

# 需要跟进上游时（可能有冲突，冲突集中在启动分流 / 引擎设置 / 文案资源）
git switch feat/embedded-only
git merge upstream/main
```

## 非目标

- 不向上游提 PR，不改上游 `main`。
- 不做引擎逆向、VM 重写、静态重编译、Hook、兼容层实现或第三方移植包分析。
- 本分支未接入上游 CI；推送后上游/本 Fork 的 Actions 结果以实际运行为准。
