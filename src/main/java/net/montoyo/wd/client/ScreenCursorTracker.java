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
    private static long lastMoveTime = 0;
    private static boolean wasAttackDown = false;
    private static boolean cursorVisible = true;
    private static boolean leftButtonPressed = false;
    private static double lastYaw = Double.NaN, lastPitch = Double.NaN;

    public static boolean isCursorVisible() {
        return cursorVisible;
    }

    public static void toggleCursorVisible() {
        cursorVisible = !cursorVisible;
    }

    public static CursorInfo getCurrentCursor() {
        return currentCursor;
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
        boolean active = c != null && c.screenData != null && c.screenData.hybridMode && isOwner(mc, c.screenData);
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
            return;
        }

        float yaw = mc.player.getViewYRot(1.0f);
        float pitch = mc.player.getViewXRot(1.0f);
        lastYaw = yaw;
        lastPitch = pitch;

        Vec3 origin = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getViewVector(1.0f);

        CursorInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (ScreenBlockEntity screen : ScreenBlockEntity.getClientScreens()) {
            if (screen.getLevel() != mc.level) continue;

            for (int i = 0; i < screen.screenCount(); i++) {
                ScreenData data = screen.getScreen(i);
                if (data == null) continue;
                boolean owner = data.ownerUuid != null
                        ? mc.player.getUUID().toString().equals(data.ownerUuid)
                        : mc.player.getGameProfile().getName().equals(data.owner);
                if (data.hybridMode && !owner) continue;

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

        if (best != null && best.screenData.hybridMode && !isOwner(mc, best.screenData)) best = null;

        if (best != null) {
            long now = System.currentTimeMillis();
            if (best.screenData.browser != null && (currentCursor == null ||
                currentCursor.screenData != best.screenData ||
                currentCursor.pixelX != best.pixelX || currentCursor.pixelY != best.pixelY)) {
                if (now - lastMoveTime > 16) {
                    MCEFHelper.sendMouseMove(best.screenData.browser, best.pixelX, best.pixelY, false);
                    lastMoveTime = now;
                }
            }
            currentCursor = best;
        } else {
            if (currentCursor != null) {
                if (leftButtonPressed) {
                    releaseLeftButton();
                }
                sendMouseLeave(mc);
                currentCursor = null;
            }
        }
    }

    public static void handleLeftClick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        if (currentCursor != null && currentCursor.screenData != null
                && currentCursor.screenData.hybridMode && !isOwner(mc, currentCursor.screenData)) {
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
            MCEFHelper.sendMouseClick(currentCursor.screenData.browser, currentCursor.pixelX, currentCursor.pixelY, 0, false, clickCount);
        } else if (!isDown && leftButtonPressed) {
            // Mouse button just released
            releaseLeftButton();
        }
        
        wasAttackDown = isDown || mc.player.isSpectator();
    }

    private static void releaseLeftButton() {
        if (leftButtonPressed && currentCursor != null && currentCursor.screenData != null) {
            MCEFHelper.sendMouseClick(currentCursor.screenData.browser, currentCursor.pixelX, currentCursor.pixelY, 0, true, 1);
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
        if (currentCursor.screenData.hybridMode && !isOwner(mc, currentCursor.screenData)) return;
        if (mc.player == null) return;

        if (mc.player.isShiftKeyDown()) {
            MCEFHelper.sendMouseWheel(currentCursor.screenData.browser, currentCursor.pixelX, currentCursor.pixelY, -delta * 4, 0);
        } else if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            // Ctrl + scroll: zoom browser page
            adjustZoom(currentCursor.screenData, delta > 0 ? 0.1 : -0.1);
        }
    }

    public static void clear() {
        if (leftButtonPressed) releaseLeftButton();
        if (currentCursor != null) sendMouseLeave(Minecraft.getInstance());
        currentCursor = null;
        leftButtonPressed = false;
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
        MCEFHelper.sendMouseMove(currentCursor.screenData.browser, -1, -1, true);
    }
}