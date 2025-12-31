package data;

import com.osmb.api.location.position.types.WorldPosition;

public final class Locations {

    private Locations() {
    }

    private static final WorldPosition UPPER_BANK_POSITION = new WorldPosition(2852, 2953, 0);
    private static final WorldPosition UPPER_MINE = new WorldPosition(2823, 2999, 0);
    private static final WorldPosition UNDERGROUND_BANK_POSITION = new WorldPosition(2842, 9383, 0);

    public static final class MiningLocation {
        private final String displayName;
        private final WorldPosition minePosition;
        private final WorldPosition bankPosition;
        private final String depositObjectName;
        private final String depositAction;
        private final int[] priorityRegions;

        public MiningLocation(String displayName, WorldPosition minePosition, WorldPosition bankPosition, String depositObjectName, String depositAction, int[] priorityRegions) {
            this.displayName = displayName;
            this.minePosition = minePosition;
            this.bankPosition = bankPosition;
            this.depositObjectName = depositObjectName;
            this.depositAction = depositAction;
            this.priorityRegions = priorityRegions;
        }

        public String displayName() {
            return displayName;
        }

        public WorldPosition minePosition() {
            return minePosition;
        }

        public WorldPosition bankPosition() {
            return bankPosition;
        }

        public String depositObjectName() {
            return depositObjectName;
        }

        public String depositAction() {
            return depositAction;
        }

        public int[] priorityRegions() {
            return priorityRegions;
        }

        public static MiningLocation fromDisplay(String name) {
            if (name != null) {
                if (UPPER.displayName().equalsIgnoreCase(name.trim())) {
                    return UPPER;
                }
                if (UNDERGROUND.displayName().equalsIgnoreCase(name.trim())) {
                    return UNDERGROUND;
                }
            }
            return UPPER;
        }
    }

    public static final MiningLocation UPPER = new MiningLocation("Upper mine", UPPER_MINE, UPPER_BANK_POSITION, "Bank Deposit Box", "Deposit", new int[]{11310});
    public static final MiningLocation UNDERGROUND = new MiningLocation("Underground mine", null, UNDERGROUND_BANK_POSITION, "Bank Deposit Chest", "Deposit", new int[]{11410});
}
