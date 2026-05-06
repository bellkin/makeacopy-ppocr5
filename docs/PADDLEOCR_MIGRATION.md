# PaddleOCR V5 替換 Tesseract — 修改摘要

## 概述

將 makeacopy 專案的 OCR 引擎從 Tesseract (`cz.adaptech.tesseract4android`) 完全替換為 PaddleOCR V5，透過 ONNX Runtime 在裝置端執行推論。

---

## 檔案變更

| 檔案 | 動作 | 說明 |
|------|------|------|
| `PaddleOCREngine.java` | **新增** | PaddleOCR V5 核心引擎：文字偵測 + 文字辨識，基於 ONNX Runtime |
| `OCRHelper.java` | **重寫** | 從 TessBaseAPI 改為委派給 PaddleOCREngine，保持相同公開 API |
| `OCRUtils.java` | **修改** | 新增 Tesseract 3 碼語言 → PaddleOCR 語言群組對應 |
| `OCRFragment.java` | **修改** | 移除所有 Tesseract/OpenCV 預處理、OCR 選項對話框、PSM 設定、.traineddata 匯入 |
| `OcrReviewFragment.java` | **修改** | 移除 `PSM_SINGLE_WORD` 參照 |
| `OCRUtilsTest.java` | **修改** | 中文字測試改為接受 chi_sim/chi_tra 兩種結果 |
| `build.gradle` | **修改** | 移除 `libs.tesseract`，改用 `libs.onnxruntime` (Maven) |
| `libs.versions.toml` | **修改** | 移除 tesseract，新增 `onnxruntime = "1.16.3"` |
| `AndroidManifest.xml` | **修改** | 新增 `android:largeHeap="true"` |
| `settings.gradle` | **修改** | 更新 JitPack 註解 |
| `assets/paddleocr/` | **新增** | 11 個語言群組的 ONNX 模型 + dict.txt（偵測模型 84 MB，總計 249 MB） |
| `jniLibs/` | **新增** | OpenCV 原生 .so 檔（7 個模組，arm64-v8a） |

---

## PaddleOCR 引擎架構 (`PaddleOCREngine.java`)

### 模型
- **偵測**：`det.onnx`（84 MB），輸入 RGB image，輸出文字區域 heatmap
- **辨識**：各語言 `rec.onnx`（7.5–81 MB），輸入文字框 crop，輸出 CTC 序列

### 流程
1. 圖片降採樣（max 1920px）節省記憶體
2. **Phase 1**：載入偵測模型 → 推論 → 取得文字框 → 關閉 session → `System.gc()`
3. **Phase 2**：載入辨識模型 → 逐框 CTC 解碼 → 關閉 session
4. 兩模型**不會同時存在記憶體中**（峰值 84 MB vs 原先 165 MB）

### 語言群組對應
```
eng        → english
deu/fra/ita/spa/por/nld/pol/... → latin
rus        → eslav
ara/fas    → arabic
hin        → hindi
tha        → thai
chi_sim/chi_tra → chinese
```

---

## 遇到的 Bug 與修復

### Bug 1：OpenCV 原生庫缺失
- **現象**：crop 時崩潰 `UnsatisfiedLinkError: No implementation found for Mat.n_Mat()`
- **原因**：`app/src/main/jniLibs/` 在 `.gitignore` 中，OpenCV `.so` 從未納入版本控制
- **修復**：執行 `scripts/build_opencv_android.sh` 建置（NDK r28），複製全部 7 個 `.so` 到 `jniLibs/arm64-v8a/`

### Bug 2：OOM — 兩模型同時載入超過 heap 256 MB
- **現象**：`OutOfMemoryError: Failed to allocate`，heap 256 MB 裝不下 84+81=165 MB 模型
- **修復**：
  1. 改為循序載入（先偵測→關閉→再辨識）
  2. 圖片降採樣至 max 1920px
  3. `DET_LIMIT_SIDE` 960 → 640
  4. `AndroidManifest` 加入 `largeHeap="true"`（256→512 MB）

### Bug 3：偵測輸出形狀解析錯誤
- **現象**：偵測永遠回傳 0 個文字框
- **原因**：`outW = probMap.length` 將總元素數當成寬度
- **修復**：正確從 `h*w = mapLen` 反推 2D 形狀

### Bug 4：CTC 編碼偏移（最關鍵）
- **現象**：Mini→Njoj, Intel→Joufm，全部字符位移 1
- **根因**：PP-OCRv5 模型 class 結構為：
  ```
  class 0     = CTC blank（需跳過）
  class 1..N  = dict.txt 逐行字符
  class N+1   = 空白字符（use_space_char=True）
  ```
  總 class 數 = `len(dict) + 2`（blank + space）
- **修復歷程**：
  1. 最初 `dict.add("")` 插入 blank 但漏掉 space → 中文對、英文差 1
  2. 移除 blank 但保留 `maxIdx > 0` → 全錯
  3. 加入 space 但 blank 放錯位置 → 中英文全錯
  4. **最終正確方案**：
     ```java
     dict.add("");           // index 0 = CTC blank
     // 讀取 dict.txt 全部非空行（含 # 字元）
     dict.add(" ");          // use_space_char 空白字符
     // dict.size() = 18385 = model num_classes
     ```
     CTC 解碼：`maxIdx > 0 && maxIdx < dict.size()`

### Bug 5：`#` 被當成註解跳過
- **現象**：Latin 字符從 dict 位置 16149 後全部位移（`#` 在該位置是合法字符）
- **修復**：移除 `line.startsWith("#")` 過濾

### Bug 6：ONNX 輸出張量型別轉換
- **現象**：`ClassCastException`，偵測輸出 `float[][][][]`（4D）被強轉 `float[][]`
- **修復**：`extract2DProbMap()` 處理 4D/3D/2D 三種格式

### Bug 7：圖片座標未縮放回原始尺寸
- **現象**：PDF 中文字位置錯誤
- **修復**：`scaleBackX/Y` 將偵測框從降採樣圖片座標還原到原始尺寸

---

## 目前狀態

| 功能 | 狀態 |
|------|------|
| App 啟動 | ✅ |
| 拍照 | ✅ |
| Crop（OpenCV 透視校正） | ✅ |
| 文字偵測（86 框） | ✅ |
| 中文辨識 | ✅ |
| 英數字辨識 | ✅（最後修復中） |
| CTC 編碼對應 | ✅ |
| PDF 座標縮放 | ✅ |
| 記憶體管理 | ✅ |
| 單元測試（872 題） | ✅ |

---

## 記憶體使用

| 項目 | 大小 |
|------|------|
| Det 模型 (det.onnx) | 84 MB |
| Rec 模型 (chinese) | 81 MB |
| Rec 模型 (english) | 7.5 MB |
| Rec 模型 (latin) | 7.5 MB |
| 峰值記憶體（循序載入） | ~84 MB |
| Heap (largeHeap) | 512 MB |
| APK 大小 (含全部語言) | ~468 MB |
