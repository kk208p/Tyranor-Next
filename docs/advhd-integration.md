# AdvHD（WillPlus）内嵌运行时接入

> 2026-11-13 首次真机验证通过（vivo V2338A / Android 16 / arm64）。
> 运行时来源：[advhd-rs](https://github.com/kk208p/advhd-rs)（MPL-2.0，clean-room）。

## 架构

```
App 主进程                          :advhd 进程
EngineLauncher ──Intent──▶ com.core.advhd.AdvhdActivity
   │  预检：目录可读 / ARC 探针 / 字体 / 私有用户目录
   │  Scanner：根目录 ARC 头 + AdvHD*.exe 双证据
   ▼
engine/src/main/jniLibs/arm64-v8a/libadvhd_abi.so   （Rust，C ABI v2）
   ▲
engine/src/main/cpp/advhd_bridge.cpp                （JNI，Surface/GL 呈现）
```

- 运行时 API 见 `engine/src/main/cpp/advhd_abi.h`（vendored，ABI v2）。
- 文本/选项/消息窗由 Rust 渲染器（fontdue）绘制进 1280×720 RGBA 帧；
  JNI 桥只在 `advhd_is_dirty` 为真时重绘，等比缩放到屏幕并保留 16:9 黑边
  （强制 `RGBA_8888` 缓冲，避免 RGB_565 上的越界）。
- 音频不经过 Rust：ABI 把 ARC 内的 `.ogg` 抽取到
  `files/advhd/userdata/<sha256(identity)>/audio/`，宿主用 MediaPlayer 按通道播放。
- 存档：`files/advhd/userdata/<sha256(identity)>/slot1.json`（原子写入），
  v1 不接入 TYN 统一存档管理（`GameSaveManager` 对 ADVHD 返回不可用）。

## 设置

- 全局/单游戏：文本编码（SJIS/GBK/Big5/UTF-8/UTF-16LE）、字体（私有 `files/fonts`）、入口脚本。
- 翻译覆盖层（中文熟肉/机翻）：导入的 JSON 必须位于
  `files/advhd/translations/*.json`（≤ 8 MiB），保存**路径**而不是副本；
  运行时 `advhd_load_translation` 将其应用到台词、选项与回想。导入与文案校验
  在设置页（全局 + 单游戏）完成，Launcher 预检阶段为 `TRANSLATION`。
- 入口为空时运行时启发式：若 `start` 只做 `goto MAINMENU`/标题 UI，则优先
  `co_1_1` → `main_00` → `title`。
- 字体为空时：游戏 `字体包/`、`font/`、`Font/` 或根目录的 TTF/OTF/TTC →
  系统 `/system/fonts/NotoSansCJK-Regular.ttc`。

## 已验证（真机）

| 项 | 证据 |
| --- | --- |
| 扫描识别 | `華は短し、踊れよ乙女`、`chaoka` 识别为 AdvHD |
| 加载 | 190 脚本 / 168 项 arg 表；真机 `create` 1.4s（表扫描优化前 27s） |
| 画面 | 背景 CG + 立绘（身体+表情合成，脸/眼正确）+ 日文台词 |
| 选项 | 宿主弹窗显示选项；帧内不再重复绘制；选择后继续剧情 |
| 存读档 | 菜单保存 4,167 B JSON；读档**回到保存时那一句**（不跳下一句） |
| 回想 | 菜单「回想」显示最近 120 句，标记文本已清理（无 `%K%P`） |
| 音频 | `bgm12.ogg` 1,634,125 B、SE 若干从 ARC 抽出并播放 |
| 视频 | 根目录 `ED_KOH.dat`（WMV/ASF）经 IJK/FFmpeg 软解播放；点击可跳过并回到剧情 |
| 翻译 | `hana-chr10-1.zh.json` 导入后真机 `translation load rc=0`；人名「女学生B」/台词中文；存读档回到原句仍为中文；回想中文 |
| 退出 | 确认后 `:advhd` 进程结束、会话登记清除 |
| 显示 | 16:9 等比缩放 + 黑边；RGBA_8888 缓冲保护（RGB_565 曾 SIGSEGV，已修） |

## 已知限制（v1）

- 菜单/标题：Lua UI 层未实现；宿主提供原生菜单（继续/保存/读取/回想/退出），
  入口直接进入剧情脚本。
- E-mote 动态立绘、`.fx` 着色器未接入；视频已通过 IJK 软解接入（依赖 `PACK_IJK`，WMV/ASF 由 FFmpeg 解码）。
- 图层：位置/缩放/颜色/加色混合/PNA 部件/遮罩已实现；旋转、
  淡入淡出动画、`postprocess` 仍未渲染。
- 文本无逐字打字机、无自动/跳过模式（backlog 已提供）。
- 读档会恢复保存时的台词与画面，但不会回滚到该句的“输入前”状态。
- PC 原版存档互通未做；TYN 存档管理/WebDAV 不覆盖 AdvHD。

## 更新运行库

```bash
cd advhd-rs
cargo build --release --target aarch64-linux-android -p advhd-abi
cp target/aarch64-linux-android/release/libadvhd_abi.so \
   ../Tyranor-Next/engine/src/main/jniLibs/arm64-v8a/
# 同步更新 libadvhd_abi.provenance.json（commit / sha256）与 advhd_abi.h
```

## 安全基线

Mimosa 对 `advhd_bridge.cpp`/`AdvhdActivity.java` 的提交扫描按兼容策略放行；
发布前需完整审计（含 JNI 边界、MediaPlayer 生命周期、字体/存档路径校验）。
