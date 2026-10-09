package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.montoyo.wd.entity.WorldBookmarks;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.stream.HybridSessionManager;
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
        HybridSignalRelay.register();
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
                ScreenData actionScreen = screen.getScreen(side);
                boolean displayOwner = actionScreen != null && (actionScreen.ownerUuid != null
                        ? player.getUUID().toString().equals(actionScreen.ownerUuid)
                        : player.getName().getString().equals(actionScreen.owner));
                if (actionScreen != null && actionScreen.hybridMode && !displayOwner
                        && !ScreenActionPayload.ACTION_MEDIA_STATE.equals(payload.action())
                        && !ScreenActionPayload.ACTION_CLAIM_OWNER.equals(payload.action())
                        && !ScreenActionPayload.ACTION_REQUEST_HYBRID_VIEW.equals(payload.action())) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "Only the display owner can control this Hybrid display."), true);
                    return;
                }
                if (actionScreen != null && (actionScreen.soloMode || actionScreen.hybridMode) && (ScreenActionPayload.ACTION_SET_URL.equals(payload.action())
                        || ScreenActionPayload.ACTION_SYNC_TAB_URL.equals(payload.action())
                        || ScreenActionPayload.ACTION_ADD_TAB.equals(payload.action())
                        || ScreenActionPayload.ACTION_SELECT_TAB.equals(payload.action())
                        || ScreenActionPayload.ACTION_CLOSE_TAB.equals(payload.action())
                        || ScreenActionPayload.ACTION_MEDIA_STATE.equals(payload.action()))) {
                    return; // Solo and Hybrid displays share no tab/URL/media state through the server
                }
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
                                ScreenData created = screen.getScreen(side);
                                if (created != null) created.ownerUuid = player.getUUID().toString();
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
                        ScreenData data = screen.getScreen(side);
                        if (data != null && !data.hybridMode && payload.extraData().length() <= 2048) {
                            try {
                                data.setTabUrl(data.activeTab(), ScreenBlockEntity.url(payload.extraData()));
                                screen.setScreenURL(side, payload.extraData());
                            } catch (java.io.IOException ignored) {}
                        }
                    }
                    case ScreenActionPayload.ACTION_SYNC_TAB_URL -> {
                        ScreenData data = screen.getScreen(side);
                        String[] parts = payload.extraData().split("\\n", 2);
                        if (data != null && !data.hybridMode && parts.length == 2 && parts[1].length() <= 2048) {
                            try {
                                int tabIndex = Integer.parseInt(parts[0]);
                                String normalized = ScreenBlockEntity.url(parts[1]);
                                long now = System.currentTimeMillis();
                                if (tabIndex >= 0 && tabIndex < data.tabUrls.size()
                                        && !normalized.equals(data.tabUrls.get(tabIndex))
                                        && now - data.lastServerUrlSyncTime >= 250) {
                                    data.lastServerUrlSyncTime = now;
                                    data.setTabUrl(tabIndex, normalized);
                                    if (tabIndex == data.activeTab()) data.url = normalized;
                                    screen.setChanged();
                                    level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()), 3);
                                }
                            } catch (NumberFormatException | java.io.IOException ignored) {}
                        }
                    }
                    case ScreenActionPayload.ACTION_MEDIA_STATE -> {
                        ScreenData data = screen.getScreen(side);
                        String[] parts = payload.extraData().split(",", 4);
                        if (data != null && parts.length == 4) {
                            try {
                                int tabIndex = Integer.parseInt(parts[0]);
                                double time = Math.max(0, Math.min(8640000, Double.parseDouble(parts[1])));
                                boolean playing = Boolean.parseBoolean(parts[2]);
                                String event = parts[3];
                                boolean isOwner = data.ownerUuid != null
                                        ? player.getUUID().toString().equals(data.ownerUuid)
                                        : player.getName().getString().equals(data.owner);
                                if (tabIndex == data.activeTab() && (isOwner
                                        || "play".equals(event) || "pause".equals(event))) {
                                    if (isOwner && !data.mediaServerAcceptedLogged) {
                                        data.mediaServerAcceptedLogged = true;
                                        Log.info("Accepted owner media sync from {} for display {} side {} tab {}",
                                                player.getName().getString(), payload.pos(), side, tabIndex);
                                    }
                                    if (!isOwner && !"play".equals(event) && !"pause".equals(event)) {
                                        return;
                                    }
                                    long now = System.currentTimeMillis();
                                    if (isOwner) {
                                        data.mediaTime = time;
                                    } else {
                                        double elapsed = Math.max(0, (now - data.mediaUpdatedAt) / 1000.0);
                                        data.mediaTime += data.mediaPlaying ? elapsed : 0;
                                    }
                                    data.mediaPlaying = playing;
                                    data.mediaUpdatedAt = now;
                                    data.mediaRevision++;
                                    screen.setChanged();
                                    level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()), 3);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case ScreenActionPayload.ACTION_ADD_TAB -> {
                        ScreenData data = screen.getScreen(side);
                        if (data != null) {
                            data.addTabState();
                            data.url = "about:blank";
                            screen.setChanged();
                            level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()), 3);
                        }
                    }
                    case ScreenActionPayload.ACTION_SELECT_TAB -> {
                        ScreenData data = screen.getScreen(side);
                        try {
                            if (data != null) {
                                data.setActiveTabIndex(Integer.parseInt(payload.extraData()));
                                data.url = data.tabUrls.get(data.activeTab());
                                screen.setChanged();
                                level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()), 3);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    case ScreenActionPayload.ACTION_CLOSE_TAB -> {
                        ScreenData data = screen.getScreen(side);
                        try {
                            if (data != null && data.removeTabState(Integer.parseInt(payload.extraData()))) {
                                data.url = data.tabUrls.get(data.activeTab());
                                screen.setChanged();
                                level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()), level.getBlockState(payload.pos()), 3);
                            }
                        } catch (NumberFormatException ignored) {}
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
                    case ScreenActionPayload.ACTION_REQUEST_HYBRID_SESSION -> {
                        ScreenData data = screen.getScreen(side);
                        boolean isOwner = data != null && (data.ownerUuid != null
                                ? player.getUUID().toString().equals(data.ownerUuid)
                                : player.getName().getString().equals(data.owner));
                        if (!isOwner || !data.hybridMode) return;
                        startHybridSession(server, player, payload.pos(), side, data);
                    }
                    case ScreenActionPayload.ACTION_CURSOR -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || !data.hybridMode || !displayOwner
                                || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
                        boolean visible = !"off".equals(payload.extraData());
                        float cx = 0, cy = 0, cz = 0;
                        if (visible) {
                            String[] parts = payload.extraData().split(",");
                            if (parts.length != 3) return;
                            try {
                                cx = Float.parseFloat(parts[0]);
                                cy = Float.parseFloat(parts[1]);
                                cz = Float.parseFloat(parts[2]);
                            } catch (NumberFormatException e) {
                                return;
                            }
                            if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(cz)
                                    || Math.abs(cx) > 256 || Math.abs(cy) > 256 || Math.abs(cz) > 256) return;
                        }
                        for (ServerPlayer watcher : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(serverLevel, payload.pos())) {
                            if (watcher.getUUID().equals(player.getUUID())) continue;
                            FriendlyByteBuf cursorBuf = PacketByteBufs.create();
                            cursorBuf.writeBlockPos(payload.pos());
                            cursorBuf.writeVarInt(side.id);
                            cursorBuf.writeBoolean(visible);
                            cursorBuf.writeFloat(cx);
                            cursorBuf.writeFloat(cy);
                            cursorBuf.writeFloat(cz);
                            ServerPlayNetworking.send(watcher, ScreenActionPayload.CURSOR_SYNC, cursorBuf);
                        }
                    }
                    case ScreenActionPayload.ACTION_CLAIM_OWNER -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null) return;
                        // Vacant = no owner, or the recorded owner is offline (covers displays that were unloaded
                        // when their owner left).
                        boolean vacant = (data.ownerUuid == null && data.owner == null)
                                || (data.ownerUuid != null
                                    ? server.getPlayerList().getPlayer(java.util.UUID.fromString(data.ownerUuid)) == null
                                    : server.getPlayerList().getPlayerByName(data.owner) == null);
                        if (vacant && !displayOwner) {
                            data.owner = player.getName().getString();
                            data.ownerUuid = player.getUUID().toString();
                            data.hybridSessionId = null;
                            data.hybridViewerToken = null;
                            if (data.hybridMode) screen.setMode(side, "sync");
                            screen.setChanged();
                            level.sendBlockUpdated(payload.pos(), level.getBlockState(payload.pos()),
                                    level.getBlockState(payload.pos()), 3);
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "You are now the owner of this display."), true);
                        }
                    }
                    case ScreenActionPayload.ACTION_REQUEST_HYBRID_VIEW -> {
                        ScreenData data = screen.getScreen(side);
                        if (data == null || !data.hybridMode || data.hybridSessionId == null
                                || WebDisplays.getInstance().getHybridWebService().findSession(data.hybridSessionId) == null) return;
                        sendViewerSession(player, payload.pos(), side, data.hybridSessionId, data.hybridViewerToken);
                    }
                    case ScreenActionPayload.ACTION_SET_MODE -> {
                        ScreenData data = screen.getScreen(side);
                        boolean isOwner = data != null && (data.ownerUuid != null
                                ? player.getUUID().toString().equals(data.ownerUuid)
                                : player.getName().getString().equals(data.owner));
                        if (isOwner) {
                            String mode = payload.extraData();
                            if (!"sync".equals(mode) && !"solo".equals(mode) && !"hybrid".equals(mode)) return;
                            screen.setMode(side, mode);
                            if (!"hybrid".equals(mode)) {
                                HybridSignalRelay.forgetSession(data.hybridSessionId);
                                data.hybridSessionId = null;
                                data.hybridViewerToken = null;
                            }
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "Display mode set to " + switch (mode) {
                                        case "hybrid" -> "Hybrid (streamed from your browser)";
                                        case "solo" -> "Solo (everyone uses their own browser)";
                                        default -> "Sync";
                                    }), true);
                            if ("hybrid".equals(mode)) startHybridSession(server, player, payload.pos(), side, data);
                        } else {
                            Log.warning("Rejected display mode change from non-owner {} for display {} side {}",
                                    player.getName().getString(), payload.pos(), side);
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "Only the display owner can change its mode."), true);
                        }
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

    /** Creates a Hybrid session, hands the owner its publish token and sends every other player the viewer link. */
    private static void startHybridSession(net.minecraft.server.MinecraftServer server, ServerPlayer owner,
                                           net.minecraft.core.BlockPos pos, BlockSide side, ScreenData data) {
        HybridSignalRelay.forgetSession(data.hybridSessionId);
        HybridSessionManager.Session session = WebDisplays.getInstance().getHybridWebService()
                .createSession(pos.asLong() + ":" + side.id);
        data.hybridSessionId = session.id();
        data.hybridViewerToken = session.viewerToken();
        FriendlyByteBuf ownerBuf = PacketByteBufs.create();
        ownerBuf.writeBlockPos(pos);
        ownerBuf.writeVarInt(side.id);
        ownerBuf.writeUtf(session.id(), 128);
        ownerBuf.writeUtf(session.ownerToken(), 128);
        ownerBuf.writeUtf(session.viewerToken(), 128);
        ownerBuf.writeUtf(WebDisplays.getInstance().getHybridWebBaseUrl(), 256);
        ServerPlayNetworking.send(owner, ScreenActionPayload.HYBRID_SESSION, ownerBuf);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(owner.getUUID())) continue;
            sendViewerSession(online, pos, side, session.id(), session.viewerToken());
        }
    }

    private static void sendViewerSession(ServerPlayer target, net.minecraft.core.BlockPos pos, BlockSide side,
                                          String sessionId, String viewerToken) {
        FriendlyByteBuf viewerBuf = PacketByteBufs.create();
        viewerBuf.writeBlockPos(pos);
        viewerBuf.writeVarInt(side.id);
        viewerBuf.writeUtf(sessionId, 128);
        viewerBuf.writeUtf(viewerToken, 128);
        viewerBuf.writeUtf(WebDisplays.getInstance().getHybridWebBaseUrl(), 256);
        ServerPlayNetworking.send(target, ScreenActionPayload.HYBRID_VIEWER_SESSION, viewerBuf);
    }
}
