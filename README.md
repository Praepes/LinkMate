# LinkMate

一个基于 Android 的智能家居控制面板应用，专为平板设备设计，支持 Home Assistant 集成、天气显示、提醒管理等功能。

## 项目简介

LinkMate 是一个横屏显示的智能家居控制面板应用，可以运行在 Android 平板设备上，作为家庭智能设备的控制中心。应用支持与 Home Assistant 系统集成，提供实时设备控制、天气信息显示、提醒管理等功能。

## 主要功能

### Home Assistant 集成
- 支持通过 WebSocket 和 REST API 连接 Home Assistant
- 实时显示和控制多种设备类型：
  - 灯光（支持亮度和色温调节）
  - 开关
  - 传感器
  - 空调（支持温度调节和模式切换）
  - 风扇
  - 按钮
  - 输入选择器（input_select）
- 设备图标动态颜色显示（灯实体根据亮度和色温显示不同颜色）
- 乐观更新机制，提供流畅的用户体验

### 天气显示
- 集成和风天气 API
- 支持多种定位方式：
  - GPS 自动定位
  - 手动输入位置
- 显示天气描述、温度、空气质量等信息
- 支持三种显示模式：
  - 垂直完整模式（1x2）
  - 紧凑模式（1x1）
  - 横向排列模式（2x1）

### 提醒管理
- 创建和管理提醒事项
- 支持提醒的激活和停用
- 新提醒浮动窗口通知
- 滑动切换多个提醒

### 设备状态上报
- 将 Android 设备状态上报到 Home Assistant：
  - 电池电量
  - 充电状态
  - 屏幕状态
  - GPS 位置信息
  - 屏幕亮度（作为 light 实体）
  - 屏幕常亮开关（作为 switch 实体）
- 可配置上报间隔

### 可定制布局
- 可拖拽的网格布局系统
- 支持自定义网格列数和行数
- 每个组件可独立调整位置
- 支持保存和恢复布局配置

### 本地 Web 服务器
- 内置 Ktor Web 服务器
- 提供 RESTful API 接口
- 支持密码保护
- 可用于远程控制或第三方集成

### 屏幕控制
- 屏幕亮度管理（可通过 Home Assistant 控制）
- 屏幕常亮开关（可通过 Home Assistant 控制）
- 支持接近传感器唤醒（可选）

### 主题定制
- 支持动态系统主题
- 支持自定义主色调
- 自动适配浅色/深色模式

## 技术栈

### 核心框架
- **Kotlin** - 主要编程语言
- **Jetpack Compose** - UI 框架
- **Material 3** - 设计系统
- **Android Architecture Components** - 架构组件
  - ViewModel
  - LiveData / StateFlow
  - Room Database
  - Navigation Component

### 依赖注入
- **Hilt** - 依赖注入框架

### 网络通信
- **Retrofit** - REST API 客户端
- **OkHttp** - HTTP 客户端和 WebSocket 支持
- **Ktor** - 嵌入式 Web 服务器

### 数据存储
- **Room Database** - 本地数据库
- **DataStore** - 配置数据存储

### 其他依赖
- **Coil** - 图片加载
- **Gson** - JSON 序列化（通过 Retrofit converter-gson）
- **Kotlin Coroutines** - 异步编程支持
- **Google Play Services Location** - 位置服务

## 系统要求

- **最低 Android 版本**: Android 5.0 (API 21)
- **目标 Android 版本**: Android 14 (API 34)
- **屏幕方向**: 横屏（Landscape）
- **设备类型**: 推荐平板设备

## 项目结构

```
app/src/main/java/io/linkmate/
├── data/
│   ├── device/              # 设备管理（亮度、屏幕控制等）
│   ├── local/               # Room 数据库实体和 DAO
│   ├── model/               # 数据模型
│   ├── remote/              # 远程 API 服务
│   │   ├── hefeng/          # 和风天气 API
│   │   └── homeassistant/   # Home Assistant API
│   └── repository/          # 数据仓库层
├── di/                      # 依赖注入模块
├── navigation/              # 导航配置
├── service/                 # 后台服务（Web 服务器）
├── ui/
│   ├── components/          # UI 组件（可拖拽网格等）
│   ├── screens/             # 屏幕组件
│   ├── theme/               # 主题配置
│   └── viewmodels/          # ViewModel
├── util/                    # 工具类
├── LinkMateApp.kt           # Application 类（Hilt 应用入口）
├── MainActivity.kt          # 主 Activity
├── MainScreen.kt            # 主屏幕
├── BakingScreen.kt          # 烘焙模式屏幕
├── BakingViewModel.kt       # 烘焙模式 ViewModel
└── UiState.kt               # UI 状态定义

```

