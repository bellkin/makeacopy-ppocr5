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

import java.util.*;
import lombok.experimental.UtilityClass;

@UtilityClass
public class OCRUtils {

  private static final Map<String, String> TESSERACT_TO_PADDLE = new HashMap<>();
  private static final Map<String, List<String>> PADDLE_TO_TESSERACT = new HashMap<>();

  static {
    // English
    TESSERACT_TO_PADDLE.put("eng", "english");

    // Latin group (most European languages)
    String[] latin = {
      "deu", "fra", "ita", "spa", "por", "nld", "pol", "ces", "slk", "hun", "ron", "dan", "nor",
      "swe", "tur"
    };
    for (String l : latin) TESSERACT_TO_PADDLE.put(l, "latin");

    // Slavic/Cyrillic
    TESSERACT_TO_PADDLE.put("rus", "eslav");

    // Arabic script
    TESSERACT_TO_PADDLE.put("ara", "arabic");
    TESSERACT_TO_PADDLE.put("fas", "arabic");

    // Hindi
    TESSERACT_TO_PADDLE.put("hin", "hindi");

    // Thai
    TESSERACT_TO_PADDLE.put("tha", "thai");

    // Chinese
    TESSERACT_TO_PADDLE.put("chi_sim", "chinese");
    TESSERACT_TO_PADDLE.put("chi_tra", "chinese");

    // Korean
    // Not in original Tesseract langs, but if added
    // TESSERACT_TO_PADDLE.put("kor", "korean");

    // Build reverse mapping
    PADDLE_TO_TESSERACT.put("english", Collections.singletonList("eng"));
    PADDLE_TO_TESSERACT.put(
        "latin",
        Arrays.asList(
            "eng", "deu", "fra", "ita", "spa", "por", "nld", "pol", "ces", "slk", "hun", "ron",
            "dan", "nor", "swe", "tur"));
    PADDLE_TO_TESSERACT.put("eslav", Collections.singletonList("rus"));
    PADDLE_TO_TESSERACT.put("arabic", Arrays.asList("ara", "fas"));
    PADDLE_TO_TESSERACT.put("hindi", Collections.singletonList("hin"));
    PADDLE_TO_TESSERACT.put("thai", Collections.singletonList("tha"));
    PADDLE_TO_TESSERACT.put("chinese", Arrays.asList("chi_sim", "chi_tra"));
    PADDLE_TO_TESSERACT.put("korean", Collections.emptyList());
    PADDLE_TO_TESSERACT.put("greek", Collections.emptyList());
    PADDLE_TO_TESSERACT.put("tamil", Collections.emptyList());
    PADDLE_TO_TESSERACT.put("telugu", Collections.emptyList());
  }

  public static String resolveEffectiveLanguage(String languageOpt) {
    if (languageOpt != null && !languageOpt.trim().isEmpty()) {
      return languageOpt;
    }
    try {
      Locale loc = Locale.getDefault();
      String sys = loc.getLanguage();
      if ("zh".equalsIgnoreCase(sys)) {
        String country = loc.getCountry();
        if ("TW".equalsIgnoreCase(country)
            || "HK".equalsIgnoreCase(country)
            || "MO".equalsIgnoreCase(country)) {
          return "chi_tra";
        } else {
          return "chi_sim";
        }
      } else if ("de".equalsIgnoreCase(sys)) return "deu";
      else if ("fr".equalsIgnoreCase(sys)) return "fra";
      else if ("it".equalsIgnoreCase(sys)) return "ita";
      else if ("es".equalsIgnoreCase(sys)) return "spa";
      else if ("pt".equalsIgnoreCase(sys)) return "por";
      else if ("nl".equalsIgnoreCase(sys)) return "nld";
      else if ("pl".equalsIgnoreCase(sys)) return "pol";
      else if ("cs".equalsIgnoreCase(sys)) return "ces";
      else if ("ru".equalsIgnoreCase(sys)) return "rus";
      else if ("th".equalsIgnoreCase(sys)) return "tha";
      else if ("sk".equalsIgnoreCase(sys)) return "slk";
      else if ("hu".equalsIgnoreCase(sys)) return "hun";
      else if ("ro".equalsIgnoreCase(sys)) return "ron";
      else if ("da".equalsIgnoreCase(sys)) return "dan";
      else if ("sv".equalsIgnoreCase(sys)) return "swe";
      else if ("no".equalsIgnoreCase(sys)
          || "nb".equalsIgnoreCase(sys)
          || "nn".equalsIgnoreCase(sys)) return "nor";
      else if ("fa".equalsIgnoreCase(sys)) return "fas";
      else if ("ar".equalsIgnoreCase(sys)) return "ara";
      else if ("hi".equalsIgnoreCase(sys)) return "hin";
      else if ("tr".equalsIgnoreCase(sys)) return "tur";
      else return "eng";
    } catch (Throwable ignore) {
      return "eng";
    }
  }

  public static String mapSystemLanguageToTesseract(String systemLanguage) {
    if (systemLanguage == null) return "eng";
    return switch (systemLanguage) {
      case "en" -> "eng";
      case "de" -> "deu";
      case "fr" -> "fra";
      case "it" -> "ita";
      case "es" -> "spa";
      case "pt" -> "por";
      case "nl" -> "nld";
      case "pl" -> "pol";
      case "cs" -> "ces";
      case "ru" -> "rus";
      case "th" -> "tha";
      case "sk" -> "slk";
      case "hu" -> "hun";
      case "ro" -> "ron";
      case "da" -> "dan";
      case "sv" -> "swe";
      case "no", "nb", "nn" -> "nor";
      case "fa" -> "fas";
      case "ar" -> "ara";
      case "hi" -> "hin";
      case "tr" -> "tur";
      case "zh" -> {
        try {
          Locale loc = Locale.getDefault();
          String country = loc.getCountry();
          if ("TW".equalsIgnoreCase(country)
              || "HK".equalsIgnoreCase(country)
              || "MO".equalsIgnoreCase(country)) {
            yield "chi_tra";
          }
        } catch (Throwable ignore) {
        }
        yield "chi_sim";
      }
      default -> "eng";
    };
  }

  /** Maps a Tesseract 3-letter language code to the corresponding PaddleOCR language group. */
  public static String mapTesseractToPaddleGroup(String tesseractLang) {
    if (tesseractLang == null || tesseractLang.isEmpty()) return "english";
    if (tesseractLang.contains("+")) {
      return mapTesseractToPaddleGroup(tesseractLang.split("\\+", -1)[0].trim());
    }
    String group = TESSERACT_TO_PADDLE.get(tesseractLang);
    if (group != null) return group;
    return "latin";
  }

  /** Maps a PaddleOCR language group to the set of Tesseract language codes it covers. */
  public static List<String> mapPaddleGroupToTesseract(String paddleGroup) {
    if (paddleGroup == null || paddleGroup.isEmpty()) return Collections.emptyList();
    List<String> langs = PADDLE_TO_TESSERACT.get(paddleGroup);
    if (langs != null) return langs;
    return Collections.emptyList();
  }

  public static String[] getLanguages() {
    return new String[] {
      "eng", "deu", "fra", "ita", "spa", "por", "nld", "pol", "ces", "slk", "hun", "ron", "dan",
      "nor", "swe", "rus", "tha", "fas", "ara", "hin", "tur", "chi_sim", "chi_tra"
    };
  }
}
