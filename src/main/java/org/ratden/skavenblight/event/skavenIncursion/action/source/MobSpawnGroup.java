package org.ratden.skavenblight.event.skavenIncursion.action.source;

import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobModifier;
import org.ratden.skavenblight.event.skavenIncursion.action.mob.MobOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MobSpawnGroup {
    private final UUID spawnGroupId;
    private final List<MobEntry> mobEntries;
    private final Set<MobModifier> modifiers;
    private final Set<MobOrder> orders;

    public MobSpawnGroup() {
        this.spawnGroupId = UUID.randomUUID();
        this.mobEntries = new ArrayList<>();
        this.modifiers = EnumSet.noneOf(MobModifier.class);
        this.orders = EnumSet.noneOf(MobOrder.class);
    }

    public UUID getSpawnGroupId() {
        return spawnGroupId;
    }

    public void addMob(
            String mobId,
            int count,
            int capacityCostPerMob,
            SourceSize minimumSourceSize
    ) {
        if (count <= 0) {
            return;
        }

        mobEntries.add(new MobEntry(
                mobId,
                count,
                capacityCostPerMob,
                minimumSourceSize
        ));
    }

    public List<MobEntry> getMobEntries() {
        return Collections.unmodifiableList(mobEntries);
    }

    public int getTotalMobCount() {
        int total = 0;

        for (MobEntry mobEntry : mobEntries) {
            total += mobEntry.getCount();
        }

        return total;
    }

    public int getTotalCapacityCost() {
        int total = 0;

        for (MobEntry mobEntry : mobEntries) {
            total += mobEntry.getTotalCapacityCost();
        }

        return total;
    }

    public SourceSize getMinimumRequiredSourceSize() {
        SourceSize requiredSize = SourceSize.SMALL;

        for (MobEntry mobEntry : mobEntries) {
            if (mobEntry.getMinimumSourceSize().ordinal() > requiredSize.ordinal()) {
                requiredSize = mobEntry.getMinimumSourceSize();
            }
        }

        return requiredSize;
    }

    public void addModifier(MobModifier modifier) {
        modifiers.add(modifier);
    }

    public void addOrder(MobOrder order) {
        orders.add(order);
    }

    public Set<MobModifier> getModifiers() {
        return Collections.unmodifiableSet(modifiers);
    }

    public Set<MobOrder> getOrders() {
        return Collections.unmodifiableSet(orders);
    }

    public boolean isEmpty() {
        return mobEntries.isEmpty();
    }

    public static class MobEntry {
        private final String mobId;
        private final int count;
        private final int capacityCostPerMob;
        private final SourceSize minimumSourceSize;

        public MobEntry(
                String mobId,
                int count,
                int capacityCostPerMob,
                SourceSize minimumSourceSize
        ) {
            this.mobId = mobId;
            this.count = count;
            this.capacityCostPerMob = capacityCostPerMob;
            this.minimumSourceSize = minimumSourceSize;
        }

        public String getMobId() {
            return mobId;
        }

        public int getCount() {
            return count;
        }

        public int getCapacityCostPerMob() {
            return capacityCostPerMob;
        }

        public int getTotalCapacityCost() {
            return count * capacityCostPerMob;
        }

        public SourceSize getMinimumSourceSize() {
            return minimumSourceSize;
        }
    }
}