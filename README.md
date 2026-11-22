# 充电功率悬浮（ChargeFloat）

[![Android CI](https://github.com/SMMYFMM/cdgl/actions/workflows/android.yml/badge.svg)](https://github.com/SMMYFMM/cdgl/actions/workflows/android.yml)

给红米 K80（小米 HyperOS）做的实时充电功率悬浮窗。打开开关后，功率会以悬浮小窗显示在所有界面之上，单位 W。

## 功能

- **悬浮显示实时功率**：充电时显示 `+63.4 W`，放电时显示 `4.8 W`，插电未充电显示待机功率
- **自由排版**：功率、电压、电流、平均功率、峰值、充入 Wh、时长、电量、温度、状态文字十项，每项独立开关，可自由分到 3 行、调整行内顺序、逐项配色（也可设为跟随状态色）
- **平均功率**：按统计窗口（插电 / 拔电各自重新计时）实时计算，充电时是平均充电功率，放电时就是平均功耗
- **字号**：主字号 8–48sp，副行比例 20%–100% 可调，也可一键统一所有行字号
- **状态栏快捷开关**：下拉控制中心一键开关悬浮窗，可在 App 里直接请求添加
- **可拖动**：上下左右都能拖，位置会记住；单击收起 / 展开副行；可锁定位置。顶部会贴紧状态栏下沿，但不能进入状态栏区域
- **保活**：前台服务 + 低优先级通知，支持开机自启

## 数据来源与口径

功率 = 电池端电压 × 电池端电流，和 Scene 的充电统计口径一致。

读取优先级：

| 顺序 | 来源 | 说明 |
| --- | --- | --- |
| 1 | `/sys/class/power_supply/battery/current_now` `voltage_now` | 小米一般可读，精度最高 |
| 2 | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` / `VOLTAGE` | 系统 API，回落方案 |
| 3 | `ACTION_BATTERY_CHANGED` 的 `EXTRA_VOLTAGE` | 最后兜底 |

可选开启 Root，用 `su -c cat` 读系统节点（部分机型普通应用读不到电流时有用）。

所以 **90W 充电器显示 60–80W 是正常的**：这是电池端实际功率，包含充电器到电池的转换损耗，且电池电压随电量上升而变化。

## 首次使用

1. 安装 APK，打开 App
2. 点「授予悬浮窗权限」，在系统列表里打开本应用开关
3. 点「允许通知」（Android 13+ 保活需要）
4. 点「忽略电池优化」
5. 小米 / 红米额外设置：点「小米后台权限」，或在安全中心里打开 **自启动**、**后台弹出界面**，并把省电策略设为「无限制」
6. 回到 App 点「开启悬浮窗」，然后回桌面即可看到

## 读数异常时

点「诊断电池节点」，会列出本机所有 `power_supply` 节点、权限、数值，以及 BatteryManager 的原始返回值，复制出来即可定位是哪个环节拿不到数据。

## 排版怎么调

主界面 → 显示设置 → **内容与排版**。

- 每项左边是开关，决定显不显示
- 点「第 X 行」在 1 / 2 / 3 行之间切换
- ↑ ↓ 调整同一行内的先后顺序
- 点色点改这一项的颜色，第一项是「跟随状态色」：充电红、放电蓝、待机灰
- 顶部预览用的就是悬浮窗的渲染代码，看到什么就是什么

## 构建

需要 JDK 17+ 和 Android SDK Platform 35 / Build-Tools 35。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

release 包用仓库内的 `signing/chargefloat.jks` 签名，所以本地和 CI 出的包签名一致，可以直接覆盖安装。签名取值顺序是：环境变量 → 仓库内置密钥 → debug 签名。

| 变量 | 说明 |
| --- | --- |
| `KEYSTORE_FILE` | 密钥库绝对路径 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名，默认 `chargefloat` |
| `KEY_PASSWORD` | 密钥密码，缺省同密钥库密码 |

## 自动构建

推送到 `main` 或发起 PR 时，GitHub Actions 自动编译 release APK 并上传为构建产物（Actions 页面底部 Artifacts，保留 30 天）。

打 tag 会额外发布 Release 并附上 APK：

```bash
git tag v1.2 && git push origin v1.2
```

签名默认使用仓库内置的 `signing/chargefloat.jks`（即 Android 标准 debug key，公开不构成额外风险，适合自用分发）。

如果想换成自己的独立密钥，在 **Settings → Secrets and variables → Actions** 添加下面几项即可，CI 会优先使用它们，仓库里的那份不用删：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库的 base64（`base64 -w0 your.jks`） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

> 换密钥后签名会变，手机上必须先卸载旧版再装。

> 国内网络本地构建慢的话，可把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`。

## 工程结构

```
app/src/main/java/com/k80/chargefloat/
├── Power.kt        采样与功率计算、统计窗口、节点诊断
├── Layout.kt       字段定义、排版配置（JSON）、悬浮窗渲染器
├── FloatService.kt 悬浮窗前台服务（息屏自动降频）
├── MainActivity.kt 主界面与设置
├── LayoutActivity.kt 内容与排版编辑页（带实时预览）
├── ChargeTile.kt   状态栏快捷开关
├── Prefs.kt        设置存储
└── BootReceiver.kt 开机自启
```

## 耗电控制

- 息屏后不再绘制悬浮窗（本来也看不见），采样间隔从 1 秒放宽到 20 秒，只维持电量积分
- 采样用的是普通 Handler 回调，不持有 wakelock，设备休眠时不会为了刷新把手机唤醒
- 显示内容没有变化时不调用 `updateViewLayout`，避免每秒一次的窗口刷新
- 采样读的是两个几十字节的 sysfs 节点，单次耗时不到 1 毫秒

## 已知限制

- 系统电流更新频率通常为 1–2 秒，采样再快也不会更灵敏
- 无 Root 时，部分 ROM 的 `current_now` 只给 10–50mA 粒度，显示会有小幅跳动
- HyperOS 若回收后台，悬浮窗会消失，需要按上面第 5 步放行
- 锁屏界面默认不显示悬浮窗（系统限制）
