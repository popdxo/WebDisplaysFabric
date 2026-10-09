package net.montoyo.wd.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.montoyo.wd.network.ScreenActionPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.montoyo.wd.client.gui.GuiScreenConfig;
import net.montoyo.wd.client.gui.InputScreen;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.client.mcef.HybridFrameCapture;
import net.montoyo.wd.client.mcef.HybridViewerPage;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

public class ClientInit implements ClientModInitializer {

    private static int previousHotbarSlot = -1;
    private static boolean wasTabDown = false;
    private static long lastUrlCheckTime = 0;
    private static final long URL_CHECK_INTERVAL_MS = 100;
    private static long lastAudioUpdateTime = 0;
    private static long lastMediaSyncTime = 0;
    private static final long AUDIO_UPDATE_INTERVAL_MS = 50;
    private static final double AUDIO_FULL_VOLUME_DISTANCE = 2.0;
    private static final double AUDIO_SILENT_DISTANCE = 16.0;
    private static boolean mcefRenderingEnabled = true;
    private static boolean wasF6Down = false;
    private static boolean wasUseDown = false;
    private static KeyMapping restartCursorKey;
    private static boolean wasZoomInDown;
    private static boolean wasZoomOutDown;
    private static boolean wasZoomResetDown;
    private static boolean wasScreenOpen;
    private static final java.util.Map<String, PendingHybridViewer> PENDING_HYBRID_VIEWERS = new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingHybridViewer(BlockPos pos, BlockSide side, String sessionId, String token, String baseUrl,
                                       long expiresAt) {}

    public static void sendHybridSignal(BlockPos pos, int sideId, String sessionId, String peerId, String json) {
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeVarInt(sideId);
        buf.writeUtf(peerId, 64);
        buf.writeUtf(sessionId, 128);
        buf.writeUtf(json, 32_000);
        ClientPlayNetworking.send(ScreenActionPayload.HYBRID_SIGNAL, buf);
    }

    private static HybridFrameCapture.SignalSender signalSender(BlockPos pos, int sideId, String sessionId) {
        return (peerId, json) -> sendHybridSignal(pos, sideId, sessionId, peerId, json);
    }

    public static boolean isMCEFRenderingEnabled() {
        return mcefRenderingEnabled;
    }

    private static void tryOpenHybridViewers(Minecraft client) {
        if (client.level == null || client.player == null || PENDING_HYBRID_VIEWERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var entry : PENDING_HYBRID_VIEWERS.entrySet()) {
            PendingHybridViewer pending = entry.getValue();
            if (now >= pending.expiresAt()) {
                PENDING_HYBRID_VIEWERS.remove(entry.getKey(), pending);
                Log.warning("Timed out waiting for Hybrid display at {} side {} to load", pending.pos(), pending.side());
                continue;
            }
            BlockEntity blockEntity = client.level.getBlockEntity(pending.pos());
            if (!(blockEntity instanceof ScreenBlockEntity screen)) continue;
            ScreenData data = screen.getScreen(pending.side());
            if (data == null) continue;
            data.hybridMode = true;
            data.soloMode = false;
            // The viewer page is built into the client and signals through the Minecraft connection, so it works
            // wherever the game connection works (no extra port to expose).
            String viewerUrl = HybridViewerPage.dataUrl();
            data.hybridSessionId = pending.sessionId();
            data.hybridViewerToken = pending.token();
            data.hybridBaseUrl = pending.baseUrl();
            data.hybridSessionUrl = viewerUrl;
            data.url = viewerUrl;
            data.lastUrl = viewerUrl;
            data.lastReportedUrl = viewerUrl;
            if (data.browser == null) screen.load();
            if (data.browser == null) continue;
            // Viewers only see the owner's stream: drop their other tabs and show just the stream page.
            data.collapseToSingleTab(viewerUrl);
            final BlockPos signalPos = pending.pos();
            final int signalSide = pending.side().id;
            MCEFHelper.registerConsoleMessageListener(data.browser, "wd-hybrid-signal", message -> {
                if (message.startsWith(HybridViewerPage.LOG_PREFIX)) {
                    Log.info("Hybrid viewer page at {}: {}", signalPos, message.substring(HybridViewerPage.LOG_PREFIX.length()));
                    return;
                }
                if (!message.startsWith(HybridViewerPage.SIGNAL_PREFIX)) return;
                String[] parts = message.split("\\|", 3);
                if (parts.length != 3) return;
                // Console callbacks arrive on a CEF thread; send from the game thread.
                Minecraft.getInstance().execute(() -> {
                    if (data.hybridSessionId == null) {
                        Log.warning("Hybrid viewer signal dropped: no session for display at {}", signalPos);
                        return;
                    }
                    sendHybridSignal(signalPos, signalSide, data.hybridSessionId, parts[1], parts[2]);
                });
            });
            if (MCEFHelper.loadBrowserUrl(data.browser, viewerUrl)) {
                PENDING_HYBRID_VIEWERS.remove(entry.getKey(), pending);
                Log.info("Opened Hybrid viewer for display at {} side {}", pending.pos(), pending.side());
            }
        }
    }

