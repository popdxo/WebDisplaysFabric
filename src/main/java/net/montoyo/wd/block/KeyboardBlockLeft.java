package net.montoyo.wd.block;


import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.item.ItemLinker;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;

public class KeyboardBlockLeft extends HorizontalDirectionalBlock implements EntityBlock {
    private static final VoxelShape SHAPE = Shapes.box(0.0, 0.0, 3.0 / 16.0, 16.0 / 16.0, 2.0 / 16.0, 16.0 / 16.0);

    public KeyboardBlockLeft(Properties properties) {
        super(properties);
    }


    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new KeyboardBlockEntity(pos, state);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos otherPos = pairedPos(pos, state);
            if (level.getBlockState(otherPos).getBlock() == WDRegistries.KEYBOARD_RIGHT) {
                level.setBlock(otherPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    static BlockPos pairedPos(BlockPos pos, BlockState state) {
        return pos.relative(state.getValue(FACING).getClockWise());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() == WDRegistries.LINKER) {
            if (level.getBlockEntity(pos) instanceof KeyboardBlockEntity kb) {
                BlockPos screenPos = ItemLinker.getLinkedScreen(player);
                BlockSide screenSide = ItemLinker.getLinkedSide(player);
                if (screenPos != null && screenSide != null) {
                    kb.setLinked(screenPos, screenSide);
                    ItemLinker.clearLink(player);
                    if (!level.isClientSide()) {
                        player.displayClientMessage(Component.literal("Keyboard linked to screen at " + screenPos.toShortString()), true);
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        // Treat the keyboard as a normal interactable block: don't let held blocks
        // replace or be placed against it unless the player explicitly crouches.
        return player.isCrouching() ? InteractionResult.PASS : InteractionResult.sidedSuccess(level.isClientSide);
    }


}