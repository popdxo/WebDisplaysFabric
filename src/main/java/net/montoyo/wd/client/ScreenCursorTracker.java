package net.montoyo.wd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector2i;
import org.joml.Vector3d;

public class ScreenCursorTracker {

    public static class CursorInfo {
        public BlockPos pos;
        public BlockSide side;
        public double localX, localY, localZ;
        public int pixelX, pixelY;
        public ScreenBlockEntity blockEntity;
        public ScreenData screenData;
    }

    private static CursorInfo currentCursor = null;
    /** The display the player is looking at, even without the mouse item (used by the keyboard item). */
    private static CursorInfo aimedCursor = null;
    /** Distance to the nearest display surface on the view ray (any display, any permission); +inf if none. */
    private static double rawAimDistance = Double.POSITIVE_INFINITY;
    /** Screen-centre ray from the last rendered frame (includes view bobbing), see {@link #setRenderRay}. */
    private static Vec3 renderRayOrigin, renderRayDir;
    private static long renderRayAt;
    private static boolean rightButtonPressed = false;
    private static long lastMoveTime = 0;
    private static boolean wasAttackDown = false;
    private static boolean cursorVisible = true;
    private static boolean leftButtonPressed = false;
    private static double lastYaw = Double.NaN, lastPitch = Double.NaN;

    public static boolean isCursorVisible() {
        return cursorVisible;
    }

    public static CursorInfo getCurrentCursor() {
        return currentCursor;
    }

    public static CursorInfo getAimedCursor() {
        return aimedCursor;
    }

    /**
     * The world ray through the centre of the screen (the crosshair) for the frame being rendered. Using it keeps
     * the cursor exactly under the crosshair while walking, when view bobbing tilts the view.
     */
    public static void setRenderRay(Vec3 origin, Vec3 direction) {
        renderRayOrigin = origin;
        renderRayDir = direction;
        renderRayAt = System.nanoTime();
    }

    /**
     * True when the block the player would hit sits behind (or is part of) a display surface. Breaking blocks
     * through a display is never allowed, with or without the mouse item.
     */
    public static boolean displayBlocksAttack(Minecraft mc) {
        if (mc.player == null || rawAimDistance == Double.POSITIVE_INFINITY) return false;
        net.minecraft.world.phys.HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
        Vec3 from = mc.gameRenderer.getMainCamera().isInitialized()
                ? mc.gameRenderer.getMainCamera().getPosition() : mc.player.getEyePosition(1.0f);
        return rawAimDistance <= hit.getLocation().distanceTo(from) + 0.1;
    }

    /** Pointing at and clicking on displays requires holding the mouse item (either hand). */
    public static boolean isHoldingMouse(Minecraft mc) {
        return mc.player != null && (mc.player.getMainHandItem().is(net.montoyo.wd.registry.WDRegistries.MOUSE_ITEM)
                || mc.player.getOffhandItem().is(net.montoyo.wd.registry.WDRegistries.MOUSE_ITEM));
    }

    public static boolean isScreenFocused() {
        return currentCursor != null && cursorVisible;
    }

    private static boolean isOwner(Minecraft mc, ScreenData data) {
        return data.ownerUuid != null
                ? mc.player.getUUID().toString().equals(data.ownerUuid)
                : mc.player.getGameProfile().getName().equals(data.owner);
    }

    // --- Hybrid: tell the server where the owner's cursor is so viewers can draw it ---
    private static long lastCursorSendAt;
    private static boolean cursorSentVisible;
    private static BlockPos cursorSentPos;
    private static BlockSide cursorSentSide;
    private static double cursorSentX, cursorSentY, cursorSentZ;

