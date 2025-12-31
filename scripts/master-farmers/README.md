# Master Farmers Pickpocket Script

Automatically pickpockets Master Farmer NPCs using pixel-verified NPC detection.

## Features

- **Pixel-Verified Detection**: Uses NPCQuery with 7 unique pixel signatures to accurately identify Master Farmer NPCs
- **Smart Targeting**: Always targets the closest Master Farmer within search radius
- **Human-like Interaction**: Uses submitHumanTask for realistic interaction timing
- **Automatic Cooldown**: Prevents spam-clicking with configurable cooldown between pickpocket attempts
- **Real-time Statistics**: Tracks successful pickpockets, failures, success rate, and pickpockets/hour
- **Inventory Monitoring**: Displays inventory status on paint overlay

## Configuration

### Constants (in MasterFarmersScript.java)

```java
SEARCH_RADIUS = 10              // Search radius in tiles around player
TOLERANCE = 5                   // Pixel color tolerance
PICKPOCKET_COOLDOWN = 600       // Minimum milliseconds between attempts
```

### NPCQuery Defaults

- **minPixelMatches**: ALL 7 pixels must match (default behavior)
- **tileScaleFactor**: 0.6 (shrinks clickbox to 60% for precise clicking)
- **requireOnScreen**: true (only targets visible NPCs)
- **sortByDistance**: true (always picks closest NPC)

## Usage

1. Start the script and choose a Master Farmer location in the settings (optionally add a food ID)
2. Stand near the chosen location; the script will pickpocket nearby Master Farmers in that area
3. Monitor the paint overlay for real-time statistics
4. Script continues until stopped or inventory management is needed

## Paint Overlay

Displays:
- Successful pickpockets count
- Failed attempts count
- Success rate percentage
- Runtime (HH:MM:SS)
- Pickpockets per hour
- Inventory status (OK/FULL)

## Future Enhancements

Potential improvements:
- [ ] Automatic item dropping when inventory full
- [ ] Bank integration for storing seeds/loot
- [ ] Eat food when low HP (from getting caught)
- [ ] Region validation (only run in specific areas)
- [ ] Anti-pattern randomization (vary search radius, cooldowns)
- [ ] Blacklist positions temporarily after multiple failures

## Technical Details

**Uses**:
- NPCQuery from utility module
- SearchablePixel with HSL color model
- RectangleArea for dynamic search zones
- Canvas for paint overlay

**Detection Method**:
- Scans yellow dots on minimap within search area
- Verifies each NPC with 7-pixel signature
- Requires 100% pixel match for positive identification
- Uses 0.6 scale factor to shrink clickbox for accuracy

## Building

```bash
./gradlew :master-farmers:build
```

Output: `master-farmers/build/libs/master-farmers-1.0.jar`
