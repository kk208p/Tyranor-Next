# `feat/embedded-only` 改动说明（Fork 专用）

本文档描述个人 Fork 中 `feat/embedded-only` 分支相对上游 `main`（基线 `0dfb71ff`）的全部改动，仅用于本 Fork 留档，不提交上游。

## 1. 目标与范围

- **目标**：构建一个纯内嵌运行时的测试版 APK：产物中不含外置 APK 委托、外置模拟器跳转及其 UI/依赖/清单配置，但保留内嵌引擎与游戏库扫描/识别/存档/设置/UI。
- **裁剪对象**：Ren'Py、RPG Maker XP/VX/VX Ace/mkxp-z（RGSS 外置 APK）、Ruffle（Flash 外置 APK）、Winlator（YU-RIS / CatSystem2 / PC）、PPSSPP（PSP）、Eden（Nintendo Switch）。
- **保留对象**：Kirikiri（Kirikiroid2 / krkrsdl3）、ONScripter、Artemis、TyranoBuilder、Siglus、RealLive / AVG32 / UK2、FVP、System35、System4、RPG Maker MV/MZ（内嵌 Web）、VN/WebOther、Ethornell/BGI（实验），以及扫描/识别/启动/存档/设置/UI 链路。
- **非目标**：不向上游提 PR；不做引擎逆向、VM 重写、静态重编译、Hook、兼容层实现或第三方移植包分析；不改上游 `main`。

## 2. 分阶段提交

| 阶段 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
| P0 | —（无代码） | 通读文档，产出理解摘要/差异冲突/待确认项（本地报告） | 只读 |
| P1 | `3afa09fb` | 移除 Ren'Py 外置模块、版本检测、设置卡；新增退役引擎过滤 | `assembleDebug` 通过；`testDebugUnitTest` 无新增失败 |
| P2 | `6198fddb` | 移除 RPG Maker RGSS 外置模块、RTP 资产、字体导入与设置卡 | 同上（修复一处存档管理入口可见性回归） |
| P3 | `a54e3453` | 移除 Ruffle 外置模块、扫描别名、设置页 | 同上；测试基线随删除用例更新 |
| P4 | `f9baac85` | 移除 Winlator（YU-RIS/CatSystem2/PC）委托、PC 添加入口、WinlatorContract | 同上 |
| P5 | `cb8cfa2e` | 移除 PSP / Nintendo Switch 外置模拟器跳转与 PPSSPP 设置 | 同上 |
| P6a | `cd65be89` | 删除 `core/engine/external/` 共享脚手架（7 文件 + 2 测试） | 删除后重新固化测试基线（29） |
| P6b | `426273e3` | 删除 Manifest 外置 `<queries>`、Winlator 设置入口/卡片、YU-RIS 存档分支 | 同 P6a |

## 3. 删除的代码与资源

### 3.1 外置模块实现（`app/src/main/java/com/tyranor/next/core/engine/external/`）

- Ren'Py：`RenPyExternalEngineModule.kt`、`RenPyRuntimeEnvironment.kt`
- RPG Maker RGSS：`RpgMakerExternalEngineModule.kt`、`RpgMakerRuntimeEnvironment.kt`
- Ruffle：`RuffleExternalEngineModule.kt`、`RuffleRuntimeEnvironment.kt`
- 外置模拟器：`EmulatorTarget.kt`、`ExternalEmulatorRegistry.kt`、`ExternalEmulatorLauncher.kt`
- Winlator 契约：`WinlatorContract.kt`
- 共享脚手架（P6a 删除）：`ExternalEngineContract.kt`、`ExternalEngineLauncher.kt`、`ExternalEngineLaunchRequest.kt`、`ExternalEngineLaunchResult.kt`、`ExternalEngineModule.kt`、`ExternalEngineModuleRegistry.kt`、`RuntimeModuleJson.kt`

### 3.2 启动与扫描

- 删除 `core/game/launch/YurisLaunchFiles.kt`（YU-RIS/PC 启动文件枚举）。
- `core/game/scan/RenPyVersionDetector.kt` 删除；`EngineScanner` 不再产出外置模块别名（`internal.flash` 等），Ren'Py/Ruffle 版本探测移除，但**保留引擎检测分支**用于识别历史记录。
- `EngineLauncher`：外置模块启动整块与 `launchWindowsViaWinlator`、外置模拟器分流、`resolveLaunchEntryPath` 系列函数删除；`supportedEngines` 收敛为内嵌引擎；对外置引擎统一返回 `LaunchResult.Failure.StartFailed`，不再落入内置链路。
- `LaunchResult` 删除 `ExternalModuleFailed` / `YurisExeMissing`；`LaunchErrorMessages`/`LaunchErrorDialog` 相应简化。

### 3.3 UI 与设置

- 删除 `ui/settings/RenPySettingsCard.kt`、`RpgMakerSettingsCard.kt`、`RpgMakerRtpMessages.kt`、`RuffleSettingsScreen.kt`；`EngineSettingsMenuActivity` 删除 Ren'Py/RPGM/Ruffle/Winlator/PPSSPP 菜单项。
- `SettingsScreen`：移除各外置引擎设置卡与 Winlator 全局卡（`WinlatorValueRow/Dialog` 组件保留，Tyrano Web 端口复用）；`PerGameSettingsScreen`：移除外置字体导入、各外置引擎单游戏覆盖卡与 Winlator 覆盖卡。
- `ui/game/PcGameAddDialog.kt` 与「添加 PC 游戏」入口删除；`EngineScreen` 移除外置安装态探测/下载入口与外置跳转弹窗。
- `app/src/main/AndroidManifest.xml`：删除外置 `<queries>`（JoiPlay Ren'Py/RPGM/Ruffle、PPSSPP、Eden、Winlator）；保留 MAIN/LAUNCHER intent 查询（「添加安卓游戏」枚举需要）。

