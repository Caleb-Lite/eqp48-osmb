package data;

import com.osmb.api.location.position.types.WorldPosition;

import java.util.Set;

/**
 * Shared data container for volcanic ash mining locations and constants.
 */
public final class VolcanicAshData {

    private VolcanicAshData() {
    }

    public static final String TARGET_OBJECT_NAME = "Ash pile";
    public static final String BANK_OBJECT_NAME = "Bank chest";
    public static final WorldPosition BANK_POSITION = new WorldPosition(3819, 3809, 0);

    // Whitelisted ash pile positions for optimal mining
    public static final Set<WorldPosition> WHITELISTED_ASH_PILES = Set.of(
        new WorldPosition(3800, 3767, 0),
        new WorldPosition(3789, 3769, 0),
        new WorldPosition(3794, 3773, 0),
        new WorldPosition(3781, 3774, 0),
        new WorldPosition(3810, 3772, 0),
        new WorldPosition(3789, 3757, 0)
    );

    // Region IDs to prioritize for faster lookups
    public static final int[] VOLCANIC_ASH_REGIONS = new int[]{15162, 15163};
}
