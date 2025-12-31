package data;

import com.osmb.api.item.ItemID;
import com.osmb.api.location.position.types.WorldPosition;
import utils.Webhook.MiningLocation;

import java.util.Set;

public final class SandstoneData {
  public static final String TARGET_ROCK_NAME = "Sandstone rocks";
  public static final WorldPosition GRINDER_POS = new WorldPosition(3152, 2909, 0);
  public static final WorldPosition NORTH_ANCHOR = new WorldPosition(3163, 2914, 0);
  public static final WorldPosition SOUTH_ANCHOR = new WorldPosition(3165, 2906, 0);
  public static final Set<WorldPosition> NORTH_ROCKS = Set.of(
    new WorldPosition(3163, 2915, 0),
    new WorldPosition(3164, 2914, 0)
  );
  public static final Set<WorldPosition> SOUTH_ROCKS = Set.of(
    new WorldPosition(3166, 2906, 0),
    new WorldPosition(3164, 2906, 0)
  );
  public static final int[] WATERSKIN_IDS = new int[]{
    ItemID.WATERSKIN4,
    ItemID.WATERSKIN3,
    ItemID.WATERSKIN2,
    ItemID.WATERSKIN1,
    ItemID.WATERSKIN0
  };

  private SandstoneData() {
  }

  public static WorldPosition getAnchor(MiningLocation location) {
    return location == MiningLocation.SOUTH ? SOUTH_ANCHOR : NORTH_ANCHOR;
  }

  public static Set<WorldPosition> getAllowedRocks(MiningLocation location) {
    return location == MiningLocation.SOUTH ? SOUTH_ROCKS : NORTH_ROCKS;
  }
}
