# TeslaBleLab

TeslaBleLab 是一个用于验证 Tesla BLE 连接、配对和车辆数据读取的 Android 原生项目。它不是完整产品 UI，重点是把 BLE 协议、key 生命周期、VCSEC/Infotainment 会话和数据解析跑通，后续可以拆成实际项目里的功能库。

项目参考 Tesla 官方 `vehicle-command` 的 BLE/Universal Message 协议实现，并结合 Android BLE API 做了本地验证。

## 当前状态

- 可以通过 VIN 扫描 Tesla BLE 广播名，并只连接当前 VIN 对应的可连接设备。
- 支持 BLE GATT 连接、服务发现、通知订阅、MTU 协商和分片收发。
- 支持 VCSEC 域握手、配对请求、NFC 卡片钥匙授权流程。
- 支持本地私钥加密存储、删除本地 key、重新配对。
- 支持 Infotainment/CarServer 域会话，并按类别拉取车辆数据。
- 连接稳定性已做过实车验证，当前版本可作为后续开发基线。

## 已读取的数据

VCSEC:

- `vehicleLockState`: 锁定状态
- `closureStatuses`: 前左/右门、后左/右门、前备箱、后备箱、充电口、顶棚/tonneau 等开闭状态

CarServer/Infotainment:

- `DriveState`: 档位、速度、功率、里程、导航目的地/到达时间等
- `ChargeState`: 电量、充电状态、续航、充电速率、充电上限等
- `ClimateState`: 内外温度、空调状态、风量、座椅加热、方向盘加热等
- `TirePressureState`: 四轮胎压
- `MediaState`: 当前播放音乐、播放状态、音源、音量等

## 刷新频率

当前使用单写队列调度，避免 BLE 写入互相冲突：

| 类别 | 频率 |
| --- | --- |
| VCSEC VehicleStatus | 1000 ms |
| Drive | 250 ms |
| Closures | 1000 ms |
| Media | 2000 ms |
| Charge | 6000 ms |
| Climate | 9000 ms |
| Tire Pressure | 15000 ms |

说明：BLE 实际吞吐会受车辆状态、手机 BLE 栈、距离和当前写队列影响，所以上表是请求调度频率，不保证每一类响应都严格按这个时间返回。

## 配对和 key 语义

本项目里有两种状态容易混淆：

- BLE/VCSEC session 建立：表示手机和车辆完成了当前连接会话的加密握手。
- Vehicle authorization：表示当前本地公钥已经被车辆白名单接受。

UI 中现在会明确显示：

- `Checking Authorization...`: 正在检查当前 key 是否在车辆白名单
- `Pairing Request Sent`: 已发送配对请求
- `Tap Card Key Now!`: 需要在 B 柱 NFC 区域刷卡片钥匙，并在车机上确认
- `Authorized & Connected`: 车辆已经接受当前 key，已连接并开始拉取数据

`Delete Key` 只删除手机本地保存的私钥和相关本地状态，不会删除车机白名单里的钥匙。要彻底移除授权，需要在 Tesla 车机钥匙管理界面删除对应手机钥匙。

调试日志会打印当前本地公钥指纹，例如：

```text
Generated fresh local key for VIN: ... fp=xxxxxxxxxxxxxxxx
Loaded saved key for VIN: ... fp=xxxxxxxxxxxxxxxx
Key fp=xxxxxxxxxxxxxxxx is not on whitelist
VCSEC authorized by vehicle with saved key fp=xxxxxxxxxxxxxxxx
```

如果删除本地 key 后，没有刷 NFC 却看到 `fresh/in-memory key` 被车辆授权，需要对比这些 `fp` 日志，确认车辆端是否仍然保留了相同公钥。

## 编译

推荐使用 Android Studio 自带 JBR：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

debug APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

APK、Gradle build 目录、本地配置、日志和 keystore 都在 `.gitignore` 中，不应提交。

## 使用流程

1. 安装 debug APK。
2. 打开手机蓝牙和定位权限。
3. 输入目标车辆 VIN。
4. 点击 `Scan`，等待出现 Tesla BLE 设备。
5. 点击 `Connect`。
6. 如果是首次配对或本地 key 已删除，按提示刷 NFC 卡片钥匙，并在车机屏幕确认。
7. 状态变为 `Authorized & Connected` 后，等待数据刷新。
8. 遇到异常时点击复制日志，把日志和截图一起用于排查。

## 项目结构

```text
app/src/main/java/com/teslablelab/
├── data/
│   ├── repository/TeslaBleRepository.kt   # BLE 扫描、连接、收发、调度
│   └── storage/SecureStorage.kt           # 本地 key 加密存储
├── domain/
│   ├── crypto/TeslaCrypto.kt              # P-256、ECDH、AES-GCM、HMAC
│   ├── model/TeslaVehicleSnapshot.kt      # UI/业务可用的数据快照
│   └── protocol/                          # Universal Message / VCSEC / CarServer 协议
├── ui/MainViewModel.kt
└── MainActivity.kt                        # 验证 UI 和日志面板
```

Protocol Buffer 文件位于：

```text
app/src/main/proto/
```

## 注意事项

- 本项目只用于个人车辆和授权测试场景。
- 不要把个人 VIN、日志、私钥、车机截图提交到公开仓库。
- 不要把 debug APK 当成正式分发版本。
- 车辆睡眠、手机系统 BLE 缓存、距离和车机状态都可能影响连接耗时。
- 不同车型/车机版本可能会有字段为空或响应频率不同，需要继续做实车样本验证。

## 参考

- Tesla 官方 SDK: https://github.com/teslamotors/vehicle-command
- Android BLE API: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