### 3.4 资源与测试

- `app/src/main/assets/rtp/sf.sf2`（4.1MB，外置 RPGM RTP 专用）删除。
- 随实现删除对应单元测试：`RenPyExternalEngineModuleTest`、`RenPyRuntimeEnvironmentTest`、`RenPyVersionDetectorTest`、`RpgMakerExternalEngineModuleTest`、`RpgMakerRuntimeEnvironmentZipTest`、`RtpImportReplaceSemanticsTest`、`CustomFontStagingTest`、`RuffleExternalEngineModuleTest`、`RuffleRuntimeEnvironmentTest`、`RuffleSettingsKeysTest`、`YurisLaunchFilesTest`、`WinlatorContractTest`、`ExternalEmulatorRegistryTest`、`PpssppVersionSettingsTest`、`ExternalEngineModuleRegistryTest`、`LaunchEntryPathTest`。

## 4. 共享代码的隔离与保留

- 新增 `app/src/main/java/com/tyranor/next/core/game/RetiredEngines.kt`：`RETIRED_EXTERNAL_ENGINES`（RENPY、RPGMAKER、RUFFLE、YURIS、CATSYSTEM2、PC、PSP、NINTENDO_SWITCH），用于：库层过滤（`GameLibraryRepository` 的 loadGames/loadRecentGames/searchGames/loadQuickLaunch）、启动总闸防御、存档管理入口隐藏。
- 保留 `EngineType` 全部枚举值：历史 Room 记录不会因删枚举而崩溃；这些记录被库层过滤隐藏，不再出现在游戏列表。
- 保留 legacy 字段与键（Room 兼容，避免数据库迁移）：`ScanGame.externalModuleAlias`、`games.external_module_alias`、`GameRecordCodec`、`EngineDetectionRepository` 的 rpgmRuntime/mkxpzSupported、`EngineSettingsStore` 的外置引擎键、三语言中已不可达的外置文案。后续如需彻底清理，另行处理。
- 保留的共享抽象：`LaunchContract`、`LaunchResult`、`EngineSettingsResolver`/`EffectiveEngineSettings`、`EnginePluginBootstrap`、`EngineSessionRegistry`、engine 模块各内嵌宿主。

## 5. 构建与 JVM 测试

```bash
./gradlew assembleDebug --no-daemon
./gradlew testDebugUnitTest --no-daemon
```

- `assembleDebug`：BUILD SUCCESSFUL；APK `app/build/outputs/apk/debug/app-debug.apk`，`171,630,260` B，versionName `1.62` / versionCode `1062000`，sha256 `e32c6cefa48b9aaa316f81ec453893c361d8ec4141abd0e4cc190244f5c88170`。
- `testDebugUnitTest`：`app` + `engine` 两个模块合计失败 29 个，全部为本机 Windows 平台差异（POSIX 符号链接/权限、大小写不敏感文件系统等），在 `compare-tests` 基线下 **0 个新增失败**；Linux CI 为绿（上游基线 `0dfb71ff` 的 CI 亦为 success）。
- 测试计数变化来自随代码删除的用例（尤其 `LaunchEntryPathTest` 的 7 个 Windows 专有失败），因此 P6 收口后基线由 39 更新为 29。

## 6. 真机验证（Android 16，vivo PD2338 / V2338A）

| 验证项 | 结果 |
| --- | --- |
| 安装 / 冷启动 | PASS（`adb install -r`；MainActivity 启动，无 FATAL） |
| 首页 / 游戏 / 引擎 / 设置 | PASS（四个 Tab 正常） |
| 扫描识别 | PASS：真实 Kirikiri 游戏「9-nine-九个九日九色」（`data.xp3`）被正确识别；合成 RPG Maker MV 工程被识别为 MV |
| 内嵌引擎启动 | PASS：Kirikiri 真实游戏进入 `Kirikiroid139` 并渲染标题画面（logcat `TVPMainScene startup completed`）；MV 合成工程进入内嵌 Web 宿主（`TyranoActivity`）并执行页面 JS |
| 外置入口消失 | PASS：引擎页、设置页、引擎设置菜单（9 项）、单游戏菜单均无 Winlator / PPSSPP / Eden / Ren'Py / RPGM / Ruffle |
| 存档 / 设置 / 字体 | PASS：存档管理页（KRKR 独立存档目录、导出/导入/删除）；单游戏 KRKR 引擎设置页；全局「默认字体 / 强制使用默认字体」卡片 |

未覆盖：ONS、Artemis、Siglus、Tyrano、RealLive/AVG32/UK2、FVP、System35、System4、Ethornell（实验）因缺少对应游戏样本，未做真机启动验证；这些引擎的宿主代码与注册链路均未改动。

## 7. 回滚与同步

- 回滚：`git switch main` 即可回到与上游一致的完整功能分支；裁剪只存在于 `feat/embedded-only`。
- 单点回滚：任一阶段提交可单独 `git revert`，但 `cd65be89`（脚手架删除）依赖前序提交已删完所有调用方，回滚需按 P6b → P6a → P5 → … 的逆序处理。
- 上游同步：`git merge upstream/main`；预期冲突集中在 `EngineLauncher` 启动分流、`EngineScanner` 检测分支、`EngineSettingsMenuActivity`/`SettingsScreen`/`PerGameSettingsScreen` 设置入口，以及三语言 `strings.xml`。