    private static void syncOwnerCursor(Minecraft mc) {
        CursorInfo c = currentCursor;
        // Share our cursor with everyone else looking at the display (pointless on Solo: their pages differ).
        boolean active = c != null && c.screenData != null && !c.screenData.soloMode;
        long now = System.currentTimeMillis();
        net.minecraft.resources.ResourceLocation channel = new net.minecraft.resources.ResourceLocation("webdisplays", "screen_action");
        if (active) {
            double dx = c.localX - cursorSentX, dy = c.localY - cursorSentY, dz = c.localZ - cursorSentZ;
            boolean moved = !cursorSentVisible || !c.pos.equals(cursorSentPos) || c.side != cursorSentSide
                    || dx * dx + dy * dy + dz * dz > 1e-6;
            // ~20 Hz while moving, plus a heartbeat so viewers don't time the cursor out while it rests.
            if (now - lastCursorSendAt >= 50 && (moved || now - lastCursorSendAt >= 500)) {
                lastCursorSendAt = now;
                cursorSentVisible = true;
                cursorSentPos = c.pos;
                cursorSentSide = c.side;
                cursorSentX = c.localX;
                cursorSentY = c.localY;
                cursorSentZ = c.localZ;
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(channel,
                        net.montoyo.wd.network.ScreenActionPayload.cursor(c.pos, c.side.id, true, c.localX, c.localY, c.localZ).toPacket());
            }
        } else if (cursorSentVisible) {
            cursorSentVisible = false;
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(channel,
                    net.montoyo.wd.network.ScreenActionPayload.cursor(cursorSentPos, cursorSentSide.id, false, 0, 0, 0).toPacket());
        }
    }

    public static void update(Minecraft mc) {
        updateInternal(mc);
        if (mc.player != null) syncOwnerCursor(mc);
    }

    private static void updateInternal(Minecraft mc) {
        if (mc.level == null || mc.player == null || !cursorVisible) {
            if (currentCursor != null) {
                if (leftButtonPressed) {
                    releaseLeftButton();
                }
                sendMouseLeave(mc);
            }
            currentCursor = null;
            aimedCursor = null;
            rawAimDistance = Double.POSITIVE_INFINITY;
            return;
        }

        float yaw = mc.player.getViewYRot(1.0f);
        float pitch = mc.player.getViewXRot(1.0f);
        lastYaw = yaw;
        lastPitch = pitch;

        // Cast from the render camera (third person, eye height changes while sneaking, etc.), not the player model.
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin;
        Vec3 look;
        if (renderRayDir != null && System.nanoTime() - renderRayAt < 100_000_000L) {
            origin = renderRayOrigin; // exact crosshair ray of the current frame (follows view bobbing)
            look = renderRayDir;
        } else if (camera.isInitialized()) {
            origin = camera.getPosition();
            org.joml.Vector3f forward = camera.getLookVector();
            look = new Vec3(forward.x(), forward.y(), forward.z());
        } else {
            origin = mc.player.getEyePosition(1.0f);
            look = mc.player.getViewVector(1.0f);
        }

        CursorInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
            if (screen.getLevel() != mc.level) continue;

            for (int i = 0; i < screen.screenCount(); i++) {
                ScreenData data = screen.getScreen(i);
                if (data == null) continue;

                BlockPos bp = screen.getBlockPos();
                double bx = bp.getX(), by = bp.getY(), bz = bp.getZ();
                float w = data.size.x, h = data.size.y;

                Vector3d N = data.side.normal;
                Vector3d R = data.side.right;
                Vector3d U = data.side.up;

                double cx, cy, cz;
                switch (data.side) {
                    case NORTH -> { cx = bx + w / 2; cy = by + h / 2; cz = bz; }
                    case SOUTH -> { cx = bx + w / 2; cy = by + h / 2; cz = bz + 1; }
                    case WEST ->  { cx = bx; cy = by + h / 2; cz = bz + w / 2; }
                    case EAST ->  { cx = bx + 1; cy = by + h / 2; cz = bz + w / 2; }
                    case BOTTOM ->{ cx = bx + w / 2; cy = by; cz = bz + h / 2; }
                    case TOP ->   { cx = bx + w / 2; cy = by + 1; cz = bz + h / 2; }
                    default -> { cx = bx; cy = by; cz = bz; }
                }

                double ox = origin.x, oy = origin.y, oz = origin.z;
                double dx = look.x, dy = look.y, dz = look.z;

                double nd = dx * N.x + dy * N.y + dz * N.z;
                if (nd >= 0) continue;

                double t = ((cx - ox) * N.x + (cy - oy) * N.y + (cz - oz) * N.z) / nd;
                if (t <= 0) continue;

                double px = ox + t * dx;
                double py = oy + t * dy;
                double pz = oz + t * dz;

                double lu = (px - cx) * R.x + (py - cy) * R.y + (pz - cz) * R.z;
                double lv = (px - cx) * U.x + (py - cy) * U.y + (pz - cz) * U.z;

                if (Math.abs(lu) > w / 2 || Math.abs(lv) > h / 2) continue;

                if (t < bestDist) {
                    bestDist = t;

                    double localX = px - bx;
                    double localY = py - by;
                    double localZ = pz - bz;

                    if (best == null) best = new CursorInfo();
                    best.pos = bp;
                    best.side = data.side;
                    best.localX = localX;
                    best.localY = localY;
                    best.localZ = localZ;
                    best.blockEntity = screen;
                    best.screenData = data;

                    Vector2i pixelPos = new Vector2i();
                    screen.hitToScreenCoords(data, localX, localY, localZ, pixelPos);
                    best.pixelX = pixelPos.x;
                    best.pixelY = pixelPos.y;
                }
            }
        }

