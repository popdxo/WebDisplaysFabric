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
import net.montoyo.wd.client.mcef.MCEFHelper;
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
    private static final long URL_CHECK_INTERVAL_MS = 1000;
    private static long lastAudioUpdateTime = 0;
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

    public static boolean isMCEFRenderingEnabled() {
        return mcefRenderingEnabled;
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
            boolean shouldUpdateAudio = client.player != null
                    && (now - lastAudioUpdateTime) >= AUDIO_UPDATE_INTERVAL_MS;
            if (shouldUpdateAudio) lastAudioUpdateTime = now;

            for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
                if (screen.retryCreateBrowsers()) {
                    Log.info("Browser retry succeeded for screen at {}", screen.getBlockPos());
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
                if (shouldCheckUrl) {
                    for (int i = 0; i < screen.screenCount(); i++) {
                        ScreenData data = screen.getScreen(i);
                        if (data == null || data.browser == null) continue;
                        String currentUrl = MCEFHelper.getBrowserUrl(data.browser);
                        if (!currentUrl.isEmpty() && !currentUrl.equals(data.lastUrl)) {
                            data.lastUrl = currentUrl;
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
                if (cursor != null && cursor.screenData != null) {
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