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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.layout.DocumentLayoutAnalyzer;
import de.schliweb.makeacopy.utils.layout.DocumentRegion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;

public class OCRHelper {
  private static final String TAG = "OCRHelper";
  private static final String DEFAULT_LANGUAGE = "eng";

  private final Context context;
  private PaddleOCREngine engine;
  private String language = DEFAULT_LANGUAGE;
  private boolean isInitialized = false;
  @Getter private int pageSegMode = 0;
  private boolean useBestModelSettings = false;

  public OCRHelper(Context context) {
    this.context = context.getApplicationContext();
  }

  public boolean initTesseract() {
    PaddleOCREngine.breadcrumb(context, "ocrh_init_start");
    if (isInitialized) return true;
    try {
      if (engine != null) {
        try {
          engine.close();
        } catch (Throwable ignore) {
        }
      }
      PaddleOCREngine.breadcrumb(context, "ocrh_creating_engine");
      engine = new PaddleOCREngine(context);
      PaddleOCREngine.breadcrumb(context, "ocrh_engine_created");
      String langGroup = OCRUtils.mapTesseractToPaddleGroup(language);
      engine.setLanguageGroup(langGroup);
      isInitialized = true;
      PaddleOCREngine.breadcrumb(context, "ocrh_init_ok_" + langGroup);
      Log.i(TAG, "PaddleOCR initialized: lang=" + language + ", group=" + langGroup);
      return true;
    } catch (Exception e) {
      PaddleOCREngine.breadcrumb(context, "ocrh_init_failed_" + e.getClass().getSimpleName());
      Log.e(TAG, "PaddleOCR initialization failed", e);
      return false;
    }
  }

  public void shutdown() {
    if (engine != null) {
      try {
        engine.close();
      } catch (Throwable ignore) {
      }
      engine = null;
    }
    isInitialized = false;
  }

  public boolean isTesseractInitialized() {
    return isInitialized;
  }

  public void setPageSegMode(int mode) {
    this.pageSegMode = mode;
  }

  public void setReinitPerRun(boolean enable) {}

  public void setUseBestModelSettings(boolean enable) {
    this.useBestModelSettings = enable;
  }

  public boolean isUsingBestModelSettings() {
    return useBestModelSettings;
  }

  public void setLanguage(String language) throws IOException {
    if (language == null || language.isEmpty()) language = DEFAULT_LANGUAGE;
    if (language.equals(this.language) && isInitialized) return;

    this.language = language;
    if (isInitialized) {
      shutdown();
      initTesseract();
    }
  }

  public void applyDefaultsForLanguage(String langSpec) {}

  public String[] getAvailableLanguages() {
    try {
      LinkedHashSet<String> langs = new LinkedHashSet<>();

      String[] assetFiles = context.getAssets().list("paddleocr/languages");
      if (assetFiles != null) {
        for (String f : assetFiles) {
          if (!f.contains(".")) {
            langs.addAll(OCRUtils.mapPaddleGroupToTesseract(f));
          }
        }
      }
      if (langs.isEmpty()) {
        return OCRUtils.getLanguages();
      }
      return langs.toArray(new String[0]);
    } catch (IOException e) {
      Log.e(TAG, "Error listing languages", e);
      return OCRUtils.getLanguages();
    }
  }

  public boolean isLanguageAvailable(String lang) {
    try {
      String group = OCRUtils.mapTesseractToPaddleGroup(lang);
      String[] assetFiles = context.getAssets().list("paddleocr/languages");
      if (assetFiles != null) {
        for (String f : assetFiles) {
          if (f.equals(group)) return true;
        }
      }
    } catch (IOException e) {
      Log.e(TAG, "Error checking language availability", e);
    }
    return false;
  }

