package net.montoyo.wd.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ItemLinker extends Item {

    private static class LinkData {
        BlockPos pos;
        BlockSide side;
        LinkData(BlockPos pos, BlockSide side) { this.pos = pos; this.side = side; }
    }

    private static final Map<UUID, LinkData> linkedScreens = new HashMap<>();

    public ItemLinker(Properties properties) {
        super(properties);
    }

    public static void onRightClickScreen(Player player, BlockPos screenPos, BlockSide side) {
        net.montoyo.wd.entity.ScreenBlockEntity covering =
                net.montoyo.wd.entity.ScreenBlockEntity.findCovering(player.level(), screenPos, side);
        if (covering != null) screenPos = covering.getBlockPos();
        UUID playerUUID = player.getUUID();
        linkedScreens.put(playerUUID, new LinkData(screenPos, side));
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Screen selected at " + screenPos.toShortString()),
                true);
    }

    public static BlockPos getLinkedScreen(Player player) {
        LinkData data = linkedScreens.get(player.getUUID());
        return data != null ? data.pos : null;
    }

    public static BlockSide getLinkedSide(Player player) {
        LinkData data = linkedScreens.get(player.getUUID());
        return data != null ? data.side : null;
    }

    public static void clearLink(Player player) {
        linkedScreens.remove(player.getUUID());
    }
}
