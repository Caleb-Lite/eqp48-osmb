package utils;

import com.osmb.api.item.ZoomType;
import com.osmb.api.script.Script;
import com.osmb.api.ui.overlay.BuffOverlay;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.visual.image.Image;
import com.osmb.api.visual.image.ImageSearchResult;
import com.osmb.api.visual.image.SearchableImage;

import java.util.ArrayList;
import java.util.List;

import static com.osmb.api.visual.ocr.fonts.Font.SMALL_FONT;

public class WaterskinTracker {
  private final Script script;
  private final List<BuffOverlay> waterskinOverlays = new ArrayList<>();
  private final List<WaterskinTemplate> waterskinTemplates = new ArrayList<>();
  private Integer lastWaterskinCharges = null;

  public WaterskinTracker(Script script, int[] waterskinIds) {
    this.script = script;
    if (waterskinIds == null) {
      return;
    }
    for (int itemId : waterskinIds) {
      waterskinOverlays.add(new BuffOverlay(script, itemId));
      WaterskinTemplate template = buildWaterskinTemplate(itemId);
      if (template != null) {
        waterskinTemplates.add(template);
      }
    }
  }

  public Integer getCharges() {
    Integer overlay = getWaterskinChargesFromBuffOverlay();
    if (overlay != null) {
      return overlay;
    }
    return getWaterskinChargesFromImageSearch();
  }

  private Integer getWaterskinChargesFromBuffOverlay() {
    for (BuffOverlay overlay : waterskinOverlays) {
      String text = overlay.getBuffText();
      Integer parsed = parseWaterskinBuffText(text);
      if (parsed != null) {
        lastWaterskinCharges = parsed;
        return parsed;
      }
    }
    return null;
  }

  private WaterskinTemplate buildWaterskinTemplate(int itemId) {
    try {
      Image itemImage = script.getItemManager().getItemImage(itemId, 999, ZoomType.SIZE_1, 0xFF00FF);
      if (itemImage == null) {
        return null;
      }
      itemImage = itemImage.subImage(0, 0, itemImage.getWidth() - 5, itemImage.getHeight() - 11);
      SearchableImage searchable = itemImage.toSearchableImage(new SingleThresholdComparator(25), ColorModel.RGB);
      Rectangle digitArea = new Rectangle(0, Math.max(0, itemImage.getHeight() - 12), itemImage.getWidth(), 12);
      return new WaterskinTemplate(searchable, digitArea);
    } catch (Exception e) {
      return null;
    }
  }

  private Integer getWaterskinChargesFromImageSearch() {
    try {
      for (WaterskinTemplate template : waterskinTemplates) {
        ImageSearchResult result = script.getImageAnalyzer().findLocation(template.icon());
        if (result == null) {
          continue;
        }
        Rectangle bounds = result.getBounds();
        Rectangle numberBounds = new Rectangle(
          bounds.x + template.digitArea.x,
          bounds.y + template.digitArea.y,
          template.digitArea.width,
          template.digitArea.height
        );
        String text = script.getOCR().getText(SMALL_FONT, numberBounds, new int[]{-1, -65536});
        Integer parsed = parseWaterskinBuffText(text);
        if (parsed != null) {
          lastWaterskinCharges = parsed;
          return parsed;
        }
      }
    } catch (Exception e) {
      // Swallow and fall back to null to avoid interrupting script loop
    }
    return null;
  }

  private Integer parseWaterskinBuffText(String buffText) {
    if (buffText == null || buffText.isEmpty()) {
      return null;
    }
    buffText = buffText.trim();
    for (int i = 0; i < buffText.length(); i++) {
      char c = buffText.charAt(i);
      if (Character.isDigit(c)) {
        int value = Character.getNumericValue(c);
        if (value >= 0 && value <= 4) {
          return value;
        }
      }
    }
    return null;
  }

  private static class WaterskinTemplate {
    private final SearchableImage icon;
    private final Rectangle digitArea;

    private WaterskinTemplate(SearchableImage icon, Rectangle digitArea) {
      this.icon = icon;
      this.digitArea = digitArea;
    }

    private SearchableImage icon() {
      return icon;
    }

    private Rectangle digitArea() {
      return digitArea;
    }
  }
}
