package net.montoyo.wd.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.montoyo.wd.registry.WDRegistries;

public class KeyboardItem extends Item {
    public KeyboardItem(Properties properties) {
        super(properties);
    }


    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || !player.isCrouching()) return InteractionResult.PASS;

        BlockPos firstPos = context.getClickedPos().relative(context.getClickedFace());
        Direction facing = player.getDirection().getOpposite();
        Direction across = facing.getClockWise();
        BlockPos secondPos = firstPos.relative(across);
        BlockState firstState = WDRegistries.KEYBOARD_LEFT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
        BlockState secondState = WDRegistries.KEYBOARD_RIGHT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);

        if (!level.getBlockState(firstPos).canBeReplaced() || !level.getBlockState(secondPos).canBeReplaced()) {
            return InteractionResult.FAIL;
        }
        if (!firstState.canSurvive(level, firstPos) || !secondState.canSurvive(level, secondPos)) {
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide) {
            level.setBlock(firstPos, firstState, 3);
            level.setBlock(secondPos, secondState, 3);
            if (player.isCreative()) return InteractionResult.CONSUME;
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
