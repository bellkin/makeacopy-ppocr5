/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentOcrBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ocr.*;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * OCRFragment handles the Optical Character Recognition (OCR) functionality within the application.
 * This fragment manages UI and orchestration; the actual OCR work is done on a dedicated
 * single-thread executor with a fresh PaddleOCR engine per job to ensure thread-safety.
 *
 * <p>Flow: Crop -> (optional) User Rotation -> OCR -> Export
 */
@AndroidEntryPoint
public class OCRFragment extends Fragment {
  private static final String TAG = "OCRFragment";
  // Early-exit threshold: if the first rotation attempt (extra=0) reaches this mean confidence,
  // we skip trying further 90° rotations to save time. Adjust if needed.
  private static final int OCR_EARLY_EXIT_MEAN_CONF_THRESHOLD = 55;

  private FragmentOcrBinding binding;
  private OCRViewModel ocrViewModel;
  private CropViewModel cropViewModel;

  // Track last observed image to decide when to reset OCR state
  private Bitmap lastObservedBitmap;

  // Language helper for listing/availability checks (no long-lived PaddleOCR engine instance)
  private OCRHelper langHelper;

  @Inject Provider<OCRHelper> ocrHelperProvider;
  @Inject DictionaryManager dictionaryManager;

  // Concurrency: serialize OCR jobs, 1 job ↔ 1 PaddleOCREngine instance
  private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
  private volatile Future<?> runningOcr = null;
  private final AtomicBoolean ocrCancelled = new AtomicBoolean(false);