        // `aimed`/`rawAimDistance` cover every display (the keyboard and the anti-break check need them);
        // the interactive cursor only exists for displays we may control, and only with the mouse item.
        aimedCursor = best;
        rawAimDistance = best == null ? Double.POSITIVE_INFINITY : bestDist;
        if (best != null && !ScreenInput.canControl(best.screenData)) best = null;
        if (!isHoldingMouse(mc)) best = null;

        if (best != null) {
            long now = System.currentTimeMillis();
            if (best.screenData.browser != null && (currentCursor == null ||
                currentCursor.screenData != best.screenData ||
                currentCursor.pixelX != best.pixelX || currentCursor.pixelY != best.pixelY)) {
                if (now - lastMoveTime > 16) {
                    ScreenInput.mouseMove(best.screenData, best.pos, best.side, best.pixelX, best.pixelY, false);
                    lastMoveTime = now;
                }
            }
            currentCursor = best;
        } else {
            if (currentCursor != null) {
                if (leftButtonPressed) {
                    releaseLeftButton();
                }
                if (rightButtonPressed) releaseRightButton();
                sendMouseLeave(mc);
                currentCursor = null;
            }
        }
    }

    public static void handleLeftClick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        if (currentCursor != null && currentCursor.screenData != null
                && !ScreenInput.canControl(currentCursor.screenData)) {
            if (leftButtonPressed) releaseLeftButton();
            return;
        }
        if (!cursorVisible) return;
        if (currentCursor == null || currentCursor.screenData == null || currentCursor.screenData.browser == null) return;

        boolean isDown = mc.options.keyAttack.isDown();
        
        if (isDown && !leftButtonPressed) {
            // Mouse button just pressed down
            leftButtonPressed = true;
            long now = System.currentTimeMillis();
            int clickCount = (now - currentCursor.screenData.lastClickTime < 500) ? 2 : 1;
            currentCursor.screenData.lastClickTime = now;
            ScreenInput.click(currentCursor.screenData, currentCursor.pos, currentCursor.side, currentCursor.pixelX, currentCursor.pixelY, 0, false, clickCount);
        } else if (!isDown && leftButtonPressed) {
            // Mouse button just released
            releaseLeftButton();
        }
        
        wasAttackDown = isDown || mc.player.isSpectator();
    }

    /** Right mouse button (the "use" key) while holding the mouse item and pointing at a display. */
    public static void handleRightClick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        CursorInfo cursor = currentCursor;
        boolean isDown = mc.options.keyUse.isDown();
        // A keyboard in the main hand keeps right-click for opening keyboard input, as before.
        boolean keyboardInMainHand = mc.player.getMainHandItem().is(net.montoyo.wd.registry.WDRegistries.KEYBOARD_ITEM);
        if (cursor == null || cursor.screenData == null || keyboardInMainHand) {
            if (rightButtonPressed) releaseRightButton();
            return;
        }
        if (isDown && !rightButtonPressed) {
            rightButtonPressed = true;
            ScreenInput.click(cursor.screenData, cursor.pos, cursor.side, cursor.pixelX, cursor.pixelY, 1, false, 1);
        } else if (!isDown && rightButtonPressed) {
            releaseRightButton();
        }
    }

    private static void releaseRightButton() {
        if (rightButtonPressed && currentCursor != null && currentCursor.screenData != null) {
            ScreenInput.click(currentCursor.screenData, currentCursor.pos, currentCursor.side,
                    currentCursor.pixelX, currentCursor.pixelY, 1, true, 1);
        }
        rightButtonPressed = false;
    }

    private static void releaseLeftButton() {
        if (leftButtonPressed && currentCursor != null && currentCursor.screenData != null) {
            ScreenInput.click(currentCursor.screenData, currentCursor.pos, currentCursor.side, currentCursor.pixelX, currentCursor.pixelY, 0, true, 1);
        }
        leftButtonPressed = false;
    }

    public static void adjustZoom(ScreenData data, double delta) {
        if (data == null || data.browser == null) return;
        data.zoomLevel = Math.max(0.2, Math.min(5.0, data.zoomLevel + delta));
        applyZoom(data);
    }

    public static void resetZoom(ScreenData data) {
        if (data == null || data.browser == null) return;
        data.zoomLevel = 1.0;
        applyZoom(data);
    }

    private static void applyZoom(ScreenData data) {
        int zoomPercent = (int) Math.round(data.zoomLevel * 100);
        MCEFHelper.injectJavascript(data.browser, "document.documentElement.style.zoom='" + zoomPercent + "%' ");
    }

    public static void handleScroll(double delta) {
        if (!cursorVisible) return;
        if (currentCursor == null || currentCursor.screenData == null || currentCursor.screenData.browser == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!ScreenInput.canControl(currentCursor.screenData)) return;
        if (mc.player == null) return;

        if (mc.player.isShiftKeyDown()) {
            ScreenInput.wheel(currentCursor.screenData, currentCursor.pos, currentCursor.side, currentCursor.pixelX, currentCursor.pixelY, -delta * 4);
        } else if (net.minecraft.client.gui.screens.Screen.hasControlDown() && !ScreenInput.isRemote(currentCursor.screenData)) {
            // Ctrl + scroll: zoom browser page
            adjustZoom(currentCursor.screenData, delta > 0 ? 0.1 : -0.1);
        }
    }

    public static void clear() {
        if (leftButtonPressed) releaseLeftButton();
        if (rightButtonPressed) releaseRightButton();
        if (currentCursor != null) sendMouseLeave(Minecraft.getInstance());
        currentCursor = null;
        aimedCursor = null;
        cursorVisible = true;
        leftButtonPressed = false;
        rightButtonPressed = false;
        wasAttackDown = false;
        lastMoveTime = 0;
        lastYaw = Double.NaN;
        lastPitch = Double.NaN;
    }

    public static void restart(Minecraft mc) {
        clear();
        if (mc.level != null && mc.player != null && cursorVisible) update(mc);
    }

    private static void sendMouseLeave(Minecraft mc) {
        if (currentCursor == null || currentCursor.screenData == null) return;
        ScreenInput.mouseMove(currentCursor.screenData, currentCursor.pos, currentCursor.side, -1, -1, true);
    }
}