  private PaddleOCREngine.OcrResult runOcrWithWords(Bitmap bitmap) {
    if (bitmap == null || bitmap.isRecycled()) {
      return new PaddleOCREngine.OcrResult("", 0f, new ArrayList<>());
    }
    try {
      if (!isInitialized) {
        initTesseract();
      }
      if (!isInitialized) {
        return new PaddleOCREngine.OcrResult("", 0f, new ArrayList<>());
      }
      return engine.detectAndRecognize(bitmap);
    } catch (Exception e) {
      Log.e(TAG, "Error performing OCR", e);
      return new PaddleOCREngine.OcrResult("", 0f, new ArrayList<>());
    }
  }

  public OcrResultWords runOcrWithRetry(Bitmap bitmap) {
    PaddleOCREngine.OcrResult result = runOcrWithWords(bitmap);
    if (result.words.isEmpty() && (result.text == null || result.text.trim().isEmpty())) {
      return new OcrResultWords("", null, new ArrayList<>());
    }

    List<RecognizedWord> words = result.words != null ? result.words : new ArrayList<>();
    int meanConfInt = Math.round(result.meanConfidence);
    return new OcrResultWords(result.text, meanConfInt, words);
  }

  public Integer getMeanConfidenceSafe() {
    return null;
  }

  public static class OcrResult {
    public final String text;
    public final Integer meanConfidence;

    public OcrResult(String text, Integer meanConfidence) {
      this.text = text != null ? text : "";
      this.meanConfidence = meanConfidence;
    }
  }

  public static class OcrResultWords extends OcrResult {
    public final List<RecognizedWord> words;

    public OcrResultWords(String text, Integer meanConfidence, List<RecognizedWord> words) {
      super(text, meanConfidence);
      this.words = (words != null) ? words : new ArrayList<>();
    }
  }

  public static class OcrResultWithLayout extends OcrResult {
    public final List<RegionOcrResult> regionResults;
    public final DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis;

    public OcrResultWithLayout(
        String text,
        Integer meanConfidence,
        List<RegionOcrResult> regionResults,
        DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis) {
      super(text, meanConfidence);
      this.regionResults = (regionResults != null) ? regionResults : new ArrayList<>();
      this.layoutAnalysis = layoutAnalysis;
    }
  }

  public record RegionOcrResult(DocumentRegion region, OcrResultWords ocrResult) {}

  public OcrResultWithLayout runOcrWithLayout(Bitmap bitmap) {
    if (bitmap == null || bitmap.isRecycled()) {
      return new OcrResultWithLayout("", null, new ArrayList<>(), null);
    }
    if (!FeatureFlags.isLayoutAnalysisEnabled()) {
      OcrResultWords standardResult = runOcrWithRetry(bitmap);
      return new OcrResultWithLayout(
          standardResult != null ? standardResult.text : "",
          standardResult != null ? standardResult.meanConfidence : null,
          new ArrayList<>(),
          null);
    }

    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    analyzer.setLanguage(language);
    DocumentLayoutAnalyzer.AnalysisResult layoutAnalysis = analyzer.analyzeWithMetadata(bitmap);

    List<DocumentRegion> regions = layoutAnalysis.regions();
    List<RegionOcrResult> regionResults = new ArrayList<>();
    StringBuilder fullText = new StringBuilder();
    int totalConfidence = 0;
    int confidenceCount = 0;

    for (DocumentRegion region : regions) {
      if (region.getType() == DocumentRegion.Type.FIGURE) continue;

      Bitmap regionBitmap = extractRegionBitmap(bitmap, region.getBounds());
      if (regionBitmap == null) continue;

      try {
        OcrResultWords result = runOcrWithRetry(regionBitmap);
        if (result != null && !result.text.isEmpty()) {
          List<RecognizedWord> adjustedWords =
              adjustWordCoordinates(result.words, region.getBounds());
          OcrResultWords adjustedResult =
              new OcrResultWords(result.text, result.meanConfidence, adjustedWords);
          regionResults.add(new RegionOcrResult(region, adjustedResult));

          if (fullText.length() > 0) fullText.append("\n\n");
          fullText.append(result.text);

          if (result.meanConfidence != null) {
            totalConfidence += result.meanConfidence;
            confidenceCount++;
          }
        }
      } finally {
        if (regionBitmap != bitmap && !regionBitmap.isRecycled()) regionBitmap.recycle();
      }
    }

    Integer avgConfidence = confidenceCount > 0 ? totalConfidence / confidenceCount : null;
    return new OcrResultWithLayout(
        fullText.toString(), avgConfidence, regionResults, layoutAnalysis);
  }