  public static final String BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT = "ocr_auto_rotate_apply_export";
  public static final String BUNDLE_OCR_POST_PROCESSING = "ocr_post_processing";

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    binding = FragmentOcrBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    // If OCR was opened directly (skipping Crop), ensure we have a bitmap in CropViewModel
    try {
      if (cropViewModel.getImageBitmap().getValue() == null) {
        de.schliweb.makeacopy.ui.camera.CameraViewModel camVm =
            new ViewModelProvider(requireActivity())
                .get(de.schliweb.makeacopy.ui.camera.CameraViewModel.class);
        String path = camVm.getImagePath() != null ? camVm.getImagePath().getValue() : null;
        android.net.Uri uri = camVm.getImageUri() != null ? camVm.getImageUri().getValue() : null;
        android.graphics.Bitmap bmp = ImageLoader.decode(requireContext(), path, uri);
        if (bmp != null) {
          cropViewModel.setImageBitmap(bmp);
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // On first entry, if we have an image and no OCR results yet, remember this image
    // Do NOT reset if we already have OCR results (e.g., returning from Review screen)
    try {
      Bitmap cur = cropViewModel.getImageBitmap().getValue();
      if (cur != null) {
        lastObservedBitmap = cur;
        // Only reset if no OCR has been performed yet for this image
        OCRViewModel.OcrUiState currentState = ocrViewModel.getState().getValue();
        boolean hasOcrResults = currentState != null && currentState.imageProcessed();
        if (!hasOcrResults) {
          ocrViewModel.resetForNewImage();
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // Language helper (no initTesseract() here! Language helper for listing/availability)
    langHelper = ocrHelperProvider.get();

    // Manual model import removed - PaddleOCR models are bundled in assets

    // State observer
    ocrViewModel
        .getState()
        .observe(
            getViewLifecycleOwner(),
            state -> {
              boolean canProceed = state.imageProcessed() && !state.processing();
              binding.buttonProcess.setEnabled(canProceed);
              binding.buttonProcess.setText(R.string.next);

              binding.textOcr.setText(
                  state.processing()
                      ? getString(R.string.processing_image)
                      : (state.imageProcessed()
                          ? getString(
                              R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export)
                          : getString(R.string.no_image_processed_crop_an_image_first)));

              // Use effective text (reviewed if available, otherwise original OCR)
              String effectiveText = state.getEffectiveText();
              binding.ocrResultText.setText(
                  (effectiveText == null || effectiveText.isEmpty())
                      ? getString(R.string.ocr_results_will_appear_here)
                      : effectiveText);

              // Enable review button only when OCR finished and we have words (and feature enabled)
              if (!FeatureFlags.isOcrReviewEnabled()) {
                // When feature is disabled, hide the review button completely
                binding.buttonOcrReview.setVisibility(View.GONE);
              } else {
                boolean hasWords = state.words() != null && !state.words().isEmpty();
                boolean enableReview = state.imageProcessed() && !state.processing() && hasWords;
                binding.buttonOcrReview.setEnabled(enableReview);
                binding.buttonOcrReview.setAlpha(enableReview ? 1f : 0.4f);
                binding.buttonOcrReview.setVisibility(View.VISIBLE);
              }

              // Disable settings (OCR options) button while processing is running
              boolean processing = state.processing();
              binding.buttonOcrOptions.setEnabled(!processing);
              binding.buttonOcrOptions.setAlpha(processing ? 0.4f : 1f);

              // Proceed to Export
              binding.buttonProcess.setOnClickListener(
                  v ->
                      Navigation.findNavController(requireView()).navigate(R.id.navigation_export));
            });

    // Error events
    ocrViewModel
        .getErrorEvents()
        .observe(
            getViewLifecycleOwner(),
            ev -> {
              if (ev == null) return;
              String msg = ev.getContentIfNotHandled();
              if (msg != null)
                UIUtils.showToast(requireContext(), "OCR failed: " + msg, Toast.LENGTH_LONG);
            });

    // When image changes in Crop VM, reset OCR state if it's a different image than last time
    cropViewModel
        .getImageBitmap()
        .observe(
            getViewLifecycleOwner(),
            bitmap -> {
              if (bitmap != null) {
                if (bitmap != lastObservedBitmap) {
                  lastObservedBitmap = bitmap;
                  ocrViewModel.resetForNewImage();
                }
              }
            });

    // Insets (status bar)
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
          ViewGroup.MarginLayoutParams textParams =
              (ViewGroup.MarginLayoutParams) binding.textOcr.getLayoutParams();
          textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
          binding.textOcr.setLayoutParams(textParams);
          return insets;
        });

    // Bottom inset for button container
    UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.buttonContainer,
        (v, insets) -> {
          UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
          return insets;
        });

    // Back navigates to Crop reliably: try to pop back stack, otherwise navigate explicitly
    binding.buttonBack.setOnClickListener(
        v -> {
          try {
            // Prevent immediate auto-forward from Crop by resetting cropped state and restoring
            // original
            try {
              cropViewModel.setImageCropped(false);
              cropViewModel.setUserRotationDegrees(0);

              Bitmap orig = cropViewModel.getOriginalImageBitmap().getValue();
              if (orig != null) cropViewModel.setImageBitmap(orig);
            } catch (Throwable ignoreSet) {
              // Best-effort; failure is non-critical
            }
            androidx.navigation.NavController nav = Navigation.findNavController(requireView());
            boolean popped = nav.popBackStack();
            if (!popped) {
              nav.navigate(R.id.navigation_crop);
            }
          } catch (Throwable ignore) {
            try {
              Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
            } catch (Throwable ignored2) {
              // Best-effort; failure is non-critical
            }
          }
        });

    // Also handle system back (gesture/hardware) the same way
    OnBackPressedCallback backCallback =
        new OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            try {
              // Prevent immediate auto-forward from Crop by resetting cropped state and restoring
              // original
              try {
                cropViewModel.setImageCropped(false);
                cropViewModel.setUserRotationDegrees(0);

                Bitmap orig = cropViewModel.getOriginalImageBitmap().getValue();
                if (orig != null) cropViewModel.setImageBitmap(orig);
              } catch (Throwable ignoreSet) {
                // Best-effort; failure is non-critical
              }
              androidx.navigation.NavController nav = Navigation.findNavController(requireView());
              boolean popped = nav.popBackStack();
              if (!popped) {
                nav.navigate(R.id.navigation_crop);
              }
            } catch (Throwable ignore) {
              try {
                Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
              } catch (Throwable ignored2) {
                // Best-effort; failure is non-critical
              }
            }
          }
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), backCallback);

    // OCR options (settings) icon above the button bar
    binding.buttonOcrOptions.setVisibility(View.GONE);
    // OCR Review icon (optional, feature-flagged)
    if (!FeatureFlags.isOcrReviewEnabled()) {
      binding.buttonOcrReview.setVisibility(View.GONE);
    } else {
      binding.buttonOcrReview.setVisibility(View.VISIBLE);
      binding.buttonOcrReview.setOnClickListener(
          v -> {
            // Build OcrDoc from current OCR state and pass to Review VM
            de.schliweb.makeacopy.ui.ocr.review.OcrReviewViewModel rv =
                new ViewModelProvider(requireActivity())
                    .get(de.schliweb.makeacopy.ui.ocr.review.OcrReviewViewModel.class);
            OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
            de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc doc =
                de.schliweb.makeacopy.ui.ocr.review.model.OcrDocMapper.fromState(s);
            rv.setDoc(doc);
            Navigation.findNavController(requireView()).navigate(R.id.navigation_review);
          });
    }

    // Language selection
    setupLanguageSpinner();

