# FlowZ Android（骨架）

FlowZ 桌面客户端（Electron + sing-box）的安卓版，放在 fork `hw20201/FlowZ` 的
`android/` 分支下，桌面代码不动。

## 骨架包含什么

- Kotlin + Compose 单页：连接 / 断开按钮 + 状态显示。
- `FlowzVpnService`：VpnService + libbox 的 `CommandServerHandler` /
  `PlatformInterface`（OpenTun 建隧道、protect 回调），核是 sing-box v1.14.4
  的官方 libbox（`experimental/libbox`，gomobile 打包）。
- 演示配置：TUN 起 + 全直连，证明整条链路真实跑通，不含任何真实节点。

## 骨架不包含（v1 做）

- 订阅导入、节点列表、真实出站（vless / vmess / trojan / ss / hy2）。
- 前台通知：目前没有，后台可能被系统回收，届时重连。
- 真实网络监听、分应用代理、规则热重载、前置代理、WARP / Tailscale。

## 构建

CI（`.github/workflows/flowz-android-debug.yml`，只在 `android` 分支跑）：

1. clone sing-box v1.14.4，用仓库自带的 `cmd/internal/build_libbox`
   以 `-debug`（仅 arm64）打出 `libbox.aar`，拷进 `android/app/libs`。
2. `./gradlew :app:assembleDebug`，产物 `app-debug.apk`。

本地编需要先有 `android/app/libs/libbox.aar`（同上一步），否则 gradle
会因缺文件失败。工具链：Go 1.25、JDK 17（build_libbox 强制要求
openjdk 17）、Android SDK + NDK。
