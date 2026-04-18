# 🇱🇦 ↔ 🇨🇳 老挝语翻译

老挝语和中文双向翻译工具，支持在线/离线双模式、语音输入和语音播报。

![GitHub Actions](https://github.com/guocheng1378/translator-apk/actions/workflows/build.yml/badge.svg)

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🔄 双向翻译 | 老挝语 ↔ 中文 |
| 🌐 在线翻译 | 百度翻译 API（质量最好） |
| 📴 离线翻译 | NLLB-200 模型（断网也能用） |
| 🎤 语音输入 | 按住说话，自动识别并翻译 |
| 🔊 语音播报 | 点击喇叭听取翻译结果 |
| 📱 Android APK | 手机安装即可使用 |
| 🖥️ Web 版 | 浏览器打开即用 |

## 📱 安装 (Android)

1. 前往 [Releases](https://github.com/guocheng1378/translator-apk/releases) 下载最新 APK
2. 传输到手机并安装
3. 首次使用请配置百度翻译 API（见下方说明）

## 🖥️ 安装 (Web 版)

```bash
# 克隆仓库
git clone https://github.com/guocheng1378/translator-apk.git
cd translator-apk

# 安装依赖
pip install flask requests edge-tts

# 启动
python app.py

# 浏览器访问 http://localhost:5000
```

## ⚙️ 配置百度翻译 API

1. 前往 [百度翻译开放平台](https://fanyi-api.baidu.com/manage/developer) 注册
2. 创建应用，获取 **APP ID** 和 **密钥**
3. 在 APP 中点击右上角 ⚙️ 设置填入

> 免费额度：每月 **5万字符**，个人使用绰绰有余

## 🎤 语音输入

- 点击 🎤 按钮开始说话
- 说完后自动识别并翻译
- 需要浏览器支持 Web Speech API（Chrome / Edge 推荐）
- 识别语言：老挝语（lo-LA）或 中文（zh-CN）

## 🔊 语音播报

- 翻译完成后点击 🔊 按钮播放
- 中文使用微软晓晓音色
- 老挝语使用泰语音色（最接近）

## 📴 离线模式

首次使用离线翻译时，APP 会自动下载 NLLB-200 模型（约 2.4GB）：
- 下载完成后自动缓存到本地
- 之后断网即可使用
- 翻译质量不如在线 API，适合应急使用

## 🏗️ 从源码构建 APK

```bash
# 安装依赖
pip install buildozer cython kivy

# 构建
buildozer android debug

# APK 输出在 bin/ 目录
```

或直接 Fork 本仓库，GitHub Actions 会自动构建。

## 📁 项目结构

```
translator-apk/
├── main.py                  # 主程序
├── buildozer.spec           # Android 构建配置
├── .github/workflows/
│   └── build.yml            # GitHub Actions 自动构建
└── README.md
```

## 🔧 技术栈

- **后端**: Python + Flask
- **前端**: HTML/CSS/JavaScript
- **翻译引擎**: 百度翻译 API + NLLB-200 (Facebook)
- **语音合成**: edge-tts (微软)
- **语音识别**: Web Speech API (浏览器内置)
- **Android 打包**: Kivy + Buildozer

## 📄 License

MIT
