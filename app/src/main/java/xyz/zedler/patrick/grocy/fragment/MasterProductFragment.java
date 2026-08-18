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

package xyz.zedler.patrick.grocy.fragment;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import xyz.zedler.patrick.grocy.Constants;
import xyz.zedler.patrick.grocy.Constants.ACTION;
import xyz.zedler.patrick.grocy.Constants.ARGUMENT;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.activity.MainActivity;
import xyz.zedler.patrick.grocy.behavior.SystemBarBehavior;
import xyz.zedler.patrick.grocy.databinding.FragmentMasterProductBinding;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.LocationsBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.ProductGroupsBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.StoresBottomSheet;
import xyz.zedler.patrick.grocy.helper.InfoFullscreenHelper;
import xyz.zedler.patrick.grocy.model.BottomSheetEvent;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductGroup;
import xyz.zedler.patrick.grocy.model.SnackbarMessage;
import xyz.zedler.patrick.grocy.model.Store;
import xyz.zedler.patrick.grocy.util.HapticUtil;
import xyz.zedler.patrick.grocy.util.NumUtil;
import xyz.zedler.patrick.grocy.util.PictureUtil;
import xyz.zedler.patrick.grocy.util.ResUtil;
import xyz.zedler.patrick.grocy.viewmodel.MasterProductViewModel;

public class MasterProductFragment extends BaseFragment {

  private final static String TAG = MasterProductFragment.class.getSimpleName();
  private static final String DIALOG_DELETE = "dialog_delete";

