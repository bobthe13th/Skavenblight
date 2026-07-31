package org.ratden.skavenblight.block.custom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;
import org.ratden.skavenblight.block.entity.WarpFluxConduitBlockEntity;
import org.ratden.skavenblight.capability.ModCapabilities;
import org.ratden.skavenblight.network.WarpFluxGridManager;

public class WarpFluxConduitBlock extends Block implements EntityBlock {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty NORTH_ACTIVE = BooleanProperty.create("north_active");
    public static final BooleanProperty SOUTH_ACTIVE = BooleanProperty.create("south_active");
    public static final BooleanProperty EAST_ACTIVE = BooleanProperty.create("east_active");
    public static final BooleanProperty WEST_ACTIVE = BooleanProperty.create("west_active");
    public static final BooleanProperty UP_ACTIVE = BooleanProperty.create("up_active");
    public static final BooleanProperty DOWN_ACTIVE = BooleanProperty.create("down_active");

    public static final net.minecraft.world.level.block.state.properties.IntegerProperty POWER_TIER = net.minecraft.world.level.block.state.properties.IntegerProperty.create("power", 0, 3);

    // 0 = Off, 1 = Pale, 2 = Medium, 3 = Strong
    // 0 = Off, 1 = Pale, 2 = Medium, 3 = Strong Glow
    public static final IntegerProperty GLOW_INTENSITY = IntegerProperty.create("glow_intensity", 0, 3);

    public WarpFluxConduitBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false)
                .setValue(SOUTH, false).setValue(WEST, false)
                .setValue(UP, false).setValue(DOWN, false)
                .setValue(GLOW_INTENSITY, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN, GLOW_INTENSITY,
                NORTH_ACTIVE, SOUTH_ACTIVE, EAST_ACTIVE, WEST_ACTIVE, UP_ACTIVE, DOWN_ACTIVE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return makeConnections(context.getLevel(), context.getClickedPos());
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);

        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            // Tell the Grid Manager a new conduit was placed
            WarpFluxGridManager manager = WarpFluxGridManager.get(serverLevel);
            manager.addConduit(serverLevel, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // Only trigger if the block is actually being destroyed/replaced by a different block
        if (!state.is(newState.getBlock())) {

            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                WarpFluxGridManager manager = WarpFluxGridManager.get(serverLevel);
                manager.removeConduit(serverLevel, pos);
            }

            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {

        // --- Logical Network Sync ---
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            WarpFluxGridManager manager = WarpFluxGridManager.get(serverLevel);
            org.ratden.skavenblight.network.WarpFluxNetwork network = manager.getNetworkAt(currentPos);

            if (network != null) {
                // Check if the neighbor block we are reacting to has our Warp Flux capability
                boolean isEndpoint = serverLevel.getCapability(ModCapabilities.WARP_FLUX, neighborPos, direction.getOpposite()) != null;

                if (isEndpoint) {
                    // Add it to the network's endpoint list! (Since it's a HashSet, duplicates are ignored)
                    network.getEndpoints().add(neighborPos);
                } else {
                    // The machine was broken or isn't a capability provider, so ensure it's removed
                    network.getEndpoints().remove(neighborPos);
                }

                manager.setDirty(); // Tell Minecraft to save the updated grid
            }
        }
        // ------------------------------------

        // ... YOUR EXISTING VISUAL CONNECTION LOGIC GOES HERE ...
        // (e.g., checking canConnectTo and returning the updated BlockState with the boolean properties)
        boolean connected = canConnectTo((Level) level, currentPos, neighborPos, direction);
        return state.setValue(getPropertyForDirection(direction), connected);
    }

    private BlockState makeConnections(Level level, BlockPos pos) {
        BlockState state = this.defaultBlockState();
        for (Direction dir : Direction.values()) {
            boolean connected = canConnectTo(level, pos, pos.relative(dir), dir);
            state = state.setValue(getPropertyForDirection(dir), connected);
        }
        return state;
    }

    private boolean canConnectTo(Level level, BlockPos currentPos, BlockPos neighborPos, Direction direction) {
        BlockState neighborState = level.getBlockState(neighborPos);

        // Connect to other conduits
        if (neighborState.getBlock() instanceof WarpFluxConduitBlock) {
            return true;
        }

        // Connect to machines with IWarpFluxStorage using our capability
        return level.getCapability(ModCapabilities.WARP_FLUX, neighborPos, direction.getOpposite()) != null;
    }

    private BooleanProperty getPropertyForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarpFluxConduitBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // We only need to tick to decay the visual glow state
        return (lvl, pos, st, be) -> {
            if (be instanceof WarpFluxConduitBlockEntity conduit) {
                conduit.tick(lvl, pos, st);
            }
        };
    }

    // Stops the block from reducing the light level that passes through it
    @Override
    public int getLightBlock(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return 0;
    }

    // Allows sunlight to shoot straight down through the block without stopping
    @Override
    public boolean propagatesSkylightDown(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return true;
    }

    // Removes the dark "ambient occlusion" shadow the game draws in the corners under the block
    @Override
    public float getShadeBrightness(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return 1.0f;
    }

    /**
     * Calculates the light emission based on the current glow intensity state.
     */
    public static int getLightEmission(BlockState state) {
        int intensity = state.getValue(GLOW_INTENSITY);
        return switch (intensity) {
            case 3 -> 7; // Strong: Max light level!
            case 2 -> 3; // Medium: Torch-level light
            case 1 -> 1;  // Pale: Dim glow
            default -> 0; // Off: Completely dark
        };
    }
}