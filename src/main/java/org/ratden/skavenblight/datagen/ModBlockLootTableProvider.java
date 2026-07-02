package org.ratden.skavenblight.datagen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.item.ModItems;

import java.util.Set;

public class ModBlockLootTableProvider extends BlockLootSubProvider {
    protected ModBlockLootTableProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {

        dropSelf(ModBlocks.WARPSTONE_NEXUS.get());
        dropSelf(ModBlocks.ACTIVE_WARPSTONE_NEXUS.get());

        dropSelf(ModBlocks.BLOCK_OF_WARPSTONE.get());

        dropSelf(ModBlocks.WARPSTONE_STAIRS.get());
        add(ModBlocks.WARPSTONE_SLAB.get(),
                block -> createSlabItemTable(ModBlocks.WARPSTONE_SLAB.get()));
        dropSelf(ModBlocks.WARPSTONE_PRESSURE_PLATE.get());
        dropSelf(ModBlocks.WARPSTONE_BUTTON.get());
        dropSelf(ModBlocks.WARPSTONE_FENCE.get());
        dropSelf(ModBlocks.WARPSTONE_FENCE_GATE.get());
        dropSelf(ModBlocks.WARPSTONE_WALL.get());
        dropSelf(ModBlocks.WARPSTONE_TRAPDOOR.get());
        add(ModBlocks.WARPSTONE_DOOR.get(),
                block -> createDoorTable(ModBlocks.WARPSTONE_DOOR.get()));


        add(ModBlocks.WARPSTONE_ORE.get(),
                block -> createOreDrop(ModBlocks.WARPSTONE_ORE.get(), ModItems.RAW_WARPSTONE.get()));
        add(ModBlocks.WARPSTONE_ORE_DEEPSLATE.get(),
                block -> createMultipleOreDrops(ModBlocks.WARPSTONE_ORE_DEEPSLATE.get(), ModItems.RAW_WARPSTONE.get(), 1, 3));

        dropSelf(ModBlocks.SPAWN_TUNNEL_SMALL.get());
        dropSelf(ModBlocks.SKAVEN_TUNNEL_SOURCE.get());
        dropSelf(ModBlocks.WARP_FLUX_CONDUIT.get());

        this.add(ModBlocks.BASIC_WARP_FLUX_STORAGE.get(), block -> createSingleItemTable(block)
                .apply(net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction.copyData(
                                net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider.BLOCK_ENTITY)
                        .copy("flux", "flux")));

        dropSelf(ModBlocks.WARP_FLUX_FURNACE.get());
    }

    //Basically the createCopperOreDrops() vanilla method
    protected LootTable.Builder createMultipleOreDrops(Block pBlock, Item item, float minDrops, float maxDrops) {
        HolderLookup.RegistryLookup<Enchantment> registrylookup = this.registries.lookupOrThrow(Registries.ENCHANTMENT);
        return this.createSilkTouchDispatchTable(pBlock,
                this.applyExplosionDecay(pBlock, LootItem.lootTableItem(item)
                        .apply(SetItemCountFunction.setCount(UniformGenerator.between(minDrops, maxDrops)))
                        .apply(ApplyBonusCount.addOreBonusCount(registrylookup.getOrThrow(Enchantments.FORTUNE)))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream().map(Holder::value)::iterator;
    }
}
