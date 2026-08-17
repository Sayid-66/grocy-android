/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2020-2024 by Patrick Zedler and Dominic Zedler
 * Copyright (c) 2024-2026 by Patrick Zedler
 */

package xyz.zedler.patrick.grocy.model;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.api.OpenFoodFactsApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.helper.DownloadHelper.OnErrorListener;
import xyz.zedler.patrick.grocy.helper.DownloadHelper.OnObjectResponseListener;
import xyz.zedler.patrick.grocy.util.OffPackagingUtil;

public class OpenFoodFactsProduct {

  private JSONObject productJson;

  @SerializedName("_id")
  private String id;

  @SerializedName("product_name")
  private String productName;

  @Nullable
  @SerializedName("nutriments")
  private OpenFoodFactsNutriments nutriments;

  @Nullable
  @SerializedName("brands")
  private String brands;

  @Nullable
  @SerializedName("quantity")
  private String quantity;

  @Nullable
  @SerializedName("image_front_url")
  private String imageFrontUrl;

  @Nullable
  @SerializedName("image_url")
  private String imageUrl;

  @Nullable
  @SerializedName("categories_tags")
  private List<String> categoriesTags;

  @Nullable
  @SerializedName("ingredients_text")
  private String ingredientsText;

  @Nullable
  @SerializedName("allergens")
  private String allergens;

  @Nullable
  @SerializedName("allergens_tags")
  private List<String> allergensTags;

  @Nullable
  @SerializedName("nutriscore_grade")
  private String nutriscoreGrade;

  @Nullable
  @SerializedName("manufacturing_places")
  private String manufacturingPlaces;

  @Nullable
  @SerializedName("origins")
  private String origins;

  @Nullable
  @SerializedName("packaging_shapes_tags")
  private List<String> packagingShapesTags;

  @Nullable
  @SerializedName("packaging_tags")
  private List<String> packagingTags;