    return root;
  }

  /**
   * Language spinner now only updates ViewModel language and UI. We do NOT touch any long-lived
   * PaddleOCR engine here.
   */
  private static final String PREFS_NAME = "export_options";

  private static final String PREF_KEY_OCR_LANG = "ocr_language";

  // OCR prep modes removed - PaddleOCR handles preprocessing internally

  // Maximum number of languages that can be selected for multi-language OCR
  private static final int MAX_LANGUAGES = 2;

  // Track currently selected language codes for multi-select
  private final List<String> selectedLanguageCodes = new ArrayList<>();

  private void setupLanguageSpinner() {
    AutoCompleteTextView dropdown = binding.languageSpinner;
    String[] codes = getAvailableLanguages();
    String[] displayNames = mapCodesToDisplayNames(codes);

    // Determine preferred language: saved preference (if available and installed) else system
    // default
    String systemLang =
        OCRUtils.mapSystemLanguageToTesseract(java.util.Locale.getDefault().getLanguage());
    android.content.SharedPreferences sp =
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    String savedLangSpec = null;
    try {
      savedLangSpec = sp.getString(PREF_KEY_OCR_LANG, null);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // Parse saved language spec (may contain multiple languages separated by +)
    selectedLanguageCodes.clear();
    if (savedLangSpec != null && !savedLangSpec.isEmpty()) {
      for (String lang : savedLangSpec.split("\\+", -1)) {
        String trimmed = lang.trim();
        if (!trimmed.isEmpty() && isLanguageAvailableSafe(trimmed)) {
          selectedLanguageCodes.add(trimmed);
        }
      }
    }

    // Fallback to system language if no valid saved selection
    if (selectedLanguageCodes.isEmpty()) {
      final String sysLangFinal = systemLang;
      boolean systemInList =
          IntStream.range(0, codes.length).anyMatch(i -> codes[i].equals(sysLangFinal));
      if (systemInList && isLanguageAvailableSafe(systemLang)) {
        selectedLanguageCodes.add(systemLang);
      } else if (codes.length > 0) {
        selectedLanguageCodes.add(codes[0]);
      }
    }

    // Update dropdown display text
    updateLanguageDropdownText(dropdown, codes, displayNames);

    // Set initial language in ViewModel
    String langSpec = buildLangSpec();
    ocrViewModel.setLanguage(langSpec);

    // Initial auto-run logic (only if not already processed)
    Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
    de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st0 = ocrViewModel.getState().getValue();
    boolean alreadyProcessed0 = (st0 != null && st0.imageProcessed());
    if (bitmap != null && !alreadyProcessed0) {
      PaddleOCREngine.breadcrumb(requireContext(), "ocrfg_auto_trigger");
      performOCR();
    }

    // Set click listener to show multi-select dialog
    dropdown.setOnClickListener(v -> showMultiLanguageDialog(codes, displayNames, dropdown));
    dropdown.setFocusable(false);
    dropdown.setClickable(true);
  }

  /** Shows a multi-select dialog for choosing OCR languages (max 2). */
  private void showMultiLanguageDialog(
      String[] codes, String[] displayNames, AutoCompleteTextView dropdown) {
    boolean[] checkedItems = new boolean[codes.length];
    for (int i = 0; i < codes.length; i++) {
      checkedItems[i] = selectedLanguageCodes.contains(codes[i]);
    }

    AlertDialog dlg =
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_ocr_languages)
            .setMultiChoiceItems(
                displayNames,
                checkedItems,
                (dialog, which, isChecked) -> {
                  String code = codes[which];
                  if (isChecked) {
                    // Check if max languages reached
                    if (selectedLanguageCodes.size() >= MAX_LANGUAGES) {
                      // Uncheck this item and show warning
                      ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                      checkedItems[which] = false;
                      UIUtils.showToast(
                          requireContext(),
                          getString(R.string.ocr_max_languages_warning),
                          Toast.LENGTH_SHORT);
                      return;
                    }
                    if (!selectedLanguageCodes.contains(code)) {
                      selectedLanguageCodes.add(code);
                    }
                  } else {
                    selectedLanguageCodes.remove(code);
                  }
                })
            .setPositiveButton(
                android.R.string.ok,
                (dialog, which) -> {
                  if (selectedLanguageCodes.isEmpty()) {
                    UIUtils.showToast(
                        requireContext(),
                        getString(R.string.ocr_no_language_selected),
                        Toast.LENGTH_SHORT);
                    // Fallback to first available language
                    if (codes.length > 0) {
                      selectedLanguageCodes.add(codes[0]);
                    }
                  }

                  String prevLang = ocrViewModel.getLanguage().getValue();
                  String newLangSpec = buildLangSpec();

                  // Update display
                  updateLanguageDropdownText(dropdown, codes, displayNames);

                  // Update ViewModel
                  ocrViewModel.setLanguage(newLangSpec);

                  // Persist selection
                  try {
                    android.content.SharedPreferences sp =
                        requireContext()
                            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
                    sp.edit().putString(PREF_KEY_OCR_LANG, newLangSpec).apply();
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }

                  // Handle re-run if language changed
                  de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st =
                      ocrViewModel.getState().getValue();
                  boolean processed = (st != null && st.imageProcessed());
                  boolean changed = !Objects.equals(prevLang, newLangSpec);
                  if (processed && changed) {
                    binding.buttonProcess.setText(R.string.btn_process);
                    binding.buttonProcess.setOnClickListener(v -> performOCR());
                  } else if (processed) {
                    binding.buttonProcess.setText(R.string.next);
                    binding.buttonProcess.setOnClickListener(
                        v ->
                            Navigation.findNavController(requireView())
                                .navigate(R.id.navigation_export));
                  }
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dlg.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dlg, requireContext()));
    dlg.show();
  }

  /** Builds the language specification string from selected languages (e.g., "deu+eng"). */
  private String buildLangSpec() {
    if (selectedLanguageCodes.isEmpty()) {
      return "eng"; // Fallback
    }
    return String.join("+", selectedLanguageCodes);
  }

  /** Updates the dropdown text to show selected languages. */
  private void updateLanguageDropdownText(
      AutoCompleteTextView dropdown, String[] codes, String[] displayNames) {
    if (selectedLanguageCodes.isEmpty()) {
      dropdown.setText("", false);
      return;
    }

    StringBuilder displayText = new StringBuilder();
    for (int i = 0; i < selectedLanguageCodes.size(); i++) {
      String code = selectedLanguageCodes.get(i);
      // Find display name for this code
      int idx =
          IntStream.range(0, codes.length)
              .filter(j -> codes[j].equals(code))
              .findFirst()
              .orElse(-1);
      if (idx >= 0) {
        if (displayText.length() > 0) displayText.append(" + ");
        displayText.append(displayNames[idx]);
      }
    }
    dropdown.setText(displayText.toString(), false);
  }

  private boolean isLanguageAvailableSafe(String lang) {
    try {
      return langHelper != null && langHelper.isLanguageAvailable(lang);
    } catch (Throwable t) {
      return false;
    }
  }

  /** Get available languages without keeping a long-lived PaddleOCR engine. */
  private String[] getAvailableLanguages() {
    try {
      if (langHelper != null) {
        String[] langs = langHelper.getAvailableLanguages();
        if (langs != null && langs.length > 0) return langs;
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    // Fallback includes Chinese (Simplified and Traditional) so users on zh locales can select them
    // when asset listing fails
    return OCRUtils.getLanguages();
  }

  private String[] mapCodesToDisplayNames(String[] codes) {
    String[] out = new String[codes.length];
    for (int i = 0; i < codes.length; i++) {
      out[i] = codeToDisplayName(codes[i]);
    }
    return out;
  }

  private String codeToDisplayName(String code) {
    // Map common Tesseract 3-letter codes to 2-letter BCP-47 where possible, for localization
    String two;
    switch (code) {
      case "eng":
        two = "en";
        break;
      case "deu":
        two = "de";
        break;
      case "fra":
        two = "fr";
        break;
      case "ita":
        two = "it";
        break;
      case "spa":
        two = "es";
        break;
      case "por":
        two = "pt";
        break;
      case "nld":
        two = "nl";
        break;
      case "pol":
        two = "pl";
        break;
      case "ces":
        two = "cs";
        break;
      case "slk":
        two = "sk";
        break;
      case "hun":
        two = "hu";
        break;
      case "ron":
        two = "ro";
        break;
      case "dan":
        two = "da";
        break;
      case "nor":
        two = "no";
        break;
      case "swe":
        two = "sv";
        break;
      case "rus":
        two = "ru";
        break;
      case "tha":
        two = "th";
        break;
      case "fas":
        two = "fa";
        break;
      case "ara":
        two = "ar";
        break;
      case "hin":
        two = "hi";
        break;
      case "tur":
        two = "tr";
        break;
      case "chi_sim":
        return "Chinese (Simplified)";
      case "chi_tra":
        return "Chinese (Traditional)";
      default:
        // Fallback: try first two letters
        if (code != null && code.length() >= 2) {
          two = code.substring(0, 2);
        } else {
          two = "en";
        }
    }
    String baseName;
    try {
      java.util.Locale loc = java.util.Locale.forLanguageTag(two);
      baseName = loc.getDisplayLanguage(java.util.Locale.getDefault());
    } catch (Throwable ignore) {
      baseName = code;
    }
    return baseName;
  }

  /**
   * Executes OCR in a single-thread executor with a fresh PaddleOCR engine per job. Rotation
   * handling: capture-rotation compensation, then user rotation (after crop, before OCR). No
   * write-back to CropViewModel from OCR thread.
   */
  private void performOCR() {
    PaddleOCREngine.breadcrumb(requireContext(), "ocrfg_performOCR_start");
    if (ocrExecutor.isShutdown()) {
      UIUtils.showToast(
          requireContext(), "Screen is closing, cannot start OCR", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: Executor is already shut down; aborting");
      return;
    }

    Bitmap imageBitmap = cropViewModel.getImageBitmap().getValue();
    Boolean croppedFlagAtStart = cropViewModel.isImageCropped().getValue();
    Integer userRotAtStart = cropViewModel.getUserRotationDegrees().getValue();
    // captureRotationDegrees value intentionally read to trigger LiveData observation
    Log.d(
        TAG,
        "performOCR: start on thread="
            + Thread.currentThread().getName()
            + ", imageBitmap="
            + (imageBitmap == null
                ? "null"
                : (imageBitmap.getWidth() + "x" + imageBitmap.getHeight()))
            + ", isImageCropped="
            + croppedFlagAtStart
            + ", userDeg="
            + userRotAtStart);
    if (imageBitmap == null) {
      UIUtils.showToast(requireContext(), "No image to process", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: No image present in CropViewModel");
      return;
    }

    // Prevent parallel runs
    if (runningOcr != null && !runningOcr.isDone()) {
      UIUtils.showToast(requireContext(), "OCR already running", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: A previous OCR task is still running; ignoring new request");
      return;
    }

    ocrViewModel.startProcessing();
    ocrCancelled.set(false);

    try {
      runningOcr =
          ocrExecutor.submit(
              () -> {
                final String LP = "[OCR_LOG] ";
                long t0 = System.nanoTime();
                OCRHelper localHelper = null;
                try {
                  Log.d(TAG, LP + "BG thread=" + Thread.currentThread().getName());
                  // Prepare bitmap (orientation corrections)
                  Log.d(TAG, LP + "Preparing image for OCR - orientation handling");
                  Bitmap src = imageBitmap;

                  // Note: We ignore capture/EXIF rotation here because the app is locked to
                  // portrait
                  // (AndroidManifest: android:screenOrientation="portrait") and handles
                  // configChanges for orientation/screenSize/screenLayout. In this setup,
                  // getCaptureRotationDegrees() is effectively always 0 and evaluating it adds no
                  // value.
                  // If orientation handling changes in the future, restore compensation here if
                  // needed.
                  Bitmap srcNoCaptureRotation = src; // alias for clarity
                  src = srcNoCaptureRotation;

                  // Apply user-requested rotation (after crop, before OCR)
                  int userDeg = 0;
                  Integer ur = cropViewModel.getUserRotationDegrees().getValue();
                  if (ur != null) userDeg = ((ur % 360) + 360) % 360;
                  if (userDeg != 0) {
                    src = rotateBitmap(src, userDeg);
                  }

                  // We will try OCR in 90° steps (0, 90, 180, 270) to guard against wrong user
                  // rotation
                  // Start with current src (already including userDeg). For each attempt, rotate
                  // extra and pick best by meanConfidence, then by text length.

                  if (ocrCancelled.get()) {
                    Log.w(TAG, LP + "Cancelled before OCR init");
                    postError("Cancelled");
                    return;
                  }

                  // Fresh engine per job
                  localHelper = ocrHelperProvider.get();
                  // 1 job = 1 engine instance. No automatic reinitialization per run.
                  try {
                    localHelper.setReinitPerRun(false);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }

                  String lang = ocrViewModel.getLanguage().getValue();
                  if (lang == null || lang.isEmpty()) lang = "eng";
                  Log.d(TAG, LP + "Language requested=" + lang);

                  try {
                    // Ensure language is set BEFORE init so the engine loads the correct model
                    localHelper.setLanguage(lang);
                  } catch (Throwable t) {
                    Log.e(TAG, LP + "Failed to set language " + lang, t);
                  }

                  long tInit0 = System.nanoTime();
                  boolean initOk = false;
                  try {
                    initOk = localHelper.initTesseract();
                  } catch (Throwable t) {
                    Log.e(TAG, LP + "initTesseract threw", t);
                  }
                  Log.d(
                      TAG,
                      LP
                          + "Engine init ok="
                          + initOk
                          + ", took="
                          + ((System.nanoTime() - tInit0) / 1_000_000L)
                          + "ms");
                  if (!initOk) {
                    postError("Engine not initialized");
                    return;
                  }

                  // Try OCR rotations only when Auto‑Rotate is enabled. Otherwise, use current
                  // orientation only.
                  boolean allowOcrAutoRotate = false;
                  boolean useLayoutAnalysis = false;
                  try {
                    android.content.SharedPreferences p =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    allowOcrAutoRotate = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                  useLayoutAnalysis = FeatureFlags.isLayoutAnalysisEnabled();
                  final boolean layoutAnalysisEnabled = useLayoutAnalysis;

                  // When disabled, restrict to a single attempt at the current orientation
                  // (extra=0)
                  int[] extraRots =
                      allowOcrAutoRotate ? new int[] {0, 90, 180, 270} : new int[] {0};
                  OCRHelper.OcrResultWords bestResult = null;
                  OCRViewModel.OcrTransform bestTx = null;
                  int bestRot = 0;

                  for (int extra : extraRots) {
                    if (ocrCancelled.get()) {
                      Log.w(TAG, LP + "Cancelled before OCR run (extraRot=" + extra + ")");
                      postError("Cancelled");
                      return;
                    }

                    Bitmap rotated = (extra == 0) ? src : rotateBitmap(src, extra);
                    Bitmap inputForOcr = rotated;

                    OCRViewModel.OcrTransform tx =
                        new OCRViewModel.OcrTransform(
                            rotated.getWidth(),
                            rotated.getHeight(),
                            inputForOcr.getWidth(),
                            inputForOcr.getHeight(),
                            inputForOcr.getWidth() / (float) rotated.getWidth(),
                            inputForOcr.getHeight() / (float) rotated.getHeight(),
                            0,
                            0);
                    Log.d(
                        TAG,
                        LP
                            + "Transform: src="
                            + tx.srcW()
                            + "x"
                            + tx.srcH()
                            + ", dst="
                            + tx.dstW()
                            + "x"
                            + tx.dstH()
                            + ", sx="
                            + tx.scaleX()
                            + ", sy="
                            + tx.scaleY());

                    // Run OCR (use layout analysis if enabled)
                    OCRHelper.OcrResultWords r;
                    if (layoutAnalysisEnabled) {
                      OCRHelper.OcrResultWithLayout layoutResult =
                          localHelper.runOcrWithLayout(inputForOcr);
                      // Collect all words from all regions
                      List<RecognizedWord> allWords = new ArrayList<>();
                      for (OCRHelper.RegionOcrResult regionResult : layoutResult.regionResults) {
                        if (regionResult.ocrResult() != null
                            && regionResult.ocrResult().words != null) {
                          allWords.addAll(regionResult.ocrResult().words);
                        }
                      }
                      r =
                          new OCRHelper.OcrResultWords(
                              layoutResult.text, layoutResult.meanConfidence, allWords);
                    } else {
                      r = localHelper.runOcrWithRetry(inputForOcr);
                    }

                    if (ocrCancelled.get()) {
                      Log.w(TAG, LP + "Cancelled after OCR run (extraRot=" + extra + ")");
                      postError("Cancelled");
                      return;
                    }

                    // Early-exit: if the first attempt (extra=0) is already strong enough, skip
                    // other rotations
                    if (extra == 0) {
                      int mc0 = (r.meanConfidence != null ? r.meanConfidence : 0);
                      boolean hasWords0 = r.words != null && !r.words.isEmpty();
                      boolean hasText0 = r.text != null && !r.text.trim().isEmpty();
                      boolean hasContent0 = hasWords0 || hasText0;
                      if (hasContent0 && mc0 >= OCR_EARLY_EXIT_MEAN_CONF_THRESHOLD) {
                        bestResult = r;
                        bestTx = tx;
                        bestRot = 0;
                        Log.d(
                            TAG,
                            LP
                                + "Early-exit: meanConf="
                                + mc0
                                + " >= "
                                + OCR_EARLY_EXIT_MEAN_CONF_THRESHOLD
                                + ", hasContent=true, words="
                                + (r.words != null ? r.words.size() : 0)
                                + ", textLen="
                                + (r.text != null ? r.text.length() : 0)
                                + ", skipping further rotations");
                        break;
                      }
                    }

                    // Choose best deterministically:
                    // 1) content presence (words/text)
                    // 2) mean confidence
                    // 3) content size (words count, then text length)
                    boolean take;
                    if (bestResult == null) {
                      take = true;
                    } else {
                      boolean hasWords = r.words != null && !r.words.isEmpty();
                      boolean hasText = r.text != null && !r.text.trim().isEmpty();
                      boolean hasContent = hasWords || hasText;

                      boolean bestHasWords =
                          bestResult.words != null && !bestResult.words.isEmpty();
                      boolean bestHasText =
                          bestResult.text != null && !bestResult.text.trim().isEmpty();
                      boolean bestHasContent = bestHasWords || bestHasText;

                      if (hasContent != bestHasContent) {
                        take = hasContent; // non-empty beats empty
                      } else {
                        float mc = (r.meanConfidence != null ? r.meanConfidence : 0f);
                        float bestMc =
                            (bestResult.meanConfidence != null ? bestResult.meanConfidence : 0f);
                        if (mc > bestMc + 0.01f) { // small epsilon
                          take = true;
                        } else if (Math.abs(mc - bestMc) <= 0.01f) {
                          int wc = (r.words != null ? r.words.size() : 0);
                          int bestWc = (bestResult.words != null ? bestResult.words.size() : 0);
                          if (wc != bestWc) {
                            take = wc > bestWc;
                          } else {
                            int len = (r.text != null ? r.text.length() : 0);
                            int bestLen = (bestResult.text != null ? bestResult.text.length() : 0);
                            take = len > bestLen;
                          }
                        } else {
                          take = false;
                        }
                      }
                    }

                    if (take) {
                      bestResult = r;
                      bestTx = tx;
                      bestRot = extra;
                    }
                  }

                  if (bestResult == null || bestTx == null) {
                    postError("OCR failed (no result)");
                    return;
                  }

                  // Push transform of best attempt to VM on UI thread
                  OCRViewModel.OcrTransform finalTx = bestTx;
                  final int bestRotFinal = bestRot;
                  final boolean finalAllowOcrAutoRotate = allowOcrAutoRotate;
                  runOnUiThreadSafe(
                      () -> {
                        ocrViewModel.setTransform(finalTx);
                        try {
                          // Store best OCR rotation (relative extra rotation) for optional export
                          // alignment
                          if (cropViewModel != null) {
                            // Only persist the computed rotation if the feature is enabled;
                            // otherwise reset to 0
                            cropViewModel.setBestOcrRotationDegrees(
                                finalAllowOcrAutoRotate ? bestRotFinal : 0);
                          }
                        } catch (Throwable ignore) {
                          // Best-effort; failure is non-critical
                        }
                      });

                  long durMs = (System.nanoTime() - t0) / 1_000_000L;
                  // Never persist UI placeholder strings as OCR output.
                  // Persist only real OCR output; use "" when OCR returned nothing.
                  String ocrText =
                      (bestResult.text == null || bestResult.text.trim().isEmpty())
                          ? ""
                          : bestResult.text;
                  List<RecognizedWord> ocrWords =
                      (bestResult.words != null) ? bestResult.words : new ArrayList<>();

                  // Apply post-processing to correct common OCR errors (including dictionary-based
                  // correction)
                  // Only if the option is enabled (default: ON)
                  boolean postProcessingEnabled = true;
                  try {
                    android.content.SharedPreferences pp =
                        requireContext()
                            .getSharedPreferences(
                                "export_options", android.content.Context.MODE_PRIVATE);
                    postProcessingEnabled = pp.getBoolean(BUNDLE_OCR_POST_PROCESSING, true);
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                  if (postProcessingEnabled) {
                    try {
                      // Process words with dictionary - this is the single source of truth
                      ocrWords =
                          OCRPostProcessor.processWithDictionary(ocrWords, lang, dictionaryManager);
                      // Derive text from processed words instead of processing text separately
                      ocrText = OCRPostProcessor.wordsToText(ocrWords);
                      if (ocrText == null || ocrText.trim().isEmpty()) ocrText = "";
                      // Log quality statistics
                      OCRPostProcessor.OcrQualityStats stats =
                          OCRPostProcessor.analyzeQuality(ocrWords);
                      Log.d(TAG, LP + "OCR Quality: " + stats);
                    } catch (Throwable t) {
                      Log.w(TAG, LP + "Post-processing failed", t);
                    }
                  } else {
                    Log.d(TAG, LP + "OCR post-processing disabled by user preference");
                    // Even without post-processing, derive text from words for consistency
                    ocrText = OCRPostProcessor.wordsToText(ocrWords); // TODO
                    if (ocrText == null || ocrText.trim().isEmpty()) ocrText = "";
                  }

                  // Create final variables for lambda
                  final String finalText = ocrText;
                  final List<RecognizedWord> words = ocrWords;

                  int appliedExtraRot = bestRot; // for logging only
                  Integer bestMeanConf = bestResult.meanConfidence;
                  boolean bestHasWords = bestResult.words != null && !bestResult.words.isEmpty();
                  boolean bestHasText =
                      bestResult.text != null && !bestResult.text.trim().isEmpty();
                  boolean bestHasContent = bestHasWords || bestHasText;
                  Log.d(
                      TAG,
                      LP
                          + "Best rotation extra="
                          + appliedExtraRot
                          + "°, meanConf="
                          + bestMeanConf
                          + ", hasContent="
                          + bestHasContent
                          + ", words="
                          + (bestResult.words != null ? bestResult.words.size() : 0)
                          + ", textLen="
                          + (bestResult.text == null ? 0 : bestResult.text.length()));

                  final Integer meanConfFinal = bestMeanConf;
                  runOnUiThreadSafe(
                      () -> {
                        ocrViewModel.setWords(words);
                        ocrViewModel.finishSuccess(finalText, words, durMs, meanConfFinal, finalTx);
                        // If Auto‑Rotate is enabled, show the found rotation to the user
                        try {
                          android.content.SharedPreferences p =
                              requireContext()
                                  .getSharedPreferences(
                                      "export_options", android.content.Context.MODE_PRIVATE);
                          boolean apply = p.getBoolean(BUNDLE_OCR_AUTO_ROTATE_APPLY_EXPORT, false);
                          // UX guard: never show a high confidence score for an empty OCR result.
                          // Determine content based on the final values persisted to the ViewModel.
                          boolean hasWordsFinal = words != null && !words.isEmpty();
                          boolean hasTextFinal = finalText != null && !finalText.trim().isEmpty();
                          boolean hasContentFinal = hasWordsFinal || hasTextFinal;
                          int score =
                              hasContentFinal ? (meanConfFinal != null ? meanConfFinal : -1) : -1;

                          // UX hint: If no text was detected, show a neutral toast once per OCR
                          // run.
                          // In this case we also suppress any score/rotation toast to avoid
                          // multiple toasts.
                          if (!hasContentFinal) {
                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.ocr_no_text_detected),
                                Toast.LENGTH_SHORT);
                            return;
                          }
                          // When enabled, also apply the detected rotation to the current scan for
                          // export,
                          // respecting the unified rotation model (apply in-memory; persist will
                          // bake).
                          if (apply) {
                            try {
                              // Add bestRotFinal to the current user rotation in CropViewModel
                              if (cropViewModel != null) {
                                Integer cur = null;
                                try {
                                  cur = cropViewModel.getUserRotationDegrees().getValue();
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                                int curDeg = (cur == null) ? 0 : cur.intValue();
                                int newDeg = ((curDeg + bestRotFinal) % 360 + 360) % 360;
                                try {
                                  cropViewModel.setUserRotationDegrees(newDeg);
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                                // We have applied the OCR suggestion; clear the helper to avoid
                                // re-applying later.
                                try {
                                  cropViewModel.setBestOcrRotationDegrees(0);
                                } catch (Throwable ignore) {
                                  // Best-effort; failure is non-critical
                                }
                              }
                            } catch (Throwable ignore) {
                              // Best-effort; failure is non-critical
                            }
                          }
                          if (apply) {
                            // If we know the score, show rotation + score combined; otherwise, show
                            // rotation only.
                            if (score >= 0) {
                              UIUtils.showToast(
                                  requireContext(),
                                  getString(
                                      R.string.ocr_found_rotation_with_score, bestRotFinal, score),
                                  Toast.LENGTH_SHORT);
                            } else {
                              UIUtils.showToast(
                                  requireContext(),
                                  getString(R.string.ocr_found_rotation, bestRotFinal),
                                  Toast.LENGTH_SHORT);
                            }
                          } else if (score >= 0) {
                            // Auto‑Rotate not applied, but still useful to show the OCR score.
                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.ocr_score, score),
                                Toast.LENGTH_SHORT);
                          }
                        } catch (Throwable ignore) {
                          // Best-effort; failure is non-critical
                        }
                      });

                } catch (Throwable e) {
                  Log.e(TAG, "performOCR: Unexpected error", e);
                  postError(e.getMessage() != null ? e.getMessage() : e.toString());
                } finally {
                  // Release PaddleOCR engine in the same thread that used it
                  try {
                    if (localHelper != null) localHelper.shutdown();
                    Log.d(TAG, LP + "PaddleOCR shutdown complete");
                  } catch (Throwable ignored) {
                    // Best-effort; failure is non-critical
                  }
                }
              });
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      UIUtils.showToast(requireContext(), "OCR service is shutting down", Toast.LENGTH_SHORT);
      Log.w(TAG, "performOCR: RejectedExecutionException (executor shutting down)", ex);
      ocrViewModel.finishError("Executor shutdown");
    }
  }

  private void postError(String msg) {
    runOnUiThreadSafe(
        () -> {
          ocrViewModel.finishError(msg);
        });
  }

  private void runOnUiThreadSafe(Runnable r) {
    if (!isAdded()) return;
    try {
      requireActivity()
          .runOnUiThread(
              () -> {
                if (!isAdded() || binding == null) return;
                r.run();
              });
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    // Signal cancel; do NOT forcibly interrupt the running job (avoid tearing down PaddleOCR
    // mid-call)
    ocrCancelled.set(true);

    binding = null;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    // Fragment is going away for good: now it's safe to shut down the executor
    ocrExecutor.shutdown();
  }

  /**
   * Rotates the given Bitmap by the specified degree in a clockwise direction. If the degrees are a
   * multiple of 360, the original Bitmap is returned unchanged.
   *
   * @param src The Bitmap to be rotated. Must not be null.
   * @param degreesCW The number of degrees to rotate the Bitmap clockwise. Values outside the range
   *     [0, 360) will be normalized.
   * @return A new rotated Bitmap object, or the original Bitmap if no rotation is applied.
   */
  private static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
    int deg = ((degreesCW % 360) + 360) % 360;
    if (deg == 0) return src;
    Matrix m = new Matrix();
    m.postRotate(deg);
    return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
  }
}
