# 小米 Tag / Google Find Hub LSPosed 修复

这是一个针对国行小米手机的实验性 LSPosed 模块，用来恢复 Google Play 服务中的 Fast Pair / Find Hub（查找中心）配对流程，并诊断 Find Hub 在中国大陆显示 Xiaomi Tag 时的地图坐标偏移。

本项目已经在以下环境中完整跑通：手机发现 Xiaomi Tag、弹出配对页面、完成连接，并成功打开“储存最新的位置信息”。

## 已验证环境

- 手机：Redmi K80 Pro（`24122RKC7C`）
- 系统：国行 HyperOS `OS2.0.15.0.VOMCNXM`，Android 15
- Google Play 服务：`26.26.34 (260400-945364269)`
- Tag：国际版 Xiaomi Tag，Fast Pair model ID `15D23E`
- Root + LSPosed

其他手机、系统版本、Google Play 服务版本或 Tag 型号不保证可用。

## 下载与安装

从 [Releases](https://github.com/leowood2000/gms-fastpair-lsposed/releases/latest) 下载 APK，然后：

1. 安装 APK。
2. 在 LSPosed 中启用本模块。
3. 在 LSPosed 中勾选以下作用域：
   - **Google Play 服务**（`com.google.android.gms`），用于 Fast Pair / Find Hub 配对修复。
   - **Find Hub**（`com.google.android.apps.adm`），用于地图坐标诊断。
4. 重启手机。
5. 让 Tag 重新进入配对模式；确认听到两声后，将它靠近手机。
6. 按照 Google Fast Pair / Find Hub 页面完成连接。

如果升级 APK 时提示签名不一致，请先卸载旧版模块再安装新版，然后重新在 LSPosed 中启用并重启。

## 必须打开的三个开关

国行环境中的 Google Play 服务会通过内部 Phenotype 配置关闭 Find Hub 的关键路径。本模块把以下三个开关强制设为开启：

| GMS 内部开关 | 本版本对应方法 | 关闭时的表现 |
| --- | --- | --- |
| `EnableFindMyDeviceModule__enable_fast_pair_accessories` | `jwbd.g()` | `SpotFastPair.API` 返回 `API_UNAVAILABLE` / status 17，无法连接 Find Hub 服务 |
| `enable_fast_pair_spot_integration` | `jyxk.K()` | 扫描阶段返回 `DEVICE_NOT_SUPPORTED`，或定位 Tag 的最终连接页面失败 |
| `EnableFindMyDeviceModule__enable_self_location_reporting` | `jwbd.j()` | 开启“储存最新的位置信息”时失败，并出现 `Self location reporting is disabled` |

这些是 Google Play 服务内部的服务端/Phenotype 开关，不是 Android 的普通系统属性，因此用 `adb shell setprop` 修改手机型号或 fingerprint 并不能直接打开它们。

## 模块还做了什么

除了打开上面的三个开关，模块还会：

- 仅对 Fast Pair model ID `15D23E`，把定位 Tag 处理器 `drhl.e(dreq)` 返回的 `DEVICE_NOT_SUPPORTED` 改为 `SUCCESS`。
- 阻止 Google Play 服务在配对过程中禁用以下组件：
  - `HalfSheetActivity`
  - `FastPairSliceProvider`
  - `DiscoveryService`
  - `DevicesListActivity`
- 保留诊断日志，并自动隐藏扫描到的蓝牙 MAC 地址。

`InitialPairingDeviceChecker` 并不是本次 `DEVICE_NOT_SUPPORTED` 的根源。在已验证的 GMS 版本里，它负责扫描已经通过初步资格判断后的缓存设备/地址检查；真正需要处理的是 locator-tag 路径和上述三个内部开关。

## 查看日志

Windows PowerShell：

```powershell
adb logcat -c
adb logcat -v time | Select-String GmsFastPairDiag
```

也可以使用：

```powershell
adb logcat -s LSPosedFramework | findstr GmsFastPairDiag
```

日志中的关键内容包括：

- `enable_fast_pair_accessories`
- `enable_fast_pair_spot_integration`
- `enable_self_location_reporting`
- `bypass drhl#e DEVICE_NOT_SUPPORTED -> SUCCESS`
- 被 GMS 禁用但由模块保留的 Fast Pair 组件

地图诊断日志使用独立标签：

```powershell
adb logcat -c
adb shell am force-stop com.google.android.apps.adm
adb shell monkey -p com.google.android.apps.adm 1
adb logcat -v time | Select-String FindHubMapDiag
```

`v0.8.0` 只记录 Find Hub 传给 Google Maps SDK 的 Marker 和相机坐标及其调用堆栈，不修改任何经纬度。采集这些日志是为了确认 Xiaomi Tag 标点对应的混淆调用点；在确认之前不应全局 Hook `LatLng`，否则可能同时影响手机位置、地图相机和国外坐标。

## 编译

需要 JDK 17、Android SDK，以及 Gradle 8.9：

```text
gradle :app:assembleDebug
```

GitHub Actions 也会构建 APK，并将其作为 workflow artifact 上传。

## 兼容性与风险

- 本模块依赖 Google Play 服务 `26.26.34` 中的混淆类名和方法名。GMS 更新后这些名称可能变化，模块可能失效。
- 目前只对 `15D23E` 做了资格绕过；国行版 Xiaomi Tag 或其他 tracker 是否使用相同 model ID，需要单独验证。
- 开启 self-location reporting 后，Find Hub 会按照 Google 的产品机制保存或上报设备的最后位置。请只在理解该功能并接受其隐私影响时使用。
- Root、LSPosed、修改 Google Play 服务行为均有风险。本项目仅用于研究与个人测试，不保证适用于所有设备，也不保证通过任何完整性检查。

## 版本

`v0.7.0` 是首个完成全流程验证的版本，包含三个 Find Hub 开关、定位 Tag 资格修复以及 Fast Pair 组件保护。

`v0.8.0` 增加 Find Hub 应用作用域和只读地图坐标诊断，为后续仅针对中国大陆 Tag 标点的 WGS-84 → GCJ-02 修正定位调用点。
