package net.montoyo.wd.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Relays WebRTC signaling (join / offer / answer) between a Hybrid display's owner and its viewers over the
 * normal Minecraft connection, so no extra port has to be reachable. Only small JSON control messages travel here;
 * the video itself is never sent through the server.
 */
public final class HybridSignalRelay {
    private static final int MAX_JSON = 32_000;
    private static final int MAX_TRACKED_PEERS = 512;
    /** sessionId:peerId -> the viewer's player, so the owner's replies can be routed back. */
    private static final Map<String, UUID> PEERS = new LinkedHashMap<>();

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private HybridSignalRelay() {}

    private static void logOnce(String key, String message, Object... args) {
        if (LOGGED.size() > 1000) LOGGED.clear();
        if (LOGGED.add(key)) net.montoyo.wd.utilities.Log.info(message, args);
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenActionPayload.HYBRID_SIGNAL,
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    String peerId = buf.readUtf(64);
                    String sessionId = buf.readUtf(128);
                    String json = buf.readUtf(MAX_JSON);
                    server.execute(() -> relay(server, player, pos, sideOrdinal, peerId, sessionId, json));
                });
    }

    public static void forgetSession(String sessionId) {
        if (sessionId == null) return;
        synchronized (PEERS) {
            PEERS.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        }
    }

    private static void relay(MinecraftServer server, ServerPlayer sender, BlockPos pos, int sideOrdinal,
                              String peerId, String sessionId, String json) {
        if (!peerId.matches("[A-Za-z0-9_-]{8,64}") || json.length() > MAX_JSON) return;
        if (!sender.serverLevel().isLoaded(pos)) return;
        BlockEntity be = sender.serverLevel().getBlockEntity(pos);
        if (!(be instanceof ScreenBlockEntity screen)) return;
        BlockSide side = BlockSide.fromInt(sideOrdinal);
        ScreenData data = screen.getScreen(side);
        if (data == null || !data.hybridMode || sessionId == null || !sessionId.equals(data.hybridSessionId)) {
            logOnce("stale:" + sessionId + ":" + peerId, "Hybrid signal from {} dropped: display at {} is not in Hybrid "
                    + "session {} (current {})", sender.getName().getString(), pos, sessionId, data == null ? null : data.hybridSessionId);
            return;
        }

        boolean senderIsOwner = data.ownerUuid != null
                ? sender.getUUID().toString().equals(data.ownerUuid)
                : sender.getName().getString().equals(data.owner);
        String key = sessionId + ":" + peerId;
        ServerPlayer target;
        if (senderIsOwner) {
            UUID viewer;
            synchronized (PEERS) {
                viewer = PEERS.get(key);
            }
            target = viewer == null ? null : server.getPlayerList().getPlayer(viewer);
        } else {
            synchronized (PEERS) {
                PEERS.put(key, sender.getUUID());
                Iterator<String> eldest = PEERS.keySet().iterator();
                while (PEERS.size() > MAX_TRACKED_PEERS && eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
            }
            target = data.ownerUuid != null
                    ? server.getPlayerList().getPlayer(UUID.fromString(data.ownerUuid))
                    : server.getPlayerList().getPlayerByName(data.owner);
        }
        if (target == null) {
            logOnce("notarget:" + key + senderIsOwner, "Hybrid signal from {} dropped: {} is not online",
                    sender.getName().getString(), senderIsOwner ? "viewer " + peerId : "display owner");
            return;
        }
        logOnce("ok:" + key + senderIsOwner, "Hybrid signal relayed {} -> {} (peer {})",
                sender.getName().getString(), target.getName().getString(), peerId);

        FriendlyByteBuf out = PacketByteBufs.create();
        out.writeBlockPos(pos);
        out.writeVarInt(sideOrdinal);
        out.writeUtf(peerId, 64);
        out.writeUtf(sessionId, 128);
        out.writeUtf(json, MAX_JSON);
        ServerPlayNetworking.send(target, ScreenActionPayload.HYBRID_SIGNAL, out);
    }
}