  private Bitmap extractRegionBitmap(Bitmap source, Rect bounds) {
    if (source == null || bounds == null) return null;
    try {
      int left = Math.max(0, bounds.left);
      int top = Math.max(0, bounds.top);
      int right = Math.min(source.getWidth(), bounds.right);
      int bottom = Math.min(source.getHeight(), bounds.bottom);
      int width = right - left;
      int height = bottom - top;
      if (width <= 0 || height <= 0) return null;
      return Bitmap.createBitmap(source, left, top, width, height);
    } catch (Exception e) {
      Log.e(TAG, "extractRegionBitmap: error", e);
      return null;
    }
  }

  private List<RecognizedWord> adjustWordCoordinates(
      List<RecognizedWord> words, Rect regionBounds) {
    if (words == null || regionBounds == null) return words;
    List<RecognizedWord> adjusted = new ArrayList<>();
    for (RecognizedWord word : words) {
      RectF originalBox = word.getBoundingBox();
      RectF adjustedBox =
          new RectF(
              originalBox.left + regionBounds.left,
              originalBox.top + regionBounds.top,
              originalBox.right + regionBounds.left,
              originalBox.bottom + regionBounds.top);
      adjusted.add(new RecognizedWord(word.getText(), adjustedBox, word.getConfidence()));
    }
    return adjusted;
  }

  public boolean hasComplexLayout(Bitmap bitmap) {
    if (!FeatureFlags.isLayoutAnalysisEnabled()) return false;
    if (bitmap == null || bitmap.isRecycled()) return false;
    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    return analyzer.hasComplexLayout(bitmap);
  }

  public int getDocumentColumnCount(Bitmap bitmap) {
    if (!FeatureFlags.isLayoutAnalysisEnabled()) return 1;
    if (bitmap == null || bitmap.isRecycled()) return 1;
    DocumentLayoutAnalyzer analyzer = new DocumentLayoutAnalyzer();
    return analyzer.getColumnCount(bitmap);
  }

  public static String decodeNumericEntities(String text) {
    if (text == null || text.isEmpty()) return text;

    Matcher decMatcher = Pattern.compile("&#(\\d+);").matcher(text);
    StringBuilder sb = new StringBuilder();
    while (decMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(decMatcher.group(1));
        String replacement = new String(Character.toChars(codePoint));
        decMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
      }
    }
    decMatcher.appendTail(sb);
    text = sb.toString();

    Matcher hexMatcher = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(text);
    sb = new StringBuilder();
    while (hexMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
        String replacement = new String(Character.toChars(codePoint));
        hexMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
      }
    }
    hexMatcher.appendTail(sb);
    return sb.toString();
  }

  private static String cleanHtmlText(String html) {
    if (html == null) return "";

    // Strip HTML tags
    String result = html.replaceAll("<[^>]+>", "");

    // Decode numeric entities
    Matcher decMatcher = Pattern.compile("&#(\\d+);").matcher(result);
    StringBuilder sb = new StringBuilder();
    while (decMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(decMatcher.group(1));
        String replacement = new String(Character.toChars(codePoint));
        decMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
      }
    }
    decMatcher.appendTail(sb);
    result = sb.toString();

    // Decode hex entities
    Matcher hexMatcher = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(result);
    sb = new StringBuilder();
    while (hexMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
        String replacement = new String(Character.toChars(codePoint));
        hexMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
      } catch (Throwable ignore) {
      }
    }
    hexMatcher.appendTail(sb);
    result = sb.toString();

    // Decode common named entities
    result = result.replace("&amp;", "&");
    result = result.replace("&lt;", "<");
    result = result.replace("&gt;", ">");
    result = result.replace("&quot;", "\"");
    result = result.replace("&apos;", "'");
    result = result.replace("&nbsp;", " ");

    return result;
  }
}