    private static void openKeyboardInput(BlockPos screenPos, BlockSide screenSide,
                                          net.minecraft.world.entity.player.Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof InputScreen input && input.isFor(screenPos, screenSide)) {
            mc.setScreen(null);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: OFF"), true);
        } else {
            mc.setScreen(new InputScreen(screenPos, screenSide));
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: ON (ESC to exit)"), true);
        }
    }

    @Override
    public void onInitializeClient() {
        Log.info("WebDisplays client initializing...");
        restartCursorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.webdisplays.restart_cursor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7,
                "category.webdisplays"));

        // Leaving a world/server: the level is discarded without removing block entities, so browsers (and their
        // audio) and Hybrid streams would keep running. Tear everything down explicitly.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(() -> {
                    PENDING_HYBRID_VIEWERS.clear();
                    ScreenCursorTracker.clear();
                    ScreenBlockEntity.shutdownAllClientScreens();
                    Log.info("Closed all WebDisplays browsers after disconnect");
                }));

        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.HYBRID_SIGNAL,
                (client, handler, buf, responseSender) -> {
                    BlockPos displayPos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    String peerId = buf.readUtf(64);
                    String sessionId = buf.readUtf(128);
                    String json = buf.readUtf(32_000);
                    client.execute(() -> {
                        if (client.level == null
                                || !(client.level.getBlockEntity(displayPos) instanceof ScreenBlockEntity screen)) return;
                        ScreenData data = screen.getScreen(BlockSide.fromInt(sideOrdinal));
                        if (data == null) return;
                        if (MCEFHelper.isLocalPlayerOwner(data.owner, data.ownerUuid)) {
                            if (HybridSignalLog.once("owner-rx:" + sessionId + ":" + peerId)) {
                                Log.info("Hybrid signal from viewer {} for session {}", peerId, sessionId);
                            }
                            HybridFrameCapture.onSignal(sessionId, peerId, json); // a viewer's join/answer
                        } else if (data.browser == null || !sessionId.equals(data.hybridSessionId)) {
                            Log.warning("Hybrid offer dropped for display at {}: browser={} session {} vs {}",
                                    displayPos, data.browser != null, sessionId, data.hybridSessionId);
                        } else {
                            // The owner's offer for our viewer page.
                            Log.info("Hybrid offer received from owner for display at {}", displayPos);
                            MCEFHelper.injectJavascript(data.browser, "window.__wdSignal&&window.__wdSignal("
                                    + new com.google.gson.JsonPrimitive(json) + ")");
                        }
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.CURSOR_SYNC,
                (client, handler, buf, responseSender) -> {
                    BlockPos displayPos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    boolean visible = buf.readBoolean();
                    float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                    client.execute(() -> {
                        if (client.level == null) return;
                        if (client.level.getBlockEntity(displayPos) instanceof ScreenBlockEntity screen) {
                            ScreenData data = screen.getScreen(BlockSide.fromInt(sideOrdinal));
                            if (data == null) return;
                            data.remoteCursorVisible = visible;
                            data.remoteCursorX = x;
                            data.remoteCursorY = y;
                            data.remoteCursorZ = z;
                            data.remoteCursorAt = System.currentTimeMillis();
                        }
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.HYBRID_SESSION,
                (client, handler, buf, responseSender) -> {
                    net.minecraft.core.BlockPos displayPos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    String sessionId = buf.readUtf(128);
                    String ownerToken = buf.readUtf(128);
                    String viewerToken = buf.readUtf(128);
                    String baseUrl = buf.readUtf(256);
                    client.execute(() -> {
                        if (client.player != null && client.level != null) {
                            BlockSide side = BlockSide.fromInt(sideOrdinal);
                            BlockEntity blockEntity = client.level.getBlockEntity(displayPos);
                            if (blockEntity instanceof ScreenBlockEntity screen) {
                                ScreenData data = screen.getScreen(side);
                                if (data != null) {
                                    data.hybridMode = true;
                                    data.soloMode = false;
                                    if (data.hybridSessionId != null && !data.hybridSessionId.equals(sessionId)) {
                                        HybridFrameCapture.stopSession(data.hybridSessionId);
                                    }
                                    data.hybridSessionId = sessionId;
                                    data.hybridOwnerToken = ownerToken;
                                    data.hybridViewerToken = viewerToken;
                                    data.hybridBaseUrl = baseUrl;
                                    HybridFrameCapture.register(data.browser, baseUrl, sessionId, ownerToken,
                                            signalSender(displayPos, side.id, sessionId));
                                }
                            }
                            Log.info("Hybrid browser capture armed for session {} at {} side {}", sessionId, displayPos, side);
                            client.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "Hybrid browser capture is active for this display."), false);
                        }
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.HYBRID_VIEWER_SESSION,
                (client, handler, buf, responseSender) -> {
                    BlockPos displayPos = buf.readBlockPos();
                    int sideOrdinal = buf.readVarInt();
                    String sessionId = buf.readUtf(128);
                    String viewerToken = buf.readUtf(128);
                    String baseUrl = buf.readUtf(256);
                    client.execute(() -> {
                        BlockSide side = BlockSide.fromInt(sideOrdinal);
                        String key = displayPos.asLong() + ":" + side.id;
                        PENDING_HYBRID_VIEWERS.put(key, new PendingHybridViewer(displayPos, side, sessionId,
                                viewerToken, baseUrl, System.currentTimeMillis() + 30_000));
                        tryOpenHybridViewers(client);
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(ScreenActionPayload.BOOKMARK_SYNC, (client, handler, buf, responseSender) -> {
            int count = Math.min(100, buf.readVarInt());
            java.util.ArrayList<String> urls = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) urls.add(buf.readUtf(2048));
            client.execute(() -> ClientBookmarks.set(urls));
        });

        // Register block entity renderers
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                WDRegistries.SCREEN_BLOCK_ENTITY, ScreenRenderer::new);

        // Schedule MCEF initialization callback (uses reflection)
        MCEFHelper.scheduleInit(success -> {
            if (success) {
                Log.info("MCEF initialized successfully for WebDisplays");
            } else {
                Log.info("MCEF not available for WebDisplays");
            }
        });

        // TEMPORARILY DISABLED: Reset GL pixel store state - testing if this causes noise
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });
        // net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        //     org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        // });

        // Maintain cursor state across GUI transitions and refresh as soon as gameplay resumes.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != null && client.player != null) tryOpenHybridViewers(client);
            if (client.level == null || client.player == null) {
                ScreenCursorTracker.clear();
                wasScreenOpen = false;
                return;
            }
            boolean screenOpen = client.screen != null;
            if (wasScreenOpen && !screenOpen) ScreenCursorTracker.restart(client);
            wasScreenOpen = screenOpen;
            if (!screenOpen) ScreenCursorTracker.update(client);
        });

        // Periodically retry browser creation for screens that were created before MCEF initialized
        // Also detect page navigation and re-inject window.open override (throttled to 1/sec)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null) return;
            long now = System.currentTimeMillis();
            boolean shouldCheckUrl = (now - lastUrlCheckTime) >= URL_CHECK_INTERVAL_MS;
            if (shouldCheckUrl) lastUrlCheckTime = now;
            boolean shouldSyncMedia = client.player != null && (now - lastMediaSyncTime) >= 1000;
            if (shouldSyncMedia) lastMediaSyncTime = now;
            boolean shouldUpdateAudio = client.player != null
                    && (now - lastAudioUpdateTime) >= AUDIO_UPDATE_INTERVAL_MS;
            if (shouldUpdateAudio) lastAudioUpdateTime = now;

            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                if (screen.retryCreateBrowsers()) {
                    Log.info("Browser retry succeeded for screen at {}", screen.getBlockPos());
                }

                for (int i = 0; i < screen.screenCount(); i++) {
                    ScreenData hybridData = screen.getScreen(i);
                    if (hybridData == null || client.player == null) continue;
                    if (!hybridData.hybridMode) {
                        hybridData.hybridSeenAt = 0;
                        continue;
                    }
                    if (hybridData.hybridSeenAt == 0) hybridData.hybridSeenAt = now;
                    boolean localOwner = MCEFHelper.isLocalPlayerOwner(hybridData.owner, hybridData.ownerUuid);
                    if (localOwner) {
                        if (hybridData.hybridOwnerToken != null && hybridData.hybridBaseUrl != null) {
                            // Always stream the currently selected tab; register() moves the stream when it changes.
                            HybridFrameCapture.register(hybridData.browser, hybridData.hybridBaseUrl,
                                    hybridData.hybridSessionId, hybridData.hybridOwnerToken,
                                    signalSender(screen.getBlockPos(), hybridData.side.id, hybridData.hybridSessionId));
                        } else if (now - hybridData.hybridSeenAt > 2000 && now - hybridData.lastHybridRequestAt > 3000) {
                            hybridData.lastHybridRequestAt = now;
                            ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action"),
                                    ScreenActionPayload.requestHybridSession(screen.getBlockPos(), hybridData.side.id).toPacket());
                        }
                    } else if (hybridData.hybridSessionUrl == null && now - hybridData.hybridSeenAt > 2000
                            && now - hybridData.lastHybridRequestAt > 3000
                            && !PENDING_HYBRID_VIEWERS.containsKey(screen.getBlockPos().asLong() + ":" + hybridData.side.id)) {
                        // Joined (or reloaded) after the stream started: ask the server for the viewer link.
                        hybridData.lastHybridRequestAt = now;
                        ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action"),
                                ScreenActionPayload.requestHybridView(screen.getBlockPos(), hybridData.side.id).toPacket());
                    }
                }

                if (shouldUpdateAudio) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        Vec3 audioPoint = screen.centerPoint(data);
                        double distance = audioPoint.distanceTo(client.player.position());
                        double gain = 1.0 - Math.max(0.0, Math.min(1.0,
                                (distance - AUDIO_FULL_VOLUME_DISTANCE)
                                        / (AUDIO_SILENT_DISTANCE - AUDIO_FULL_VOLUME_DISTANCE)));
                        Vec3 toSource = audioPoint.subtract(client.player.getEyePosition(1.0f));
                        double sourceLength = Math.max(0.001, toSource.length());
                        toSource = toSource.scale(1.0 / sourceLength);
                        Vec3 view = client.player.getViewVector(1.0f);
                        Vec3 cameraRight = view.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
                        double pan = Math.max(-1.0, Math.min(1.0, toSource.dot(cameraRight)));
                        String audioScript = ScreenBlockEntity.PROXIMITY_AUDIO_JS
                                .replace("__WD_AUDIO_GAIN__", Double.toString(gain))
                                .replace("__WD_AUDIO_PAN__", Double.toString(pan));
                        MCEFHelper.injectJavascript(data.browser, audioScript);
                    }
                }
                // Detect page navigation and re-inject window.open override (throttled)
                if (shouldCheckUrl || shouldSyncMedia) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        boolean pollMedia = client.player != null && now - data.lastMediaPollTime >= 500;
                        if (pollMedia) {
                            data.lastMediaPollTime = now;
                            data.installMediaReporter();
                        }
                        boolean isOwner = client.player != null && (data.ownerUuid != null
                                ? client.player.getUUID().toString().equals(data.ownerUuid)
                                : client.player.getGameProfile().getName().equals(data.owner));

                        if (shouldSyncMedia && !data.mediaOwnerDiagnosticLogged) {
                            data.mediaOwnerDiagnosticLogged = true;
                            Log.info("Media sync owner check at {} side {}: player={} uuid={}, owner={} uuid={}, match={}",
                                    screen.getBlockPos(), data.side,
                                    client.player == null ? "<none>" : client.player.getGameProfile().getName(),
                                    client.player == null ? "<none>" : client.player.getUUID(),
                                    data.owner, data.ownerUuid, isOwner);
                        }
                        String title = pollMedia ? MCEFHelper.getBrowserTitle(data.browser) : "";
                        String mediaMessage = pollMedia ? data.latestMediaMessage : "";
                        if (pollMedia && isOwner && now - data.lastMediaTitleDiagnosticTime >= 10000) {
                            data.lastMediaTitleDiagnosticTime = now;
                            Log.info("Media browser title sample at {} side {}: player={} ownerMatch={} url='{}' title='{}'",
                                    screen.getBlockPos(), data.side,
                                    client.player.getGameProfile().getName(), isOwner,
                                    MCEFHelper.getBrowserUrl(data.browser), title);
                        }
                        if (pollMedia && mediaMessage != null && mediaMessage.startsWith("__WD_MEDIA__|")) {
                            if (!data.mediaMarkerObserved) {
                                data.mediaMarkerObserved = true;
                                Log.info("Media marker observed for display {} side {}", screen.getBlockPos(), data.side);
                            }
                        }
                        if (pollMedia && data.activeTab() >= 0 && data.tab(data.activeTab()) == data.browser
                                && mediaMessage != null && mediaMessage.startsWith("__WD_MEDIA__|")) {
                            String[] media = mediaMessage.split("\\|", 6);
                            if (media.length >= 6) {
                                String event = media[1];
                                String eventId = media[4];
                                try {
                                    double mediaTime = Double.parseDouble(media[2]);
                                    boolean playing = "1".equals(media[3]);
                                    boolean isAction = "play".equals(event) || "pause".equals(event);
                                    String eventKey = data.activeTab() + ":" + eventId;
                                    boolean shouldSend = isAction ? !eventKey.equals(data.lastMediaEvent)
                                            : isOwner && pollMedia;
                                    if (shouldSend && !data.soloMode && !data.hybridMode) {
                                        if (isAction) data.lastMediaEvent = eventKey;
                                        if (isOwner && !data.mediaPacketSentLogged) {
                                            data.mediaPacketSentLogged = true;
                                            Log.info("Sending owner media sync for display {} side {} tab {} at {}s playing={}",
                                                    screen.getBlockPos(), data.side, data.activeTab(), mediaTime, playing);
                                        }
                                        ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action"),
                                                ScreenActionPayload.mediaState(screen.getBlockPos(), data.side.id,
                                                        data.activeTab(), mediaTime, playing, event).toPacket());
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        String currentUrl = MCEFHelper.getBrowserUrl(data.browser);
                        if (!currentUrl.isEmpty() && !currentUrl.equals(data.lastUrl)) {
                            data.lastUrl = currentUrl;
                            data.mediaReporterInstalled = false;
                            data.setTabUrl(data.activeTab(), currentUrl);
                            if (!data.hybridMode && !data.soloMode && !currentUrl.equals(data.lastReportedUrl) && now >= data.urlSyncCooldownUntil) {
                                data.lastReportedUrl = currentUrl;
                                data.urlSyncCooldownUntil = now + 1500;
                                ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action"),
                                        ScreenActionPayload.setUrl(screen.getBlockPos(), data.side.id, data.activeTab(), currentUrl).toPacket());
                            }
                            ScreenBlockEntity.ensureWindowOpenOverride(data.browser);
                        }
                    }
                }
            }
        });

        // Track cursor position on screen planes via raycasting
        WorldRenderEvents.START.register(context -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.update(client);
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null || client.screen != null) {
                wasZoomInDown = wasZoomOutDown = wasZoomResetDown = false;
                return;
            }
            long window = client.getWindow().getWindow();
            boolean control = net.minecraft.client.gui.screens.Screen.hasControlDown();
            boolean zoomInDown = control && (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_EQUAL)
                    || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_ADD));
            boolean zoomOutDown = control && (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_MINUS)
                    || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_SUBTRACT));
            boolean zoomResetDown = control && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_0);
            ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
            if (cursor != null && cursor.screenData != null) {
                if (zoomInDown && !wasZoomInDown) ScreenCursorTracker.adjustZoom(cursor.screenData, 0.1);
                if (zoomOutDown && !wasZoomOutDown) ScreenCursorTracker.adjustZoom(cursor.screenData, -0.1);
                if (zoomResetDown && !wasZoomResetDown) ScreenCursorTracker.resetZoom(cursor.screenData);
            }
            wasZoomInDown = zoomInDown;
            wasZoomOutDown = zoomOutDown;
            wasZoomResetDown = zoomResetDown;
        });

        // F7 clears stale hover/button state and immediately restarts cursor raycasting.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (restartCursorKey != null && restartCursorKey.consumeClick()) {
                ScreenCursorTracker.restart(client);
                if (client.player != null) {
                    client.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("WebDisplays cursor tracking restarted"), true);
                }
            }
        });

        // Detect left-click on screen surfaces
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            ScreenCursorTracker.handleLeftClick(client);

            boolean useDown = client.options.keyUse.isDown();
            if (useDown && !wasUseDown && !client.player.isShiftKeyDown()
                    && (client.player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == WDRegistries.KEYBOARD_ITEM
                    || client.player.getItemInHand(InteractionHand.OFF_HAND).getItem() == WDRegistries.KEYBOARD_ITEM)) {
                ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
                if (cursor != null && cursor.screenData != null && (!cursor.screenData.hybridMode
                        || (cursor.screenData.ownerUuid != null
                            ? client.player.getUUID().toString().equals(cursor.screenData.ownerUuid)
                            : client.player.getGameProfile().getName().equals(cursor.screenData.owner)))) {
                    openKeyboardInput(cursor.pos, cursor.side, client.player);
                }
            }
            wasUseDown = useDown;
        });

        // Handle Shift+scroll for browser scrolling, Ctrl+scroll for zoom
        // Only intercept when Shift is held AND the hotbar slot actually changed
        // due to scroll wheel (not just pressing Shift alone)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (!ScreenCursorTracker.isScreenFocused()) {
                previousHotbarSlot = -1;
                return;
            }

            boolean isShift = client.player.isShiftKeyDown();
            boolean isCtrl = net.minecraft.client.gui.screens.Screen.hasControlDown();

            int currentSlot = client.player.getInventory().selected;

            if (previousHotbarSlot < 0) {
                // Initialize tracking - just record current slot, don't trigger
                previousHotbarSlot = currentSlot;
                return;
            }

            if (currentSlot != previousHotbarSlot) {
                // Slot changed - only convert to scroll if Shift or Ctrl was already held
                // This prevents false triggers when just pressing Shift
                if (isShift || isCtrl) {
                    int delta = currentSlot - previousHotbarSlot;
                    if (delta > 4) delta -= 9;
                    else if (delta < -4) delta += 9;
                    // Only handle single-step scroll wheel changes (±1)
                    // Ignore multi-step changes (likely number key presses)
                    if (Math.abs(delta) == 1) {
                        client.player.getInventory().selected = previousHotbarSlot;
                        ScreenCursorTracker.handleScroll(delta > 0 ? 1.0 : -1.0);
                        return;
                    }
                }
                // For non-scroll changes or non-shift states, just update tracking
                previousHotbarSlot = currentSlot;
            }
        });

        // Toggle cursor visibility with Tab key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isTabDown = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_TAB);
            if (isTabDown && !wasTabDown) {
                ScreenCursorTracker.toggleCursorVisible();
            }
            wasTabDown = isTabDown;
        });

        // Toggle MCEF screen rendering with F6 key
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            boolean isF6Down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    client.getWindow().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_F6);
            if (isF6Down && !wasF6Down) {
                mcefRenderingEnabled = !mcefRenderingEnabled;
                client.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "WebDisplays 渲染: " + (mcefRenderingEnabled ? "开启" : "关闭")),
                        true);
            }
            wasF6Down = isF6Down;
        });

        // Activate a linked keyboard from the display surface before display clicks are consumed.

        // While the display cursor is active, clicks belong to the web page.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER || world.isClientSide()) {
                if (world.isClientSide()) ScreenCursorTracker.update(Minecraft.getInstance());
            }
            if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
            return ScreenCursorTracker.isScreenFocused() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        // Prevent right-clicking blocks behind/under the active display surface. Keep the
        // configurator available on the actual display block itself.

        // Open config GUI when using configurator on screen (client-side only)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            ScreenCursorTracker.update(Minecraft.getInstance());

            if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof ScreenBlockEntity screen) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.CONFIGURATOR) {
                    BlockPos pos = hitResult.getBlockPos();
                    BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                    ScreenData configured = screen.getScreen(side);
                    if (configured != null && !MCEFHelper.isLocalPlayerOwner(configured.owner, configured.ownerUuid)) {
                        // The server hands the display over only if it has no (online) owner.
                        ClientPlayNetworking.send(new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action"),
                                ScreenActionPayload.claimOwner(pos, side.id).toPacket());
                    }
                    Minecraft.getInstance().setScreen(
                            new GuiScreenConfig(pos, side, !screen.hasScreen(side)));
                    return InteractionResult.SUCCESS;
                }
            }
            if (player.getItemInHand(hand).getItem() == WDRegistries.KEYBOARD_ITEM
                    && player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            return ScreenCursorTracker.isScreenFocused() ? InteractionResult.FAIL : InteractionResult.PASS;
        });


        // Open keyboard InputScreen when right-clicking keyboard blocks.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
            BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
            if (be instanceof KeyboardBlockEntity kb) {
                if (player.getItemInHand(hand).getItem() == WDRegistries.LINKER) return InteractionResult.PASS;
                BlockPos screenPos = kb.getLinkedPos();
                BlockSide screenSide = kb.getLinkedSide();
                if (screenPos != null && screenSide != null) {
                    Minecraft mc = Minecraft.getInstance();
                    BlockEntity linkedEntity = mc.level == null ? null : mc.level.getBlockEntity(screenPos);
                    if (linkedEntity instanceof ScreenBlockEntity linkedScreen) {
                        ScreenData linkedData = linkedScreen.getScreen(screenSide);
                        if (linkedData != null && linkedData.hybridMode
                                && (linkedData.ownerUuid != null
                                    ? !mc.player.getUUID().toString().equals(linkedData.ownerUuid)
                                    : !mc.player.getGameProfile().getName().equals(linkedData.owner))) {
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "Only the display owner can use this Hybrid display."), true);
                            return InteractionResult.SUCCESS;
                        }
                    }
                    if (mc.screen instanceof InputScreen && ((InputScreen) mc.screen).isFor(screenPos, screenSide)) {
                        mc.setScreen(null);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: OFF"), true);
                    } else {
                        mc.setScreen(new InputScreen(screenPos, screenSide));
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Input mode: ON (ESC to exit)"), true);
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("webdisplays.message.notLinked"), true);
                    return InteractionResult.SUCCESS;
                }
            }
            return ScreenCursorTracker.isScreenFocused() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        Log.info("WebDisplays client initialized!");
    }
}