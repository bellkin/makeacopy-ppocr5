# PaddleOCR V5 環境設定

## 1. 下載 PaddleOCR V5 ONNX 模型

模型來源：[monkt/paddleocr-onnx on HuggingFace](https://huggingface.co/monkt/paddleocr-onnx)

```bash
# 安裝 huggingface-hub
pip install huggingface-hub

# 下載全部模型到專案 assets 目錄
cd makeacopy-ppocr5
huggingface-cli download monkt/paddleocr-onnx \
  --local-dir app/src/main/assets/paddleocr/ \
  --include "detection/v5/*" "languages/*/rec.onnx" "languages/*/dict.txt"
```

或手動從 HuggingFace 下載：
```
https://huggingface.co/monkt/paddleocr-onnx/tree/main
```

下載後的目錄結構：
```
app/src/main/assets/paddleocr/
├── detection/v5/det.onnx          (84 MB, 文字偵測)
└── languages/
    ├── arabic/dict.txt rec.onnx
    ├── chinese/dict.txt rec.onnx   (81 MB)
    ├── english/dict.txt rec.onnx   (7.5 MB)
    ├── eslav/dict.txt rec.onnx
    ├── greek/dict.txt rec.onnx
    ├── hindi/dict.txt rec.onnx
    ├── korean/dict.txt rec.onnx
    ├── latin/dict.txt rec.onnx     (7.5 MB, 32 種拉丁語系)
    ├── tamil/dict.txt rec.onnx
    ├── telugu/dict.txt rec.onnx
    └── thai/dict.txt rec.onnx
```

> 總計約 249 MB，已加入 `.gitignore`，不納入版本控制。

## 2. 建置 OpenCV 原生庫

OpenCV 為 git submodule（`external/opencv`），需先初始化：

```bash
git submodule update --init --depth 1 external/opencv
```

然後執行建置腳本（僅 arm64-v8a，約 10-15 分鐘）：

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.0.13004108
ABIS=arm64-v8a bash scripts/build_opencv_android.sh
```

產出位於 `/tmp/opencv-build/lib/arm64-v8a/`，包含 7 個 `.so` 檔：

```
libopencv_core.so
libopencv_imgcodecs.so
libopencv_imgproc.so
libopencv_java4.so
libopencv_photo.so
libopencv_video.so
libopencv_videoio.so
```

複製到 jniLibs：

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp /tmp/opencv-build/lib/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

> `jniLibs/` 已加入 `.gitignore`，不納入版本控制。

## 3. 建置 APK

```bash
./gradlew assembleDebug
```

產出：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## 系統需求

- Android NDK 28.0.13004108（用於 OpenCV 建置）
- Android SDK platform 36
- ONNX Runtime 1.16.3（透過 Maven 自動下載）
- Java 21
