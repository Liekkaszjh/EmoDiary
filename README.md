# EmoDiary 整合包使用说明

本目录 `E:\EmoDiary` 已包含前后端、ASR/SER 推理代码和所需模型权重，可直接用于开发管理与 APK 打包。

## 一、目录结构

- `android-demo/`：Android 前端工程（Android Studio）
- `backend/`：FastAPI 后端工程
- `asr/src/asr_infer.py`：ASR 推理脚本
- `asr/models_cache/models/iic/`：ASR 模型缓存（SenseVoice + VAD）
- `data/CASIA/model_CASIA_CTMAM_EMODB_mfcc_CASIA_random80_20_seed2022_hop512.pth`：SER 权重
- `models.py`、`lct.py`、`process_CASIA.py`、`infer.py`：SER 训练/推理相关源码

## 二、后端启动

```powershell
cd E:\EmoDiary\backend
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

说明：
- 后端默认已指向整合包内模型路径：
  - ASR：`E:\EmoDiary\asr\src`
  - SER：`E:\EmoDiary\data\CASIA\model_CASIA_CTMAM_EMODB_mfcc_CASIA_random80_20_seed2022_hop512.pth`
- 可选环境变量：
  - `ASR_REPO_SRC`
  - `SER_MODEL_PATH`
  - `SER_DEVICE`（`cpu` / `auto` / `cuda:0`）

## 三、Android 运行与 APK 打包

1. 用 Android Studio 打开：`E:\EmoDiary\android-demo`
2. 同步 Gradle 后运行 `app` 模块
3. 打包 APK：Android Studio -> Build -> Build APK(s)

也可命令行安装调试包：

```powershell
cd E:\EmoDiary\android-demo
.\gradlew installDebug
```

### Record 页说明（端侧 SER）

- `record` 页面已改为端侧情绪识别主链路：
  - 录音（WAV 16k） -> 本地 MFCC 提取 -> 本地模型推理（`ser_model_seed2022.ptl`）
  - 不依赖后端 `/diaries/upload` 推理
- 当天记录会本地保存并在页面展示（情绪标签 + 可播放音频 + 转写文本占位）

## 四、后端地址配置（非常重要）

当前前端后端地址在：
- `android-demo/app/build.gradle.kts`
- 字段：`BACKEND_BASE_URL`

### 模拟器
使用：`http://10.0.2.2:8000/`

### 真机
使用你电脑局域网 IP，例如：`http://192.168.1.102:8000/`

修改后必须重新安装 APK 才会生效。

## 五、常见问题

1. 真机提示连接失败
- 检查 `BACKEND_BASE_URL` 是否为电脑局域网 IP
- 确认电脑与手机在同一 Wi-Fi
- 确认后端在运行且监听 `0.0.0.0:8000`
- 必要时放行防火墙 8000 端口

2. 录音后没有声音/总是 Neutral
- 模拟器中开启 `Extended Controls -> Microphone`
- 录音页可用“播放最近录音”检查录音是否正常

3. 首次启动较慢
- ASR/SER 首次加载模型会有准备时间，属于正常现象
