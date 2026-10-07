package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraft.world.level.block.state.BlockState;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;
import org.jetbrains.annotations.Nullable;

public class KeyboardBlockEntity extends BlockEntity {
    private BlockPos linkedPos = null;
    private BlockSide linkedSide = null;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.KEYBOARD_BLOCK_ENTITY, pos, state);
    }

    public void setLinked(BlockPos pos, BlockSide side) {
        setLinkedLocal(pos, side);
        KeyboardBlockEntity pair = getPair();
        if (pair != null) pair.setLinkedLocal(pos, side);
    }

    private void setLinkedLocal(BlockPos pos, BlockSide side) {
        this.linkedPos = pos;
        this.linkedSide = side;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clearLinked() {
        clearLinkedLocal();
        KeyboardBlockEntity pair = getPair();
        if (pair != null) pair.clearLinkedLocal();
    }

    private void clearLinkedLocal() {
        this.linkedPos = null;
        this.linkedSide = null;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private KeyboardBlockEntity getPair() {
        if (level == null) return null;
        BlockState state = getBlockState();
        BlockPos pairPos;
        if (state.is(WDRegistries.KEYBOARD_LEFT)) {
            pairPos = worldPosition.relative(state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING).getClockWise());
        } else if (state.is(WDRegistries.KEYBOARD_RIGHT)) {
            pairPos = worldPosition.relative(state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING).getCounterClockWise());
        } else {
            return null;
        }
        BlockState pairState = level.getBlockState(pairPos);
        boolean expectedPair = state.is(WDRegistries.KEYBOARD_LEFT)
                ? pairState.is(WDRegistries.KEYBOARD_RIGHT)
                : pairState.is(WDRegistries.KEYBOARD_LEFT);
        if (!expectedPair || pairState.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)
                != state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
            return null;
        }
        BlockEntity pair = level.getBlockEntity(pairPos);
        return pair instanceof KeyboardBlockEntity keyboard ? keyboard : null;
    }

    public @Nullable BlockPos getLinkedPos() { return linkedPos; }
    public @Nullable BlockSide getLinkedSide() { return linkedSide; }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (linkedPos != null) {
            tag.putInt("lx", linkedPos.getX());
            tag.putInt("ly", linkedPos.getY());
            tag.putInt("lz", linkedPos.getZ());
            tag.putInt("ls", linkedSide.id);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("lx")) {
            linkedPos = new BlockPos(tag.getInt("lx"), tag.getInt("ly"), tag.getInt("lz"));
            linkedSide = BlockSide.fromInt(tag.getInt("ls"));
        } else {
            linkedPos = null;
            linkedSide = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
