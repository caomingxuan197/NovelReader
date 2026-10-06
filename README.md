# 悦读 · NovelReader

Android 小说阅读应用，制作者：草莓熊。

本仓库保存 **1.0 版本**：`versionName = "1.0"`，`versionCode = 1`。

## 功能

- 导入本地 TXT，支持常见中文编码与最大 100 MB 文件。
- 书架管理：更改书名、删除书籍、记录阅读位置。
- 自动识别章节与目录跳转，左右翻页，调整字号和阅读背景。
- 阅读界面显示章节、进度、电量与时间。
- 首页 AAA 小说搜索，下载过程中可以进入阅读。
- 在线找书入口：奇书网、10086TXT。

## 打开与运行

1. 下载或克隆本仓库，在 Android Studio 中选择 **Open**，打开项目根目录。
2. 安装项目要求的 Android SDK（API 37），等待 Gradle 同步完成。
3. 连接 Android 7.0 或以上手机，或启动模拟器，点击 **Run**。

项目包含 Gradle Wrapper。现有构建配置使用 Gradle 9.6.0、Android Gradle Plugin 9.4.1、Kotlin 2.2.10，Gradle 的 JVM 配置指定 Java 25。建议使用支持这些版本的 Android Studio 及配套 JDK。

Windows 命令行构建调试 APK：

```powershell
.\gradlew.bat assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。发布时请自行配置并妥善保存签名密钥；仓库不含签名密钥或已打包 APK。

## 版本来源

核心代码、依赖和字形映射恢复自最后一份 1.0 更新包 `NovelReader-loading-rename`，项目外壳、图标及资源取自现有 Android Studio 工程。此仓库是整理后的 1.0 项目，不是当时整个工程的逐文件备份，不包含后续 1.1 的阅读动画等代码修改。

在线功能依赖第三方站点，站点变化可能导致功能失效。仓库不包含小说正文或用户书架数据。

## 第三方依赖

本项目附带 Brotli 解码库 `app/libs/brotli-dec-0.1.2.jar`，其许可证见 [Brotli-LICENSE.txt](Brotli-LICENSE.txt)。其他依赖由 Gradle 下载。