  public void setProductJson(JSONObject productJson) {
    this.productJson = productJson;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getLocalizedProductName(Application application) {
    String language = application.getResources().getConfiguration().locale.getLanguage();
    String country = application.getResources().getConfiguration().locale.getCountry();
    String both = language + "_" + country;
    if (productJson == null) return this.productName;
    String name = productJson.optString("product_name_" + both);
    if(name.isEmpty()) {
      name = productJson.optString("product_name_" + language);
    }
    return name.isEmpty() ? this.productName : name;
  }

  @Nullable
  public String getBrands() {
    return cleanOffText(brands);
  }

  @Nullable
  public String getQuantity() {
    return cleanOffText(quantity);
  }

  /**
   * Collapses newlines/tabs/repeated whitespace into single spaces and trims. OFF free-text
   * fields (ingredients, allergens, brands, ...) sometimes contain raw newlines; left as-is,
   * those would later break deep-link navigation entirely and silently (the Navigation
   * component's argument regex does not match across line breaks), not just look odd in the UI.
   */
  @Nullable
  private static String cleanOffText(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.replaceAll("\\s+", " ").trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  @Nullable
  public String getImageUrl() {
    if (imageFrontUrl != null && !imageFrontUrl.isEmpty()
        && imageFrontUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
      return imageFrontUrl;
    } else if (imageUrl != null && !imageUrl.isEmpty()
        && imageUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
      return imageUrl;
    } else {
      return null;
    }
  }

  @NonNull
  public List<String> getCategoriesTags() {
    return categoriesTags != null ? categoriesTags : Collections.emptyList();
  }

  @Nullable
  public String getIngredientsText() {
    return cleanOffText(ingredientsText);
  }

  @Nullable
  public String getAllergens() {
    return cleanOffText(allergens);
  }

  @NonNull
  public List<String> getAllergensTags() {
    return allergensTags != null ? allergensTags : Collections.emptyList();
  }

  /**
   * Uppercased A-E grade, or null if OFF has no real grade. OFF also uses "unknown" and
   * "not-applicable" as the field value when there simply is no Nutri-Score - those are not a
   * grade and must not be displayed as if they were one.
   */
  @Nullable
  public String getNutriscoreGrade() {
    String grade = cleanOffText(nutriscoreGrade);
    if (grade == null || grade.equalsIgnoreCase("unknown")
        || grade.equalsIgnoreCase("not-applicable")) {
      return null;
    }
    return grade.toUpperCase(Locale.ROOT);
  }

  @Nullable
  public String getManufacturingPlaces() {
    return cleanOffText(manufacturingPlaces);
  }

  @Nullable
  public String getOrigins() {
    return cleanOffText(origins);
  }

  /**
   * Combines origins + manufacturing places into one display string. Never invents a value:
   * only concatenates what OFF already returned, no guessing which one is "correct".
   */
  @Nullable
  public String getOriginInfo() {
    String originsCleaned = getOrigins();
    String placesCleaned = getManufacturingPlaces();
    if (originsCleaned != null && placesCleaned != null
        && !originsCleaned.equalsIgnoreCase(placesCleaned)) {
      return originsCleaned + " / " + placesCleaned;
    }
    return originsCleaned != null ? originsCleaned : placesCleaned;
  }

  /**
   * Human-readable allergen list for display only. Prefers the structured allergens_tags (light
   * formatting: strips the "en:"-style language prefix, replaces separators with spaces), falls
   * back to the raw allergens text. Never adds/removes/infers allergens beyond what OFF returned
   * - this is informational text, not a safety check.
   */
  @Nullable
  public String getAllergensInfo() {
    List<String> tags = getAllergensTags();
    if (!tags.isEmpty()) {
      List<String> formatted = new ArrayList<>();
      for (String tag : tags) {
        String value = formatOffTag(tag);
        if (value != null) {
          formatted.add(value);
        }
      }
      if (!formatted.isEmpty()) {
        return String.join(", ", formatted);
      }
    }
    return getAllergens();
  }

  @Nullable
  private static String formatOffTag(@Nullable String tag) {
    if (tag == null || tag.isBlank()) {
      return null;
    }
    String value = tag.trim();
    int colonIndex = value.indexOf(':');
    if (colonIndex >= 0 && colonIndex < value.length() - 1) {
      value = value.substring(colonIndex + 1);
    }
    value = value.replace('-', ' ').replace('_', ' ').trim();
    if (value.isEmpty()) {
      return null;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  public double getEnergy100g() {
    if (nutriments == null) {
      return 0;
    }
    if (nutriments.energyKcal100g != 0) {
      return nutriments.energyKcal100g;
    } else if (nutriments.energyKcalValue != 0) {
      return nutriments.energyKcalValue;
    } else {
      return nutriments.energyKcal;
    }
  }

  @Nullable
  public String getHumanReadableEnergy100g() {
    double energy100g = getEnergy100g();
    if (energy100g == 0 || nutriments == null) {
      return null;
    }
    return energy100g + " " + nutriments.energyKcalUnit;
  }

  @Nullable
  public String getHumanReadableEnergyServing() {
    if (nutriments == null || nutriments.energyKcalServing == 0) {
      return null;
    }
    return nutriments.energyKcalServing + " " + nutriments.energyKcalUnit;
  }

  @Nullable
  public Double getFat100g() {
    return nutriments != null ? nutriments.getFat100g() : null;
  }

  @Nullable
  public Double getSaturatedFat100g() {
    return nutriments != null ? nutriments.getSaturatedFat100g() : null;
  }

  @Nullable
  public Double getCarbohydrates100g() {
    return nutriments != null ? nutriments.getCarbohydrates100g() : null;
  }

  @Nullable
  public Double getSugars100g() {
    return nutriments != null ? nutriments.getSugars100g() : null;
  }

  @Nullable
  public Double getProteins100g() {
    return nutriments != null ? nutriments.getProteins100g() : null;
  }

  @Nullable
  public Double getSalt100g() {
    return nutriments != null ? nutriments.getSalt100g() : null;
  }

  @NonNull
  public List<String> getPackagingShapesTags() {
    return packagingShapesTags != null ? packagingShapesTags : Collections.emptyList();
  }

  @NonNull
  public List<String> getPackagingTags() {
    return packagingTags != null ? packagingTags : Collections.emptyList();
  }

  /**
   * The single unambiguous primary packaging type detected from OFF's structured packaging
   * tags (see {@link xyz.zedler.patrick.grocy.util.OffPackagingUtil}), or null if OFF's data
   * doesn't allow a confident, unique determination.
   */
  @Nullable
  public String getDetectedPackagingType() {
    return OffPackagingUtil.detectPrimaryPackaging(getPackagingShapesTags(), getPackagingTags());
  }

  public static class OpenFoodFactsNutriments {
    @SerializedName("energy-kcal")
    private double energyKcal;

    @SerializedName("energy-kcal_100g")
    private double energyKcal100g;

    @SerializedName("energy-kcal_serving")
    private double energyKcalServing;

    @SerializedName("energy-kcal_unit")
    private String energyKcalUnit;

    @SerializedName("energy-kcal_value")
    private double energyKcalValue;

    @Nullable
    @SerializedName("fat_100g")
    private Double fat100g;

    @Nullable
    @SerializedName("saturated-fat_100g")
    private Double saturatedFat100g;

    @Nullable
    @SerializedName("carbohydrates_100g")
    private Double carbohydrates100g;

    @Nullable
    @SerializedName("sugars_100g")
    private Double sugars100g;

    @Nullable
    @SerializedName("proteins_100g")
    private Double proteins100g;

    @Nullable
    @SerializedName("salt_100g")
    private Double salt100g;

    @Nullable
    public Double getFat100g() {
      return fat100g;
    }

    @Nullable
    public Double getSaturatedFat100g() {
      return saturatedFat100g;
    }

    @Nullable
    public Double getCarbohydrates100g() {
      return carbohydrates100g;
    }

    @Nullable
    public Double getSugars100g() {
      return sugars100g;
    }

    @Nullable
    public Double getProteins100g() {
      return proteins100g;
    }

    @Nullable
    public Double getSalt100g() {
      return salt100g;
    }
  }

  @NonNull
  @Override
  public String toString() {
    return "OpenFoodFactsProduct(" + productName + ")";
  }

  public static void getOpenFoodFactsProduct(
      DownloadHelper dlHelper,
      String barcode,
      OnObjectResponseListener<OpenFoodFactsProduct> successListener,
      OnErrorListener errorListener
  ) {
    dlHelper.get(
        OpenFoodFactsApi.getProduct(barcode),
        response -> {
          try {
            JSONObject jsonObject = new JSONObject(response);
            JSONObject jsonProduct = jsonObject.getJSONObject("product");
            Type type = new TypeToken<OpenFoodFactsProduct>(){}.getType();
            OpenFoodFactsProduct product = dlHelper.gson.fromJson(jsonProduct.toString(), type);
            product.setProductJson(jsonProduct);
            successListener.onResponse(product);
            if(dlHelper.debug) Log.i(dlHelper.tag, "getOpenFoodFactsProduct: " + product);
          } catch (Exception e) {
            // Catches JSONException (malformed envelope) as well as Gson's unchecked
            // JsonSyntaxException/IllegalStateException etc. (a field OFF is not type-stable
            // on, e.g. an array field arriving as a scalar) - OFF's response structure is never
            // trusted blindly, so any parse failure must go through the existing error path
            // instead of crashing the Volley callback on the main thread.
            if(dlHelper.debug) Log.e(dlHelper.tag, "getOpenFoodFactsProduct: " + e);
            errorListener.onError(null);
          }
        },
        error -> {
          if(dlHelper.debug) Log.e(dlHelper.tag, "getOpenFoodFactsProduct: "
              + "can't get OpenFoodFacts product");
          errorListener.onError(error);
        },
        OpenFoodFactsApi.getUserAgent(dlHelper.application)
    );
  }
}