## 配置说明

### Home Assistant 配置

1. 在设置页面输入 Home Assistant 服务器地址（例如：`http://192.168.1.100:8123`）
2. 输入访问令牌（Long-Lived Access Token）
3. 选择要显示的实体 ID
4. 应用会自动连接到 Home Assistant 并同步设备状态

### 天气 API 配置

1. 注册和风天气账号并获取 API Key
2. 在设置页面输入 API Key
3. 选择定位方式：
   - GPS：自动使用设备 GPS 定位
   - 手动：输入城市名称或坐标（格式：`经度,纬度`）
4. 设置刷新间隔（分钟）

### 设备状态上报配置

1. 在设置中启用"设备状态上报"
2. 设置上报间隔（分钟）
3. 应用会自动在 Home Assistant 中创建以下实体：
   - `sensor.{device_id}_battery_level` - 电池电量
   - `binary_sensor.{device_id}_charging` - 充电状态
   - `binary_sensor.{device_id}_screen` - 屏幕状态
   - `light.{device_id}_screen_brightness` - 屏幕亮度
   - `switch.{device_id}_keep_screen_on` - 屏幕常亮
   - `device_tracker.{device_id}` - 位置追踪

### Web 服务器配置

1. 在设置中启用 Web 服务器
2. 设置端口号（默认：8080）
3. 设置访问密码（4 位数字，首次启动自动生成）
4. 服务器会在应用启动时自动运行

## 权限说明

应用需要以下权限：

- **INTERNET** - 网络访问（连接 Home Assistant 和天气 API）
- **ACCESS_FINE_LOCATION** - 精确位置（GPS 定位）
- **ACCESS_COARSE_LOCATION** - 粗略位置（网络定位）
- **ACCESS_BACKGROUND_LOCATION** - 后台位置（可选，用于持续定位）
- **WAKE_LOCK** - 唤醒锁（保持屏幕常亮）
- **FOREGROUND_SERVICE** - 前台服务（Web 服务器）

## 构建和运行

### 前置要求

- Android Studio Hedgehog 或更高版本
- JDK 8 或更高版本
- Android SDK API 34

### 构建步骤

1. 克隆项目到本地
2. 使用 Android Studio 打开项目
3. 同步 Gradle 依赖
4. 配置签名（如需要发布版本）
5. 连接 Android 设备或启动模拟器
6. 运行应用

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug
```

## 开发说明

### 架构模式

项目采用 MVVM（Model-View-ViewModel）架构模式：

- **Model**: 数据层（Repository, Entity, API Service）
- **View**: UI 层（Compose Screens, Components）
- **ViewModel**: 业务逻辑层（处理数据流和 UI 状态）

### 数据流

1. Repository 层负责数据获取和缓存
2. ViewModel 观察 Repository 的数据流
3. UI 通过 StateFlow/LiveData 观察 ViewModel 状态
4. 用户操作通过 ViewModel 方法修改状态
5. ViewModel 通过 Repository 更新数据源

### 数据库迁移

Room 数据库版本变更时，需要在 `AppDatabase` 中添加迁移策略。数据库 schema 文件位于 `app/schemas/` 目录。

## 已知问题

- 首次连接 Home Assistant 时可能需要等待几秒钟
- 某些设备可能不支持屏幕亮度控制
- Web 服务器默认仅监听本地网络接口

## 未来计划

- [ ] 支持更多设备类型（摄像头、门锁等）
- [ ] 添加场景和自动化支持
- [ ] 支持多用户账户
- [ ] 添加数据备份和恢复功能
- [ ] 支持自定义图标和主题
- [ ] 添加语音控制支持

## 许可证

本项目采用 MIT 许可证。

## 贡献

欢迎提交 Issue 和 Pull Request。

## 联系方式

如有问题或建议，请通过 GitHub Issues 联系。

