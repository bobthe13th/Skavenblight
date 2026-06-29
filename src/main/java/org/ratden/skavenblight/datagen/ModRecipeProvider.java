package org.ratden.skavenblight.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.ModBlocks;
import org.ratden.skavenblight.item.ModItems;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModRecipeProvider extends RecipeProvider implements IConditionBuilder {
    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        List<ItemLike> WARPSTONE_SMELTABLES = List.of(ModItems.RAW_WARPSTONE, ModBlocks.WARPSTONE_ORE, ModBlocks.WARPSTONE_ORE_DEEPSLATE);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModBlocks.BLOCK_OF_WARPSTONE.get())
                .pattern("WWW")
                .pattern("WWW")
                .pattern("WWW")
                .define('W', ModItems.RAW_WARPSTONE.get())
                .unlockedBy("has_raw_warpstone", has(ModItems.RAW_WARPSTONE)).save(recipeOutput);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModBlocks.WARP_FLUX_CONDUIT.get(), 8)
                .pattern("III")
                .pattern("GGG")
                .pattern("III")
                .define('I', Items.IRON_INGOT)
                .define('G', Items.GLASS) // Or FUSED_WARPSTONE, whatever makes sense!
                .unlockedBy(getHasName(ModItems.RAW_WARPSTONE), has(ModItems.RAW_WARPSTONE))
                .save(recipeOutput);

        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModBlocks.BASIC_WARP_FLUX_STORAGE.get())
                .pattern("III")
                .pattern("IWI")
                .pattern("III")
                .define('I', net.minecraft.world.item.Items.IRON_INGOT)
                .define('W', ModItems.FUSED_WARPSTONE.get())
                .unlockedBy("has_warpstone", has(ModItems.FUSED_WARPSTONE.get()))
                .save(recipeOutput);

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.FUSED_WARPSTONE.get(),9)
                .requires(ModBlocks.BLOCK_OF_WARPSTONE)
                .unlockedBy("has_block_of_warpstone", has(ModBlocks.BLOCK_OF_WARPSTONE)).save(recipeOutput);

        oreSmelting(recipeOutput, WARPSTONE_SMELTABLES, RecipeCategory.MISC, ModItems.FUSED_WARPSTONE.get(), 0.25f, 200, "warpstone");
        oreBlasting(recipeOutput, WARPSTONE_SMELTABLES, RecipeCategory.MISC, ModItems.FUSED_WARPSTONE.get(), 0.25f, 100, "warpstone");

        //Decoration Blocks from FUSED_WARPSTONE
        stairBuilder(ModBlocks.WARPSTONE_STAIRS.get(), Ingredient.of(ModItems.FUSED_WARPSTONE)).group("warpstone")
                .unlockedBy("has_warpstone", has(ModItems.FUSED_WARPSTONE)).save(recipeOutput);

        slab(recipeOutput, RecipeCategory.BUILDING_BLOCKS, ModBlocks.WARPSTONE_SLAB.get(), ModItems.FUSED_WARPSTONE.get());
        buttonBuilder(ModBlocks.WARPSTONE_BUTTON.get(), Ingredient.of(ModItems.FUSED_WARPSTONE.get())).group("bismuth")
                .unlockedBy("has_bismuth", has(ModItems.FUSED_WARPSTONE.get())).save(recipeOutput);
        pressurePlate(recipeOutput, ModBlocks.WARPSTONE_PRESSURE_PLATE.get(), ModItems.FUSED_WARPSTONE.get());

        fenceBuilder(ModBlocks.WARPSTONE_FENCE.get(), Ingredient.of(ModItems.FUSED_WARPSTONE.get())).group("bismuth")
                .unlockedBy("has_bismuth", has(ModItems.FUSED_WARPSTONE.get())).save(recipeOutput);
        fenceGateBuilder(ModBlocks.WARPSTONE_FENCE_GATE.get(), Ingredient.of(ModItems.FUSED_WARPSTONE.get())).group("bismuth")
                .unlockedBy("has_bismuth", has(ModItems.FUSED_WARPSTONE.get())).save(recipeOutput);
        wall(recipeOutput, RecipeCategory.BUILDING_BLOCKS, ModBlocks.WARPSTONE_WALL.get(), ModItems.FUSED_WARPSTONE.get());

        doorBuilder(ModBlocks.WARPSTONE_DOOR.get(), Ingredient.of(ModItems.FUSED_WARPSTONE.get())).group("bismuth")
                .unlockedBy("has_bismuth", has(ModItems.FUSED_WARPSTONE.get())).save(recipeOutput);
        trapdoorBuilder(ModBlocks.WARPSTONE_TRAPDOOR.get(), Ingredient.of(ModItems.FUSED_WARPSTONE.get())).group("bismuth")
                .unlockedBy("has_bismuth", has(ModItems.FUSED_WARPSTONE.get())).save(recipeOutput);
    }



    //These are required because the default method generated files directly under the minecraft folder...
    protected static void oreSmelting(RecipeOutput recipeOutput, List<ItemLike> pIngredients, RecipeCategory pCategory, ItemLike pResult,
                                      float pExperience, int pCookingTIme, String pGroup) {
        oreCooking(recipeOutput, RecipeSerializer.SMELTING_RECIPE, SmeltingRecipe::new, pIngredients, pCategory, pResult,
                pExperience, pCookingTIme, pGroup, "_from_smelting");
    }

    protected static void oreBlasting(RecipeOutput recipeOutput, List<ItemLike> pIngredients, RecipeCategory pCategory, ItemLike pResult,
                                      float pExperience, int pCookingTime, String pGroup) {
        oreCooking(recipeOutput, RecipeSerializer.BLASTING_RECIPE, BlastingRecipe::new, pIngredients, pCategory, pResult,
                pExperience, pCookingTime, pGroup, "_from_blasting");
    }

    protected static <T extends AbstractCookingRecipe> void oreCooking(RecipeOutput recipeOutput, RecipeSerializer<T> pCookingSerializer, AbstractCookingRecipe.Factory<T> factory,
                                                                       List<ItemLike> pIngredients, RecipeCategory pCategory, ItemLike pResult, float pExperience, int pCookingTime, String pGroup, String pRecipeName) {
        for(ItemLike itemlike : pIngredients) {
            SimpleCookingRecipeBuilder.generic(Ingredient.of(itemlike), pCategory, pResult, pExperience, pCookingTime, pCookingSerializer, factory).group(pGroup).unlockedBy(getHasName(itemlike), has(itemlike))
                    .save(recipeOutput, Skavenblight.MODID + ":" + getItemName(pResult) + pRecipeName + "_" + getItemName(itemlike));
        }
    }
}
