package org.ratden.skavenblight.magic.potion;

/**
 * Handles the skill-check and outcome calculations for WFRP alchemical potion brewing.
 * Brewing success depends on Alchemist Skill, Lab Quality modifiers, and Potion Creation Number.
 */
public final class AlchemicalBrewingResolver {

    private AlchemicalBrewingResolver() {}

    public enum LabQuality {
        POOR(-20, "Poor Quality (Impending disaster)"),
        GOOD(0, "Good Quality (Standard Order)"),
        BEST(20, "Best Quality (Alchemist's Dream)");

        private final int modifier;
        private final String displayName;

        LabQuality(int modifier, String displayName) {
            this.modifier = modifier;
            this.displayName = displayName;
        }

        public int getModifier() {
            return modifier;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public record BrewingResult(
            boolean success,
            int degreesOfSuccess,
            boolean isDisaster,
            String message
    ) {}

    /**
     * Resolves a brewing attempt.
     * success = skill + labModifier - creationNumber >= roll.
     * If failed, and roll is within the potion's volatility range, a Brewing Disaster occurs!
     */
    public static BrewingResult resolve(
            int alchemistSkill,
            LabQuality labQuality,
            AlchemicalPotion potion,
            int roll
    ) {
        int totalTarget = alchemistSkill + labQuality.getModifier() - potion.creationNumber();
        boolean success = roll <= totalTarget;

        int degreesOfSuccess = 0;
        boolean isDisaster = false;
        String message;

        if (success) {
            degreesOfSuccess = Math.max(1, (totalTarget - roll) / 10 + 1);
            message = "Brewing succeeded! Produced " + degreesOfSuccess + " potions.";
        } else {
            // Check for disaster: if the failure margin or the roll itself is within potion volatility
            isDisaster = roll >= (100 - potion.volatility());
            if (isDisaster) {
                message = "Brewing Disaster! The alchemical laboratory is filled with toxic gas or explodes!";
            } else {
                message = "Brewing failed. The ingredients sludged and were ruined.";
            }
        }

        return new BrewingResult(success, degreesOfSuccess, isDisaster, message);
    }
}
