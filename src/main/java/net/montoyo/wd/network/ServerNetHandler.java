package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.montoyo.wd.entity.WorldBookmarks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class ServerNetHandler {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendBookmarks(handler.getPlayer(), WorldBookmarks.get(server)));
        ServerPlayNetworking.registerGlobalReceiver(new ResourceLocation("webdisplays", "screen_action"), (server, player, handler, buf, responseSender) -> {
            ScreenActionPayload payload = ScreenActionPayload.read(buf);
            Level level = player.serverLevel();
            if (!level.isLoaded(payload.pos())) return;

            server.execute(() -> {
                if (ScreenActionPayload.ACTION_ADD_BOOKMARK.equals(payload.action())
                        || ScreenActionPayload.ACTION_REMOVE_BOOKMARK.equals(payload.action())) {
                    WorldBookmarks bookmarks = WorldBookmarks.get(server);
                    boolean changed = ScreenActionPayload.ACTION_ADD_BOOKMARK.equals(payload.action())
                            ? bookmarks.add(payload.extraData()) : bookmarks.remove(payload.extraData());
                    if (changed) {
                        for (ServerPlayer online : server.getPlayerList().getPlayers()) sendBookmarks(online, bookmarks);
                    }
                    return;
                }
                BlockEntity be = level.getBlockEntity(payload.pos());
                if (!(be instanceof ScreenBlockEntity screen)) return;

                BlockSide side = BlockSide.fromInt(payload.sideOrdinal());
                String owner = player.getName().getString();

                switch (payload.action()) {
                    case ScreenActionPayload.ACTION_ADD_SCREEN -> {
                        if (!screen.hasScreen(side) && screen.screenCount() == 0) {
                            int bw = 2, bh = 2;
                            String[] parts = payload.extraData().split(",");
                            if (parts.length == 2) {
                                try {
                                    bw = Math.max(1, Math.min(100, Integer.parseInt(parts[0])));
                                    bh = Math.max(1, Math.min(100, Integer.parseInt(parts[1])));
                                } catch (NumberFormatException e) {}
                            }
                            Vector2i size = new Vector2i(bw, bh);
                            Vector2i res = new Vector2i(bw * 320, bh * 320);
                            if (screen.addScreen(side, res, size, owner)) {
                                screen.setChanged();
                                level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()),
                                        level.getBlockState(payload.pos()), 3);
                            } else {
                                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                        "Screen must fit on free screen blocks and cannot overlap another display."), true);
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_REMOVE_SCREEN -> {
                        screen.removeScreen(side);
                    }
                    case ScreenActionPayload.ACTION_SET_URL -> {
                        screen.setScreenURL(side, payload.extraData());
                    }

                    case ScreenActionPayload.ACTION_SET_DISPLAY_SIZE -> {
                        String[] parts = payload.extraData().split(",");
                        if (parts.length == 2) {
                            try {
                                screen.setDisplaySize(side,
                                        Math.max(1, Math.min(100, Integer.parseInt(parts[0]))),
                                        Math.max(1, Math.min(100, Integer.parseInt(parts[1]))));
                            } catch (NumberFormatException e) {
                                Log.warning("Invalid display size data: {}", payload.extraData());
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_RESOLUTION -> {
                        String[] parts = payload.extraData().split(",");
                        if (parts.length == 2) {
                            try {
                                int w = Integer.parseInt(parts[0]);
                                int h = Integer.parseInt(parts[1]);
                                int blocksWide = Math.max(1, (int) Math.ceil(w / 320.0));
                                int blocksHigh = Math.max(1, (int) Math.ceil(h / 320.0));
                                if (screen.canFitScreen(side, blocksWide, blocksHigh)) {
                                    screen.setResolution(side, new Vector2i(w, h));
                                } else {
                                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                            "Screen must fit on free screen blocks and cannot overlap another display."), true);
                                }
                            } catch (NumberFormatException e) {
                                Log.warning("Invalid resolution data: {}", payload.extraData());
                            }
                        }
                    }
                    case ScreenActionPayload.ACTION_SET_AUTO_SIZE -> {
                        screen.setAutoSize(side, Boolean.parseBoolean(payload.extraData()));
                    }
                    case ScreenActionPayload.ACTION_SET_AUTO_RESOLUTION -> {
                        screen.setAutoResolution(side, Boolean.parseBoolean(payload.extraData()));
                    }
                    case ScreenActionPayload.ACTION_SET_ROTATION -> {
                        try {
                            int rot = Integer.parseInt(payload.extraData());
                            screen.setRotation(side, Rotation.fromInt(rot));
                        } catch (NumberFormatException e) {
                            Log.warning("Invalid rotation data: {}", payload.extraData());
                        }
                    }
                }
            });
        });
    }

    private static void sendBookmarks(ServerPlayer player, WorldBookmarks bookmarks) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(bookmarks.urls().size());
        for (String url : bookmarks.urls()) buf.writeUtf(url, 2048);
        ServerPlayNetworking.send(player, ScreenActionPayload.BOOKMARK_SYNC, buf);
    }
}
