# TV-Replica

面向遥控器操作的 Android TV 启动器，提供玻璃风格菜单、动态壁纸和 HDMI 设备卡片。

[English](README.md)

本仓库基于 [CleanYourTV](https://github.com/Gathin23/cleanyourtv) 的启动器模块开发，保留原有 MIT 许可及作者署名，详见 [LICENSE](LICENSE) 和 [UPSTREAM.md](UPSTREAM.md)。

## 功能

- 每行六个应用卡片、底部 Dock、直接在主页排列应用。
- Profile pill 内显示 LAN / Wi-Fi / 离线状态圆标。
- 第一层菜单为 Profile、Add profile、Connection、Input、All Settings。
- HDMI 连接状态与设备识别；可识别内容取决于电视固件。
- Saturn、Earth、Weather、Flip Clock 场景，各有独立设置，并提供本地屏保。
- 地球使用 4K 昼夜贴图，渲染上限固定为 1080p，支持安全的 2K 回退和旋转、倾斜、缩放。
- 12 / 24 小时制、Profile 照片缓存与柔和导航音效。

## 构建

需要 JDK 17、Android SDK platform 34、build-tools 34.0.0；电视需 Android 8.0 / API 26 或以上。仓库带有 Gradle 8.9 wrapper。

```sh
git clone https://github.com/lmnjun/TV-Replica.git
cd TV-Replica
(cd launcher && ./gradlew testDebugUnitTest lintRelease assembleRelease)
```

通过 `ANDROID_HOME` 或本地 `launcher/local.properties` 配置 SDK。安装包输出至 `launcher/app/build/outputs/apk/release/`。

目前保留应用 ID `com.gathin.tvhome`。开发构建使用本机生成的 debug 签名，仓库不包含签名密钥。覆盖已有应用需要相同签名，请保留自己的签名及设备数据。

## 安装与操作

在电视开启开发者选项并授权 ADB 后，使用 Android platform-tools 安装自己构建的 APK：

```sh
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.gathin.tvhome/.MainActivity
```

在系统的 Home 设置中选择启动器；部分固件还需辅助功能接管 Home。修改默认主页前，请保留进入系统设置与原桌面的恢复路径。投屏、语音、HDMI 与系统更新由电视固件管理。

在 All Settings → Wallpaper scene 选择壁纸并进入对应设置。Earth 的 Explore scene 使用方向键旋转和倾斜，频道键或快进／快退缩放，OK 切换 1× / 1.5×，Back 保存退出。

## 兼容性与性能

HDMI 信息、Google 账号、默认主页、刷新率与待机行为均受厂商固件影响。设备资料不足时，卡片回退为 HDMI 端口和连接状态。

目标是流畅的 60fps 交互，但窗口和显示表面的帧率请求不保证生效。固件维持其他刷新率时，无法承诺稳定 60fps。应用导航不会降低地球画质；部分设备的首次菜单和场景切换仍可能有长帧。

## 隐私与素材

账号名称、照片、应用顺序及偏好保存在电视本机。IP 定位会联系 `ipwho.is`，天气会将坐标发送给 Open-Meteo，详情见 [PRIVACY.md](PRIVACY.md)。

公开仓库不包含设备备份、账号导出、截图、ADB 凭据、签名密钥或本机构建配置。地球地图为 NASA / NOAA 历史合成资料，并非实时卫星画面；素材说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