  private MainActivity activity;
  private FragmentMasterProductBinding binding;
  private MasterProductViewModel viewModel;
  private InfoFullscreenHelper infoFullscreenHelper;
  private AlertDialog dialogDelete;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      ViewGroup container,
      Bundle savedInstanceState
  ) {
    binding = FragmentMasterProductBinding.inflate(
        inflater, container, false
    );
    return binding.getRoot();
  }

  @Override
  public void onDestroyView() {
    if (dialogDelete != null) {
      // Else it throws an leak exception because the context is somehow from the activity
      dialogDelete.dismiss();
    }
    super.onDestroyView();
    binding = null;
  }

  @Override
  public void onViewCreated(@Nullable View view, @Nullable Bundle savedInstanceState) {
    activity = (MainActivity) requireActivity();
    MasterProductFragmentArgs args = MasterProductFragmentArgs
        .fromBundle(requireArguments());
    viewModel = new ViewModelProvider(this, new MasterProductViewModel
        .MasterProductViewModelFactory(activity.getApplication(), args)
    ).get(MasterProductViewModel.class);
    if (!viewModel.isActionEdit() && args.getProductName() != null) {
      // remove product name from arguments because it was filled
      // in the form during ViewModel creation
      setArguments(new MasterProductFragmentArgs.Builder(args).setProductName(null)
          .setProductId(null).setBarcode(null).setOffBrand(null).setOffBrandFull(null)
          .setOffQuantity(null).setOffImageUrl(null).setOffEnergyPer100g(null)
          .setOffIngredients(null).setOffAllergens(null).setOffNutriscore(null)
          .setOffOrigin(null).setOffNutrients(null).setOffPackagingType(null)
          .setOffPackagingMaterial(null).setOffContentAmount(null).setOffContentUnit(null)
          .setOffCategoriesTagsJoined(null)
          .build().toBundle());
    }
    binding.setActivity(activity);
    binding.setFormData(viewModel.getFormData());
    binding.setViewModel(viewModel);
    binding.setFragment(this);
    binding.setLifecycleOwner(getViewLifecycleOwner());

    viewModel.getOffImageUrlLive().observe(getViewLifecycleOwner(), imageUrl -> {
      if (imageUrl != null && !imageUrl.isBlank()) {
        PictureUtil.loadExternalPicture(binding.imageOffPicture, binding.frameOffPicture, imageUrl);
      }
    });

    // Quick packaging/content entry card: plain listener wiring (matches this codebase's
    // existing preference for listeners over data-binding adapters for chip selection state).
    binding.chipGroupQuickPackaging.setOnCheckedStateChangeListener(
        (group, checkedIds) -> viewModel.setQuickPackaging(getCheckedChipLabel(group))
    );
    binding.chipGroupQuickContentUnit.setOnCheckedStateChangeListener(
        (group, checkedIds) -> viewModel.setQuickContentUnit(getCheckedChipLabel(group))
    );
    viewModel.getQuickPackagingLive().observe(
        getViewLifecycleOwner(),
        label -> setCheckedChipByLabel(binding.chipGroupQuickPackaging, label)
    );
    viewModel.getQuickContentUnitLive().observe(
        getViewLifecycleOwner(),
        label -> setCheckedChipByLabel(binding.chipGroupQuickContentUnit, label)
    );

    // "Dieser Einkauf" section: MHD/Verbrauchsdatum type toggle - same plain-listener pattern as
    // the quick packaging/content chips above, writing directly into the shared Product object
    // (see MasterProductViewModel#setPurchaseDueDateType) so the classic Fälligkeitsdatum
    // sub-screen always reflects the same choice (task docs section U - one single source of
    // truth), never a second, parallel value.
    binding.chipGroupPurchaseDueDateType.setOnCheckedStateChangeListener((group, checkedIds) -> {
      if (checkedIds.isEmpty()) {
        return;
      }
      int type = checkedIds.get(0) == binding.chipDueDateTypeExpiration.getId() ? 2 : 1;
      viewModel.setPurchaseDueDateType(type);
    });
    viewModel.getDueDateTypeLive().observe(getViewLifecycleOwner(), type -> {
      Chip chip = type != null && type == 2
          ? binding.chipDueDateTypeExpiration : binding.chipDueDateTypeBestBefore;
      if (!chip.isChecked()) {
        chip.setChecked(true);
      }
    });

    SystemBarBehavior systemBarBehavior = new SystemBarBehavior(activity);
    systemBarBehavior.setAppBar(binding.appBar);
    systemBarBehavior.setContainer(binding.swipeMasterProductSimple);
    systemBarBehavior.setScroll(binding.scroll, binding.constraint);
    systemBarBehavior.setUp();
    activity.setSystemBarBehavior(systemBarBehavior);

    binding.toolbar.setNavigationOnClickListener(v -> activity.navUtil.navigateUp());

    binding.categoryOptional.setOnClickListener(
        v -> activity.navUtil.navigate(MasterProductFragmentDirections
            .actionMasterProductFragmentToMasterProductCatOptionalFragment(viewModel.getAction())
            .setProduct(viewModel.getFilledProduct())
            .setForceSaveWithClose(viewModel.isForceSaveWithClose())));
    binding.categoryLocation.setOnClickListener(
        v -> activity.navUtil.navigate(MasterProductFragmentDirections
            .actionMasterProductFragmentToMasterProductCatLocationFragment(viewModel.getAction())
            .setProduct(viewModel.getFilledProduct())
            .setForceSaveWithClose(viewModel.isForceSaveWithClose())));
    binding.categoryDueDate.setOnClickListener(
        v -> activity.navUtil.navigate(MasterProductFragmentDirections
            .actionMasterProductFragmentToMasterProductCatDueDateFragment(viewModel.getAction())
            .setProduct(viewModel.getFilledProduct())
            .setForceSaveWithClose(viewModel.isForceSaveWithClose())));
    binding.categoryAmount.setOnClickListener(
        v -> activity.navUtil.navigate(MasterProductFragmentDirections
            .actionMasterProductFragmentToMasterProductCatAmountFragment(viewModel.getAction())
            .setProduct(viewModel.getFilledProduct())
            .setForceSaveWithClose(viewModel.isForceSaveWithClose())));
    binding.categoryQuantityUnit.setOnClickListener(
        v -> activity.navUtil.navigate(MasterProductFragmentDirections
            .actionMasterProductFragmentToMasterProductCatQuantityUnitFragment(viewModel.getAction())
            .setProduct(viewModel.getFilledProduct())
            .setForceSaveWithClose(viewModel.isForceSaveWithClose())));
    binding.categoryBarcodes.setOnClickListener(v -> {
      if (!viewModel.isActionEdit()) {
        activity.showSnackbar(R.string.msg_save_product_first, true);
        return;
      }
      activity.navUtil.navigate(MasterProductFragmentDirections
          .actionMasterProductFragmentToMasterProductCatBarcodesFragment(viewModel.getAction())
          .setProduct(viewModel.getFilledProduct()));
    });
    binding.categoryQuConversions.setOnClickListener(v -> {
      if (!viewModel.isActionEdit()) {
        activity.showSnackbar(R.string.msg_save_product_first, true);
        return;
      }
      activity.navUtil.navigate(MasterProductFragmentDirections
          .actionMasterProductFragmentToMasterProductCatConversionsFragment(viewModel.getAction())
          .setProduct(viewModel.getFilledProduct()));
    });

    Product product = (Product) getFromThisDestinationNow(Constants.ARGUMENT.PRODUCT);
    if (product != null) {
      viewModel.setCurrentProduct(product);
      removeForThisDestination(Constants.ARGUMENT.PRODUCT);
    }

    viewModel.getEventHandler().observeEvent(getViewLifecycleOwner(), event -> {
      if (event.getType() == Event.SNACKBAR_MESSAGE) {
        activity.showSnackbar(
            ((SnackbarMessage) event).getSnackbar(activity.binding.coordinatorMain)
        );
      } else if (event.getType() == Event.NAVIGATE_UP) {
        activity.navUtil.navigateUp();
      } else if (event.getType() == Event.SET_PRODUCT_ID) {
        int id = event.getBundle().getInt(Constants.ARGUMENT.PRODUCT_ID);
        setForPreviousDestination(Constants.ARGUMENT.PRODUCT_ID, id);
        // If this screen was itself given a scanned barcode (i.e. reached via
        // ChooseProductFragment#createNewProduct, not e.g. its "copy existing product" flow,
        // which never passes one), MasterProductViewModel already made a best-effort attempt to
        // link it to the product just created (see linkScannedBarcodeAndUploadPending) and,
        // by that method's own design, never retries on failure - so this signals ChooseProduct-
        // Fragment to NOT forward the same barcode further down to a screen that would otherwise
        // try to link it again and fail on the resulting duplicate, even in the (far more common)
        // case where the first attempt already succeeded. Read from the ViewModel, not
        // args.getBarcode(): the argument is cleared right after first being read (see above),
        // so on a later onViewCreated pass - e.g. after visiting the quantity unit screen, which
        // a new product requires, and coming back to save - it would already be gone even though
        // the same ViewModel instance still remembers it.
        if (viewModel.hasScannedBarcode()) {
          setForPreviousDestination(ARGUMENT.BARCODE_ALREADY_HANDLED, true);
        }
        // Same principle as BARCODE_ALREADY_HANDLED above: if the merged "Dieser Einkauf" section
        // already booked the first purchase directly (see MasterProductViewModel#isPurchaseBooked),
        // the screen below (via ChooseProductFragment) must never prefill/offer its own purchase
        // form for the exact same delivery again.
        if (viewModel.isPurchaseBooked()) {
          setForPreviousDestination(ARGUMENT.PURCHASE_ALREADY_BOOKED, true);
        }
        if (NumUtil.isStringInt(args.getPendingProductId())) {
          setForPreviousDestination(
              ARGUMENT.PENDING_PRODUCT_ID,
              Integer.parseInt(args.getPendingProductId())
          );
        }
      } else if (event.getType() == Event.BOTTOM_SHEET) {
        BottomSheetEvent bottomSheetEvent = (BottomSheetEvent) event;
        activity.showBottomSheet(bottomSheetEvent.getBottomSheet(), event.getBundle());
      } else if (event.getType() == Event.FOCUS_INVALID_VIEWS) {
        if (binding.editTextName.getText() == null
            || binding.editTextName.getText().length() == 0) {
          activity.showKeyboard(binding.editTextName);
        }
      } else if (event.getType() == Event.TRANSACTION_SUCCESS) {
        if (args.getAction().equals(ACTION.CREATE) && viewModel.isActionEdit()) {
          activity.updateFab(
              R.drawable.ic_round_save,
              R.string.action_save_close,
              Constants.FAB.TAG.SAVE,
              savedInstanceState == null,
              () -> {
                if (!viewModel.getFormData().isNameValid()) {
                  clearInputFocus();
                  activity.showKeyboard(binding.editTextName);
                } else {
                  clearInputFocus();
                  viewModel.saveProduct(true);
                }
              }
          );
        }
      }
    });

    infoFullscreenHelper = new InfoFullscreenHelper(binding.container);
    viewModel.getInfoFullscreenLive().observe(
        getViewLifecycleOwner(),
        infoFullscreen -> infoFullscreenHelper.setInfo(infoFullscreen)
    );

    viewModel.getActionEditLive().observe(getViewLifecycleOwner(), isEdit -> activity.updateBottomAppBar(
        true,
        isEdit
            ? R.menu.menu_master_product_edit
            : R.menu.menu_master_product_create,
        menuItem -> {
          if (menuItem.getItemId() == R.id.action_delete) {
            deleteProductSafely();
            return true;
          }
          if (menuItem.getItemId() == R.id.action_save) {
            viewModel.saveProduct(true);
            return true;
          }
          return false;
        }
    ));

    String action = (String) getFromThisDestinationNow(Constants.ARGUMENT.ACTION);
    if (action != null) {
      removeForThisDestination(Constants.ARGUMENT.ACTION);
      switch (action) {
        case ACTION.SAVE_CLOSE:
          new Handler().postDelayed(() -> viewModel.saveProduct(true), 500);
          break;
        case ACTION.SAVE_NOT_CLOSE:
          new Handler().postDelayed(() -> viewModel.saveProduct(false), 500);
          break;
        case ACTION.DELETE:
          new Handler().postDelayed(this::deleteProductSafely, 500);
          break;
      }
    }

    viewModel.getFormData().getCatOptionalErrorLive().observe(
        getViewLifecycleOwner(), value -> binding.textCatOptional.setTextColor(
            ResUtil.getColor(activity, value ? R.attr.colorError : R.attr.colorOnSurface)
        )
    );
    viewModel.getFormData().getCatLocationErrorLive().observe(
        getViewLifecycleOwner(), value -> binding.textCatLocation.setTextColor(
            ResUtil.getColor(activity, value ? R.attr.colorError : R.attr.colorOnSurface)
        )
    );
    viewModel.getFormData().getCatDueDateErrorLive().observe(
        getViewLifecycleOwner(), value -> binding.textCatDueDate.setTextColor(
            ResUtil.getColor(activity, value ? R.attr.colorError : R.attr.colorOnSurface)
        )
    );
    viewModel.getFormData().getCatQuErrorLive().observe(
        getViewLifecycleOwner(), value -> binding.textCatQu.setTextColor(
            ResUtil.getColor(activity, value ? R.attr.colorError : R.attr.colorOnSurface)
        )
    );
    viewModel.getFormData().getCatAmountErrorLive().observe(
        getViewLifecycleOwner(), value -> binding.textCatAmount.setTextColor(
            ResUtil.getColor(activity, value ? R.attr.colorError : R.attr.colorOnSurface)
        )
    );

    if (savedInstanceState == null) {
      viewModel.loadFromDatabase(true);
    }

    if (savedInstanceState != null && savedInstanceState.getBoolean(DIALOG_DELETE)) {
      new Handler(Looper.getMainLooper()).postDelayed(
          this::deleteProductSafely, 1
      );
    }

    // UPDATE UI

    activity.getScrollBehavior().setNestedOverScrollFixEnabled(true);
    activity.getScrollBehavior().setUpScroll(
        binding.appBar, false, binding.scroll, false
    );
    activity.getScrollBehavior().setBottomBarVisibility(true);
    boolean showSaveWithCloseButton = viewModel.isActionEdit() || viewModel.isForceSaveWithClose();
    activity.updateFab(
        showSaveWithCloseButton ? R.drawable.ic_round_save : R.drawable.ic_round_save_as,
        showSaveWithCloseButton ? R.string.action_save : R.string.action_save_not_close,
        showSaveWithCloseButton ? Constants.FAB.TAG.SAVE : Constants.FAB.TAG.SAVE_NOT_CLOSE,
        savedInstanceState == null,
        () -> viewModel.saveProduct(showSaveWithCloseButton)
    );
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    boolean isShowing = dialogDelete != null && dialogDelete.isShowing();
    outState.putBoolean(DIALOG_DELETE, isShowing);
  }

  public void deleteProductSafely() {
    if (!viewModel.isActionEdit()) {
      return;
    }
    Product product = viewModel.getFormData().getProductLive().getValue();
    if (product == null) {
      viewModel.showErrorMessage();
      return;
    }
    dialogDelete = new MaterialAlertDialogBuilder(
        activity, R.style.ThemeOverlay_Grocy_AlertDialog_Caution
    ).setTitle(R.string.title_confirmation)
        .setMessage(
            getString(
                R.string.msg_master_delete_product,
                product.getName()
            )
        ).setPositiveButton(R.string.action_delete, (dialog, which) -> {
          (new HapticUtil(requireContext())).click();
          viewModel.deleteProduct(product.getId());
          dialog.dismiss();
        }).setNegativeButton(R.string.action_cancel, (dialog, which) ->
            (new HapticUtil(requireContext())).click())
        .create();
    dialogDelete.show();
  }

  public void clearInputFocus() {
    activity.hideKeyboard();
    binding.textInputName.clearFocus();
  }

  @Nullable
  private String getCheckedChipLabel(ChipGroup group) {
    int checkedId = group.getCheckedChipId();
    if (checkedId == View.NO_ID) {
      return null;
    }
    Chip chip = group.findViewById(checkedId);
    return chip != null ? chip.getText().toString() : null;
  }

  private void setCheckedChipByLabel(ChipGroup group, @Nullable String label) {
    if (label == null) {
      group.clearCheck();
      return;
    }
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof Chip && label.contentEquals(((Chip) child).getText())) {
        if (!((Chip) child).isChecked()) {
          ((Chip) child).setChecked(true);
        }
        return;
      }
    }
  }

  public void onQuickPackagingQuCreateClick() {
    viewModel.createQuickQuantityUnit(viewModel.getQuickPackagingLive().getValue(), qu -> {});
  }

  public void onQuickContentQuCreateClick() {
    viewModel.createQuickQuantityUnit(viewModel.getQuickContentUnitLive().getValue(), qu -> {});
  }

  /** Receives the result from {@link MasterProductViewModel#showPurchaseDueDateBottomSheet}. */
  @Override
  public void selectDueDate(String dueDate) {
    viewModel.getPurchaseDueDateLive().setValue(dueDate);
  }

  public void onRetryPurchaseClick() {
    viewModel.retryPurchase();
  }

  // Inline "Produktzuordnung"/"Geschäft" pickers on the main page (task docs section L/N) -
  // reuse the SAME bottom sheets and result-callback pattern the classic "Optionale
  // Eigenschaften"/"Standort" sub-screens already use (see MasterProductCatOptionalFragment/
  // MasterProductCatLocationFragment), but write straight into MasterProductViewModel's own
  // Product object (setProductGroup()/setLocation()/setStore()) instead of a sub-screen's own
  // separate FormData instance - never a second data holder for the same field. No inline
  // "create new" option here (unlike those sub-screens): keeps this addition minimal, creating a
  // new product group/location/store remains a classic-sub-screen-only action.

  public void showProductGroupBottomSheet() {
    List<ProductGroup> productGroups = viewModel.getProductGroups();
    if (productGroups == null) {
      viewModel.showNetworkErrorMessage(null);
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(Constants.ARGUMENT.PRODUCT_GROUPS, new ArrayList<>(productGroups));
    bundle.putBoolean(ARGUMENT.DISPLAY_EMPTY_OPTION, true);
    Product product = viewModel.getFormData().getProductLive().getValue();
    int selectedId = product != null && NumUtil.isStringInt(product.getProductGroupId())
        ? Integer.parseInt(product.getProductGroupId()) : -1;
    bundle.putInt(Constants.ARGUMENT.SELECTED_ID, selectedId);
    activity.showBottomSheet(new ProductGroupsBottomSheet(), bundle);
  }

  @Override
  public void selectProductGroup(ProductGroup productGroup) {
    viewModel.setProductGroup(productGroup);
  }

  public void showLocationBottomSheet() {
    List<Location> locations = viewModel.getLocations();
    if (locations == null) {
      viewModel.showNetworkErrorMessage(null);
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(Constants.ARGUMENT.LOCATIONS, new ArrayList<>(locations));
    Product product = viewModel.getFormData().getProductLive().getValue();
    int selectedId = product != null && NumUtil.isStringInt(product.getLocationId())
        ? Integer.parseInt(product.getLocationId()) : -1;
    bundle.putInt(Constants.ARGUMENT.SELECTED_ID, selectedId);
    activity.showBottomSheet(new LocationsBottomSheet(), bundle);
  }

  @Override
  public void selectLocation(Location location, Bundle args) {
    viewModel.setLocation(location);
  }

  public void showStoreBottomSheet() {
    List<Store> stores = viewModel.getStores();
    if (stores == null) {
      viewModel.showNetworkErrorMessage(null);
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(Constants.ARGUMENT.STORES, new ArrayList<>(stores));
    bundle.putBoolean(ARGUMENT.DISPLAY_EMPTY_OPTION, true);
    Product product = viewModel.getFormData().getProductLive().getValue();
    int selectedId = product != null && NumUtil.isStringInt(product.getStoreId())
        ? Integer.parseInt(product.getStoreId()) : -1;
    bundle.putInt(Constants.ARGUMENT.SELECTED_ID, selectedId);
    activity.showBottomSheet(new StoresBottomSheet(), bundle);
  }

  @Override
  public void selectStore(Store store) {
    viewModel.setStore(store);
  }

  // "Zusammenfassung" card (task docs section N): each row scrolls back up to the corresponding
  // field on this SAME page instead of opening another screen - nothing more, the field itself
  // is edited exactly like before, this only changes which part of the page is visible.

  public void scrollToName() {
    scrollToView(binding.editTextName);
  }

  public void scrollToPackaging() {
    scrollToView(binding.chipGroupQuickPackaging);
  }

  public void scrollToProductGroup() {
    scrollToView(binding.rowProductGroup);
  }

  public void scrollToLocation() {
    scrollToView(binding.rowLocation);
  }

  public void scrollToPurchaseAmount() {
    scrollToView(binding.editTextPurchaseAmount);
  }

  public void scrollToDueDate() {
    scrollToView(binding.chipGroupPurchaseDueDateType);
  }

  public void scrollToPrice() {
    scrollToView(binding.textInputPurchasePrice);
  }

  public void scrollToStore() {
    scrollToView(binding.rowStore);
  }

  private void scrollToView(@Nullable View target) {
    if (binding == null || target == null) {
      return;
    }
    binding.scroll.post(() -> {
      if (binding == null) {
        return;
      }
      Rect offsetRect = new Rect();
      target.getDrawingRect(offsetRect);
      binding.constraint.offsetDescendantRectToMyCoords(target, offsetRect);
      binding.scroll.smoothScrollTo(0, offsetRect.top);
    });
  }

  @Override
  public void deleteObject(int objectId) {
    viewModel.deleteProduct(objectId);
  }

  @Override
  public void updateConnectivity(boolean online) {
    if (!online == viewModel.isOffline()) {
      return;
    }
    viewModel.downloadData(false);
  }

  @NonNull
  @Override
  public String toString() {
    return TAG;
  }
}
