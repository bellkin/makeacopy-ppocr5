/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import ai.onnxruntime.*;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PaddleOCREngine implements AutoCloseable {

  private static final String TAG = "PaddleOCREngine";

  private static final String DET_ASSET_PATH = "paddleocr/detection/v5/det.onnx";
  private static final String REC_ASSET_DIR = "paddleocr/languages";

  private static final int DET_LIMIT_SIDE = 640;
  private static final float DET_THRESH = 0.1f;
  private static final float UNCLIP_RATIO = 1.5f;
  private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
  private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};

  private static final int REC_IMG_H = 48;
  private static final float REC_MEAN = 0.5f;
  private static final float REC_STD = 0.5f;

  private final OrtEnvironment env;
  private final Context context;
  private volatile boolean closed;

  // Cached model file paths (models stay on disk, loaded on demand)
  private File detModelFile;
  private String langGroup;
  private File recModelFile;
  private List<String> charDict;

  public PaddleOCREngine(Context context) throws Exception {
    this.context = context.getApplicationContext();
    breadcrumb(context, "ctor_start");
    this.env = OrtEnvironment.getEnvironment();
    breadcrumb(context, "env_ok");

    this.detModelFile = copyAssetToCache(context, DET_ASSET_PATH);
    breadcrumb(context, "det_cached_" + detModelFile.length());
    Log.i(
        TAG,
        "Detection model cached: "
            + detModelFile.getAbsolutePath()
            + " ("
            + detModelFile.length()
            + " bytes)");
  }

  public void setLanguageGroup(String langGroup) throws Exception {
    if (langGroup == null) return;
    if (langGroup.equals(this.langGroup) && this.recModelFile != null) return;

    this.langGroup = langGroup;
    String recAssetPath = REC_ASSET_DIR + "/" + langGroup + "/rec.onnx";
    breadcrumb(context, "rec_cache_start_" + langGroup);
    this.recModelFile = copyAssetToCache(context, recAssetPath);
    breadcrumb(context, "rec_cached_" + recModelFile.length());

    String dictAssetPath = REC_ASSET_DIR + "/" + langGroup + "/dict.txt";
    this.charDict = loadCharDict(context, dictAssetPath);
    Log.i(
        TAG,
        "Recognition model cached: lang="
            + langGroup
            + " ("
            + recModelFile.length()
            + " bytes), dict="
            + (charDict != null ? charDict.size() : 0));
  }

  public OcrResult detectAndRecognize(Bitmap bitmap) {
    if (closed) return emptyResult();
    if (bitmap == null || bitmap.isRecycled()) return emptyResult();
    if (recModelFile == null || charDict == null) {
      Log.e(TAG, "Language group not set");
      return emptyResult();
    }

    Bitmap argb = null;
    OrtSession detSession = null;
    OrtSession recSession = null;
    String detInputName = null;
    String detOutputName = null;
    String recInputName = null;
    String recOutputName = null;

    try {
      // Downscale large images
      int maxDim = Math.max(bitmap.getWidth(), bitmap.getHeight());
      float scaleBackX = 1f;
      float scaleBackY = 1f;
      if (maxDim > 1920) {
        float ds = 1920f / maxDim;
        int dw = Math.max(32, (int) (bitmap.getWidth() * ds));
        int dh = Math.max(32, (int) (bitmap.getHeight() * ds));
        scaleBackX = (float) bitmap.getWidth() / dw;
        scaleBackY = (float) bitmap.getHeight() / dh;
        argb = Bitmap.createScaledBitmap(bitmap, dw, dh, true);
        Log.d(
            TAG, "Downscaled: " + dw + "x" + dh + ", scaleBack: " + scaleBackX + "x" + scaleBackY);
      } else {
        argb = ensureArgb8888(bitmap);
      }

      // --- Phase 1: Detection (load → run → close) ---
      long t0 = System.nanoTime();
      breadcrumb(context, "det_load");
      detSession = createSession(env, detModelFile.getAbsolutePath());
      detInputName = firstOrThrow(detSession.getInputNames(), "det input");
      detOutputName = firstOrThrow(detSession.getOutputNames(), "det output");
      Log.i(TAG, "Detection session created: input=" + detInputName + ", output=" + detOutputName);

      List<RectF> boxes = detect(argb, detSession, detInputName, detOutputName);
      breadcrumb(context, "det_done_" + boxes.size());
      Log.i(
          TAG,
          "Detection: "
              + boxes.size()
              + " boxes, "
              + ((System.nanoTime() - t0) / 1_000_000L)
              + "ms");

      detSession.close();
      detSession = null;
      System.gc();

      if (boxes.isEmpty()) return emptyResult();

      List<RectF> sortedBoxes = sortBoxes(boxes);
      if (sortedBoxes.size() > 64) sortedBoxes = sortedBoxes.subList(0, 64);

      // --- Phase 2: Recognition (load → run per box → close) ---
      long t1 = System.nanoTime();
      breadcrumb(context, "rec_load");
      recSession = createSession(env, recModelFile.getAbsolutePath());
      recInputName = firstOrThrow(recSession.getInputNames(), "rec input");
      recOutputName = firstOrThrow(recSession.getOutputNames(), "rec output");
      Log.i(
          TAG, "Recognition session created: input=" + recInputName + ", output=" + recOutputName);

      List<RecognizedWord> words = new ArrayList<>();
      StringBuilder fullText = new StringBuilder();
      float totalConf = 0f;
      int confCount = 0;

      for (RectF box : sortedBoxes) {
        if (closed) break;
        if (box.width() < 8 || box.height() < 8) continue;
        try {
          RecognitionResult recResult =
              recognize(argb, box, recSession, recInputName, recOutputName);
          if (recResult.text != null && !recResult.text.isEmpty()) {
            RectF scaledBox =
                new RectF(
                    box.left * scaleBackX, box.top * scaleBackY,
                    box.right * scaleBackX, box.bottom * scaleBackY);
            RecognizedWord word =
                new RecognizedWord(recResult.text, scaledBox, recResult.confidence);
            words.add(word);
            if (fullText.length() > 0) fullText.append(" ");
            fullText.append(recResult.text);
            totalConf += recResult.confidence;
            confCount++;
          }
        } catch (Exception e) {
          Log.w(TAG, "Recognition failed for box", e);
        }
      }
      breadcrumb(context, "rec_done_" + words.size());
      Log.i(
          TAG,
          "Recognition: "
              + words.size()
              + " words, "
              + ((System.nanoTime() - t1) / 1_000_000L)
              + "ms");

      float meanConf = confCount > 0 ? totalConf / confCount : 0f;
      return new OcrResult(fullText.toString().trim(), meanConf, words);
    } catch (Exception e) {
      Log.e(TAG, "detectAndRecognize failed", e);
      return emptyResult();
    } finally {
      if (detSession != null) {
        try {
          detSession.close();
        } catch (Throwable t) {
        }
      }
      if (recSession != null) {
        try {
          recSession.close();
        } catch (Throwable t) {
        }
      }
      // Free the scaled bitmap if we created one
      if (argb != null && argb != bitmap && !argb.isRecycled()) {
        argb.recycle();
      }
    }
  }

  // --- Detection (takes session as parameter) ---

  private List<RectF> detect(Bitmap argb, OrtSession session, String inputName, String outputName)
      throws Exception {
    int w = argb.getWidth();
    int h = argb.getHeight();

    float ratio = 1f;
    int newW = w;
    int newH = h;
    if (Math.max(w, h) > DET_LIMIT_SIDE) {
      ratio = (float) DET_LIMIT_SIDE / Math.max(w, h);
      newW = Math.max(32, ((int) (w * ratio) / 32) * 32);
      newH = Math.max(32, ((int) (h * ratio) / 32) * 32);
    }

    float[] input = new float[3 * newH * newW];
    fillDetectionInput(argb, input, w, h, newW, newH, ratio);

    long[] inputShape = {1, 3, newH, newW};
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape);
        OrtSession.Result results = session.run(Collections.singletonMap(inputName, tensor))) {

      float[] probMap = extract2DProbMap(results, outputName);
      if (probMap == null || probMap.length == 0) {
        return Collections.emptyList();
      }

      // probMap is [h*w] flat; shape matches detection output (typically same as input or close)
      int outH = newH;
      int outW = newW;
      int mapLen = probMap.length;
      // If flattened length doesn't match input size, try to infer from common ratios
      if (mapLen != newH * newW) {
        // Try to find a factor that gives a plausible aspect ratio
        for (int d = 4; d >= 1; d--) {
          int tryW = newW / d;
          int tryH = newH / d;
          if (tryW * tryH == mapLen) {
            outW = tryW;
            outH = tryH;
            break;
          }
        }
        if (mapLen != outW * outH) {
          // Fallback: assume square or use mapLen as one dimension
          int sqrt = (int) Math.sqrt(mapLen);
          outW = sqrt;
          outH = mapLen / outW;
        }
      }
      Log.d(
          TAG,
          "det output shape: "
              + outW
              + "x"
              + outH
              + " (input was "
              + newW
              + "x"
              + newH
              + ", mapLen="
              + mapLen
              + ")");

      boolean[][] mask = new boolean[outH][outW];
      for (int y = 0; y < outH; y++) {
        for (int x = 0; x < outW; x++) {
          int idx = y * outW + x;
          mask[y][x] = idx < probMap.length && probMap[idx] > DET_THRESH;
        }
      }

      List<RectF> boxes = extractBoxesFromMask(mask, outW, outH);
      return rescaleBoxes(boxes, outW, outH, w, h);
    }
  }

  private void fillDetectionInput(
      Bitmap argb, float[] input, int w, int h, int newW, int newH, float ratio) {
    int[] pixels = new int[w * h];
    argb.getPixels(pixels, 0, w, 0, 0, w, h);
    for (int y = 0; y < newH; y++) {
      for (int x = 0; x < newW; x++) {
        float srcX = Math.min(x / ratio, w - 1);
        float srcY = Math.min(y / ratio, h - 1);
        int sx = (int) srcX;
        int sy = (int) srcY;
        float fx = srcX - sx;
        float fy = srcY - sy;
        int sx1 = Math.min(sx + 1, w - 1);
        int sy1 = Math.min(sy + 1, h - 1);

        int p00 = pixels[sy * w + sx];
        int p10 = pixels[sy * w + sx1];
        int p01 = pixels[sy1 * w + sx];
        int p11 = pixels[sy1 * w + sx1];

        float r =
            bilinear(
                    ((p00 >> 16) & 0xFF),
                    ((p10 >> 16) & 0xFF),
                    ((p01 >> 16) & 0xFF),
                    ((p11 >> 16) & 0xFF),
                    fx,
                    fy)
                / 255f;
        float g =
            bilinear(
                    ((p00 >> 8) & 0xFF),
                    ((p10 >> 8) & 0xFF),
                    ((p01 >> 8) & 0xFF),
                    ((p11 >> 8) & 0xFF),
                    fx,
                    fy)
                / 255f;
        float b = bilinear((p00 & 0xFF), (p10 & 0xFF), (p01 & 0xFF), (p11 & 0xFF), fx, fy) / 255f;

        int baseC = y * newW + x;
        input[baseC] = (r - DET_MEAN[0]) / DET_STD[0];
        input[newH * newW + baseC] = (g - DET_MEAN[1]) / DET_STD[1];
        input[2 * newH * newW + baseC] = (b - DET_MEAN[2]) / DET_STD[2];
      }
    }
  }

  // --- Recognition (takes session as parameter) ---

  private RecognitionResult recognize(
      Bitmap argb, RectF box, OrtSession session, String inputName, String outputName) {
    int left = Math.max(0, (int) box.left);
    int top = Math.max(0, (int) box.top);
    int right = Math.min(argb.getWidth(), (int) Math.ceil(box.right));
    int bottom = Math.min(argb.getHeight(), (int) Math.ceil(box.bottom));
    int bw = right - left;
    int bh = bottom - top;
    if (bw < 4 || bh < 4) return RecognitionResult.empty();

    Bitmap crop = null;
    try {
      crop = Bitmap.createBitmap(argb, left, top, bw, bh);
      float[] input = preprocessRecCrop(crop);
      int recW = input.length / (3 * REC_IMG_H);
      long[] inputShape = {1, 3, REC_IMG_H, recW};

      try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape);
          OrtSession.Result results = session.run(Collections.singletonMap(inputName, tensor))) {

        float[][] ctcOutput = extract2DCtcOutput(results, outputName);
        if (ctcOutput == null) return RecognitionResult.empty();
        return ctcDecode(ctcOutput, charDict);
      }
    } catch (Exception e) {
      Log.w(TAG, "recognize error", e);
      return RecognitionResult.empty();
    } finally {
      if (crop != null && !crop.isRecycled()) crop.recycle();
    }
  }

  private float[] preprocessRecCrop(Bitmap crop) {
    int cw = crop.getWidth();
    int ch = crop.getHeight();
    int newW = Math.max(8, (int) (REC_IMG_H * (float) cw / ch));
    int[] srcPixels = new int[cw * ch];
    crop.getPixels(srcPixels, 0, cw, 0, 0, cw, ch);

    float[] result = new float[3 * REC_IMG_H * newW];
    float sx = (float) (cw - 1) / Math.max(1, newW - 1);
    float sy = (float) (ch - 1) / Math.max(1, REC_IMG_H - 1);

    for (int dy = 0; dy < REC_IMG_H; dy++) {
      for (int dx = 0; dx < newW; dx++) {
        int ix = Math.min((int) (dx * sx), cw - 1);
        int iy = Math.min((int) (dy * sy), ch - 1);
        int pixel = srcPixels[iy * cw + ix];
        float gray = rgbToGray(pixel) / 255f;
        float val = (gray - REC_MEAN) / REC_STD;
        int base = dy * newW + dx;
        result[base] = val;
        result[REC_IMG_H * newW + base] = val;
        result[2 * REC_IMG_H * newW + base] = val;
      }
    }
    return result;
  }

  // --- Tensor extraction ---

  private static float[] extract2DProbMap(OrtSession.Result results, String outputName)
      throws OrtException {
    OnnxValue val = results.get(outputName).orElse(null);
    if (val == null) return null;
    Object raw = val.getValue();
    if (raw instanceof float[][][][]) {
      float[][][][] t = (float[][][][]) raw;
      if (t.length == 0 || t[0].length == 0) return null;
      float[][] ch = t[0][0];
      int h = ch.length, w = ch[0].length;
      float[] flat = new float[h * w];
      for (int y = 0; y < h; y++) System.arraycopy(ch[y], 0, flat, y * w, w);
      return flat;
    }
    if (raw instanceof float[][][]) {
      float[][][] t = (float[][][]) raw;
      float[][] map = t[0];
      int h = map.length, w = map[0].length;
      float[] flat = new float[h * w];
      for (int y = 0; y < h; y++) System.arraycopy(map[y], 0, flat, y * w, w);
      return flat;
    }
    if (raw instanceof float[][]) {
      float[][] t = (float[][]) raw;
      int h = t.length, w = t[0].length;
      float[] flat = new float[h * w];
      for (int y = 0; y < h; y++) System.arraycopy(t[y], 0, flat, y * w, w);
      return flat;
    }
    Log.e(TAG, "Unexpected det output: " + raw.getClass().getName());
    return null;
  }

  private static float[][] extract2DCtcOutput(OrtSession.Result results, String outputName)
      throws OrtException {
    OnnxValue val = results.get(outputName).orElse(null);
    if (val == null) return null;
    Object raw = val.getValue();
    if (raw instanceof float[][][]) {
      float[][][] t = (float[][][]) raw;
      return t.length > 0 ? t[0] : null;
    }
    if (raw instanceof float[][]) return (float[][]) raw;
    Log.e(TAG, "Unexpected CTC output: " + raw.getClass().getName());
    return null;
  }

  // --- CTC Decode ---

  private static RecognitionResult ctcDecode(float[][] output, List<String> dict) {
    if (output == null || output.length == 0 || dict == null || dict.isEmpty()) {
      return RecognitionResult.empty();
    }

    int dictSize = dict.size();
    StringBuilder sb = new StringBuilder();
    float totalConf = 0f;
    int steps = 0;
    String lastChar = null;
    for (float[] step : output) {
      int maxIdx = 0;
      float maxVal = step[0];
      for (int i = 0; i < step.length; i++) {
        if (step[i] > maxVal) {
          maxVal = step[i];
          maxIdx = i;
        }
      }
      if (maxIdx > 0 && maxIdx < dictSize) {
        String ch = dict.get(maxIdx);
        if (ch != null && !ch.isEmpty() && !ch.equals(lastChar)) {
          sb.append(ch);
          lastChar = ch;
        }
        totalConf += maxVal;
        steps++;
      }
    }
    if (sb.length() == 0) return RecognitionResult.empty();
    float conf = steps > 0 ? (totalConf / steps) * 100f : 0f;
    return new RecognitionResult(sb.toString(), Math.min(100f, conf));
  }

  // --- Box helpers ---

  private static List<RectF> extractBoxesFromMask(boolean[][] mask, int w, int h) {
    boolean[][] visited = new boolean[h][w];
    List<RectF> boxes = new ArrayList<>();
    int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (!mask[y][x] || visited[y][x]) continue;
        int minX = w, minY = h, maxX = 0, maxY = 0;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {x, y});
        while (!stack.isEmpty()) {
          int[] p = stack.pop();
          int px = p[0], py = p[1];
          if (px < 0 || px >= w || py < 0 || py >= h) continue;
          if (!mask[py][px] || visited[py][px]) continue;
          visited[py][px] = true;
          if (px < minX) minX = px;
          if (py < minY) minY = py;
          if (px > maxX) maxX = px;
          if (py > maxY) maxY = py;
          for (int[] d : dirs) stack.push(new int[] {px + d[0], py + d[1]});
        }
        RectF box = new RectF(minX, minY, maxX + 1, maxY + 1);
        if (box.width() > 4 && box.height() > 4) {
          box = expandBox(box, UNCLIP_RATIO, w, h);
          if (box.width() > 6 && box.height() > 6) boxes.add(box);
        }
      }
    }
    return boxes;
  }

  private static RectF expandBox(RectF box, float ratio, int maxW, int maxH) {
    float cx = (box.left + box.right) / 2f;
    float cy = (box.top + box.bottom) / 2f;
    float hw = box.width() / 2f * ratio;
    float hh = box.height() / 2f * ratio;
    return new RectF(
        Math.max(0, cx - hw), Math.max(0, cy - hh),
        Math.min(maxW, cx + hw), Math.min(maxH, cy + hh));
  }

  // --- Utilities ---

  private static List<RectF> sortBoxes(List<RectF> boxes) {
    if (boxes.size() <= 1) return new ArrayList<>(boxes);
    List<RectF> sorted = new ArrayList<>(boxes);
    sorted.sort(
        (a, b) -> {
          float lineThresh = Math.min(a.height(), b.height()) * 0.4f;
          if (Math.abs(a.top - b.top) > lineThresh) return Float.compare(a.top, b.top);
          return Float.compare(a.left, b.left);
        });
    return sorted;
  }

  private static List<RectF> rescaleBoxes(
      List<RectF> boxes, int detW, int detH, int origW, int origH) {
    float scaleX = (float) origW / detW;
    float scaleY = (float) origH / detH;
    List<RectF> result = new ArrayList<>();
    for (RectF b : boxes) {
      result.add(
          new RectF(
              b.left * scaleX, b.top * scaleY,
              b.right * scaleX, b.bottom * scaleY));
    }
    return result;
  }

  private static float bilinear(float v00, float v10, float v01, float v11, float fx, float fy) {
    return v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) + v01 * (1 - fx) * fy + v11 * fx * fy;
  }

  private static float rgbToGray(int pixel) {
    return 0.299f * ((pixel >> 16) & 0xFF)
        + 0.587f * ((pixel >> 8) & 0xFF)
        + 0.114f * (pixel & 0xFF);
  }

  private static Bitmap ensureArgb8888(Bitmap bitmap) {
    if (bitmap.getConfig() == Bitmap.Config.ARGB_8888) return bitmap;
    Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    return copy != null ? copy : bitmap;
  }

  // --- Model file management ---

  private static File copyAssetToCache(Context context, String assetPath) throws IOException {
    AssetManager am = context.getAssets();
    String baseName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
    long versionCode;
    try {
      versionCode =
          context
              .getPackageManager()
              .getPackageInfo(context.getPackageName(), 0)
              .getLongVersionCode();
    } catch (Exception e) {
      versionCode = -1L;
    }
    String versionedName = "pdl_" + versionCode + "_" + baseName;
    File outFile = new File(context.getCacheDir(), versionedName);
    if (!outFile.exists() || outFile.length() < 1024) {
      File tmpFile = new File(context.getCacheDir(), versionedName + ".tmp");
      try (InputStream is = am.open(assetPath);
          FileOutputStream fos = new FileOutputStream(tmpFile)) {
        byte[] buf = new byte[256 * 1024];
        int len;
        while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
      }
      if (!tmpFile.renameTo(outFile)) {
        if (outFile.exists()) outFile.delete();
        if (!tmpFile.renameTo(outFile)) {
          throw new IOException("Failed to rename " + tmpFile);
        }
      }
    }
    return outFile;
  }

  private static List<String> loadCharDict(Context context, String assetPath) {
    List<String> dict = new ArrayList<>();
    dict.add(""); // class 0 = CTC blank
    try (InputStream is = context.getAssets().open(assetPath);
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty()) dict.add(line);
      }
    } catch (IOException e) {
      Log.w(TAG, "Failed to load dict: " + assetPath, e);
      return null;
    }
    dict.add(" "); // use_space_char=True
    return dict;
  }

  private static OrtSession createSession(OrtEnvironment env, String modelPath) throws Exception {
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    for (int attempt = 0; attempt < 3; attempt++) {
      try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setInterOpNumThreads(threads);
        opts.setIntraOpNumThreads(threads);
        if (attempt == 2) {
          try {
            opts.addXnnpack(Collections.emptyMap());
          } catch (Throwable t) {
          }
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
              opts.addNnapi();
            } catch (Throwable t) {
            }
          }
        }
        return env.createSession(modelPath, opts);
      } catch (Exception e) {
        if (attempt == 2) throw e;
      }
    }
    throw new IllegalStateException("Unreachable");
  }

  private static String firstOrThrow(Set<String> names, String label) {
    if (names == null || names.isEmpty())
      throw new IllegalStateException("No " + label + " names in model");
    return names.iterator().next();
  }

  // --- Breadcrumb ---

  public static void breadcrumb(Context context, String step) {
    try {
      java.io.File dir = new java.io.File(context.getCacheDir(), "pdl_breadcrumbs");
      dir.mkdirs();
      new java.io.File(dir, "step_" + System.currentTimeMillis() + "_" + step).createNewFile();
    } catch (Throwable ignore) {
    }
  }

  // --- Lifecycle ---

  @Override
  public void close() {
    closed = true;
  }

  // --- Result types ---

  public static class OcrResult {
    public final String text;
    public final float meanConfidence;
    public final List<RecognizedWord> words;

    OcrResult(String text, float meanConfidence, List<RecognizedWord> words) {
      this.text = text != null ? text : "";
      this.meanConfidence = meanConfidence;
      this.words = words != null ? words : new ArrayList<>();
    }
  }

  private static OcrResult emptyResult() {
    return new OcrResult("", 0f, new ArrayList<>());
  }

  private static class RecognitionResult {
    final String text;
    final float confidence;

    RecognitionResult(String text, float confidence) {
      this.text = text;
      this.confidence = confidence;
    }

    static RecognitionResult empty() {
      return new RecognitionResult("", 0f);
    }
  }
}
