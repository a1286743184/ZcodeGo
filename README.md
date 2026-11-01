# ZcodeGo

Zcode 的移动版（安卓远程客户端）：在手机上实时看到桌面端 ZCode 的会话列表与任务动态，
收到任务事件通知，并可直接打开远控页面操作。

> 工程代号 `ZcodeRemote`（Gradle 工程 / applicationId `com.zcodeapp.remote` 均沿用此名）。

本项目与 Z.ai 无隶属关系；ZCode 是 [zai-org/ZCode](https://github.com/zai-org/ZCode) 的产品。

## 来源与许可证

- 派生自 `zai-org/ZCode` 仓库的 `packages/android` 目录，上游锁定 commit
  `3c5b227649371507ddc4eb46ac5133afe6db1cab`（2026-10-20）。
- 全仓库以 Apache-2.0 分发（见根目录 `LICENSE`）。上游归属与第三方组件声明见
  [`third_party/NOTICE.md`](third_party/NOTICE.md)；哪些文件源自上游、改了什么，
  见 [`upstream.json`](upstream.json)。
- 其余代码（协议解析、会话层、保活、诊断日志、更新器等）为本项目原创。

## 功能边界

- 配对：粘贴配对链接 / 扫码 / `zcode://remote` 深链，三种入口等价。
- 会话与任务动态：订阅桌面端的 sessions/tasks 增量流，归一化为本地事件。
- 通知：任务事件转发为系统通知，含息屏提醒。
- 保活：前台服务 + WebView 会话维持，息屏不断流（能力受厂商限制，见已知限制）。
- 诊断日志：本地分块记录、可选上传（需自行配置私有仓库）。
- 应用内更新：默认走本项目 GitHub Release（`latest.json` 清单 + APK 资产，匿名可下载），
  自有分发渠道可通过 `local.properties` 的 `zr.updateManifestUrl` / `zr.updateFallbackUrl` 覆盖。

前置条件：需要一台已登录的 ZCode 桌面端，并从其远程控制页取得配对链接。

## 构建要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | `gradle/gradle-daemon-jvm.properties` 声明 toolchain 21，缺失时经 foojay 自动下载（需能访问外网） |
| Android SDK Platform | 36.1 | 注意是 36.1（minorApiLevel=1），不是 36.0 |
| Build-Tools | 36.1.0 | |
| Gradle | 9.4.1 | wrapper 自带，`distributionSha256Sum` 已锁定 |
| ANDROID_HOME | - | 或在 `local.properties` 写 `sdk.dir` |

`settings.gradle.kts` 默认优先走腾讯镜像加速；境外网络可删除两个 mirror 块。

## 构建与运行

```bash
./gradlew :app:assembleDebug      # Debug 包
./gradlew :app:testDebugUnitTest  # 单元测试
python tools/deploy.py            # 构建 + 装到在线设备 + 启动（可选脚本）
```

Linux/macOS 上如提示 gradlew 无执行权限：`chmod +x gradlew`。

`tools/` 下另有协议分析、WebView CDP 直连、资产脚本行为测试等辅助工具；
依赖维护者本地环境的脚本未随仓库分发。

## 签名（release）

仓库不分发 keystore。release 构建在以下配置齐备时才会签名，否则产出未签名包
（未签名包不要分发）：

- 环境变量：`ZR_KEYSTORE` / `ZR_KEYSTORE_PASSWORD` / `ZR_KEY_ALIAS` / `ZR_KEY_PASSWORD`
- 或 `local.properties`：`zr.keystorePath` / `zr.keystorePassword` / `zr.keyAlias` / `zr.keyPassword`

仓库内不内嵌任何口令。

## 可选配置（全部默认关闭，不配置不影响使用）

| 配置 | 位置 | 作用 |
|---|---|---|
| `zr.logUploadToken` | `local.properties`（仅 debug） | 本机自测时免手输日志上传 token |
| `zr.logOwner` / `zr.logRepo` | `local.properties` | 日志上传目标私有仓库（Gitee） |
| 设置页粘贴 token | App 运行时 | release 包上传日志的唯一凭据入口，只存本机 |

## 隐私说明

诊断日志只记类别、状态与计数：URL 丢弃 query 与 fragment（sid/token/code 在里面），
消息体不记。详见 [`docs/日志上传与拉取.md`](docs/日志上传与拉取.md)。

## 已知限制

- 页面内导航判据基于对上游页面 DOM 的实测，上游页面升级后需重新核对
  （[`docs/交互模型.md`](docs/交互模型.md) 有完整取舍记录）。
- 后台保活高度依赖厂商 ROM 策略，未在全部主流机型上验证。

## 贡献与安全

提交 issue / PR 前请确认不携带任何真实配对链接、token、日志文件。
发现安全问题请优先以不公开方式联系维护者，勿直接开公开 issue。
