# 老挝语↔中文翻译 Android APP

原生 Android 应用，支持在线/离线翻译、语音输入和语音播报。

## 功能

| 功能 | 说明 |
|------|------|
| 🔄 双向翻译 | 老挝语 ↔ 中文 |
| 🌐 在线翻译 | 百度翻译 API（质量最好） |
| 📴 离线翻译 | NLLB-200 ONNX 模型（断网也能用） |
| 🎤 语音输入 | Android 原生 SpeechRecognizer |
| 🔊 语音播报 | Android 原生 TextToSpeech |
| 📋 一键复制 | 复制翻译结果到剪贴板 |

## 安装

前往 [Releases](https://github.com/guocheng1378/translator-apk/releases) 下载最新 APK，传输到手机安装即可。

## 百度翻译 API 配置

1. 前往 [百度翻译开放平台](https://fanyi-api.baidu.com/manage/developer) 注册
2. 创建应用，获取 APP ID 和密钥
3. 在 APP 中点击右上角 ⋮ → 设置 填入

免费额度：每月 5 万字符

## 离线翻译

首次使用需下载 NLLB-200 量化模型（约 300MB），下载完成后断网即可使用。翻译质量不如在线 API，适合应急场景。

## 技术栈

- **UI**: Material Design 3 (原生 Android Views)
- **在线翻译**: 百度翻译 API + OkHttp
- **离线翻译**: NLLB-200 (ONNX Runtime)
- **语音识别**: Android SpeechRecognizer
- **语音合成**: Android TextToSpeech
- **语言**: Java

## 构建

```bash
# Android Studio 打开项目，或命令行：
./gradlew assembleDebug

# APK 输出在 app/build/outputs/apk/debug/
```

## 项目结构

```
translator-apk/
├── app/
│   └── src/main/
│       ├── java/com/laotranslate/translator/
│       │   ├── MainActivity.java       # 主界面（原生 UI）
│       │   ├── BaiduTranslate.java     # 百度翻译 API 客户端
│       │   ├── OfflineTranslator.java  # NLLB-200 离线翻译
│       │   └── SimpleTokenizer.java    # Tokenizer
│       └── res/
│           ├── layout/                 # 原生布局文件
│           ├── drawable/               # 图标和背景
│           ├── menu/                   # 菜单
│           └── values/                 # 颜色、主题、字符串
├── .github/workflows/build.yml         # GitHub Actions 自动构建
└── gradle/                             # Gradle Wrapper
```

## License

MIT
