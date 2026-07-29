package org.ratden.skavenblight.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.block.custom.ResearchTableBlock;
import org.ratden.skavenblight.magic.Wind;
import org.ratden.skavenblight.magic.player.ModAttachments;
import org.ratden.skavenblight.magic.player.PlayerMagicData;
import org.ratden.skavenblight.magic.spell.Spell;
import org.ratden.skavenblight.magic.spell.SpellManager;
import org.ratden.skavenblight.magic.wind.ChunkWindState;
import org.ratden.skavenblight.magic.wind.WindGridManager;
import org.ratden.skavenblight.screen.ResearchTableMenu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ResearchTableBlockEntity extends BlockEntity implements MenuProvider {

    private final ItemStackHandler catalystHandler = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    @Nullable
    private ResourceLocation targetSpellId;
    @Nullable
    private UUID researchingPlayer;
    private int progress;

    public ResearchTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESEARCH_TABLE.get(), pos, state);
    }

    /** All researchable spell ids, in a stable order the client and server can each compute
     *  independently (used both for ContainerData's "which spell is active" sync and for the
     *  vanilla clickMenuButton mechanism the Screen's "Begin Research" button uses). */
    public static List<ResourceLocation> sortedSpellIds() {
        List<ResourceLocation> ids = new ArrayList<>(SpellManager.getAll().keySet());
        Collections.sort(ids);
        return ids;
    }

    /** Flat Wind-level threshold a spell's Wind must meet locally to be researched. Phase 1 keeps
     *  this the same for every spell; per-spell scaling (e.g. by casting number) is a natural
     *  later refinement once there's more than one duration/difficulty worth distinguishing. */
    public static float requiredWindLevel(Spell spell) {
        return Config.researchWindThreshold;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ResearchTableBlockEntity be) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (be.targetSpellId != null) {
            Spell spell = SpellManager.get(be.targetSpellId);
            ServerPlayer researcher = be.researchingPlayer != null
                    ? serverLevel.getServer().getPlayerList().getPlayer(be.researchingPlayer)
                    : null;

            boolean shouldProgress = spell != null && researcher != null
                    && WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(pos)).getCurrent(spell.wind())
                    >= requiredWindLevel(spell);

            if (shouldProgress) {
                be.progress++;
                if (be.progress >= Config.researchTicksBase) {
                    be.completeResearch(researcher, spell);
                }
            } else if (be.progress > 0) {
                be.progress = Math.max(0, be.progress - 2);
            }
            be.setChanged();
        }

        boolean isLit = state.getValue(ResearchTableBlock.LIT);
        boolean shouldBeLit = be.targetSpellId != null;
        if (isLit != shouldBeLit) {
            level.setBlock(pos, state.setValue(ResearchTableBlock.LIT, shouldBeLit), 3);
        }
    }

    /** Server-side entry point for the "Begin Research" action - validates every requirement
     *  before committing to a target so the client can trust a false return means "not eligible". */
    public boolean tryStartResearch(ServerPlayer player, ResourceLocation spellId) {
        if (targetSpellId != null) {
            return false;
        }

        Spell spell = SpellManager.get(spellId);
        if (spell == null) {
            return false;
        }

        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        if (data.knownSpells().contains(spellId)) {
            return false;
        }
        if (spell.tier() > data.getTier(spell.wind())) {
            return false;
        }

        ItemStack catalyst = catalystHandler.getStackInSlot(0);
        if (spell.componentItem().isPresent() && !spell.componentItem().get().test(catalyst)) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        ChunkWindState windState = WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(worldPosition));
        if (windState.getCurrent(spell.wind()) < requiredWindLevel(spell)) {
            return false;
        }

        targetSpellId = spellId;
        researchingPlayer = player.getUUID();
        progress = 0;
        setChanged();
        return true;
    }

    private void completeResearch(ServerPlayer player, Spell spell) {
        ResourceLocation completedId = targetSpellId;
        PlayerMagicData data = player.getData(ModAttachments.PLAYER_MAGIC.get());
        player.setData(ModAttachments.PLAYER_MAGIC.get(), data.withKnownSpell(completedId));

        if (spell.componentItem().isPresent()) {
            catalystHandler.extractItem(0, 1, false);
        }

        targetSpellId = null;
        researchingPlayer = null;
        progress = 0;
        setChanged();
    }

    // --- ContainerData exposed to the Menu: [0]=progress, [1]=active spell's sorted index (-1 if
    // idle), [2..9]=current Wind level for this chunk, indexed by Wind.ordinal(). ---
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            if (index == 0) {
                return progress;
            }
            if (index == 1) {
                return targetSpellId == null ? -1 : sortedSpellIds().indexOf(targetSpellId);
            }
            int windIndex = index - 2;
            if (windIndex >= 0 && windIndex < Wind.values().length && level instanceof ServerLevel serverLevel) {
                Wind wind = Wind.values()[windIndex];
                return (int) WindGridManager.get(serverLevel).getOrCreate(new ChunkPos(worldPosition)).getCurrent(wind);
            }
            return 0;
        }

        @Override
        public void set(int index, int value) {
            if (index == 0) {
                progress = value;
            }
        }

        @Override
        public int getCount() {
            return 2 + Wind.values().length;
        }
    };

    // --- NBT ---
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Catalyst", catalystHandler.serializeNBT(registries));
        tag.putInt("Progress", progress);
        if (targetSpellId != null) {
            tag.putString("TargetSpell", targetSpellId.toString());
        }
        if (researchingPlayer != null) {
            tag.putUUID("ResearchingPlayer", researchingPlayer);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Catalyst")) {
            catalystHandler.deserializeNBT(registries, tag.getCompound("Catalyst"));
        }
        progress = tag.getInt("Progress");
        targetSpellId = tag.contains("TargetSpell") ? ResourceLocation.tryParse(tag.getString("TargetSpell")) : null;
        researchingPlayer = tag.hasUUID("ResearchingPlayer") ? tag.getUUID("ResearchingPlayer") : null;
    }

    public IItemHandler getCatalystHandler() {
        return catalystHandler;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.skavenblight.research_table");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ResearchTableMenu(id, inventory, this, this.data);
    }
}
