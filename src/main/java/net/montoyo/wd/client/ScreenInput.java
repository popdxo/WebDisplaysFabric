package net.montoyo.wd.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;

/**
 * Routes mouse/keyboard input for a display. Normally it goes straight to the local browser. On a Hybrid display
 * the local browser only shows the owner's stream, so a non-owner's input is sent through the server and applied
 * to the owner's browser instead.
 */
public final class ScreenInput {
    private static final ResourceLocation CHANNEL = new ResourceLocation("webdisplays", "screen_action");
    private static long lastRemoteMoveAt;

    private ScreenInput() {}

    public static boolean isLocalOwner(ScreenData data) {
        return MCEFHelper.isLocalPlayerOwner(data.owner, data.ownerUuid);
    }

    /**
     * Owners and Solo displays are always controllable. Hybrid: only while holding the display's linked mouse.
     * Sync: unless the owner made the display view-only.
     */
    public static boolean canControl(ScreenData data) {
        if (data == null) return false;
        if (data.soloMode) return true;
        if (data.hybridMode) {
            // One controller at a time: whoever holds the linked mouse - the owner included.
            return net.montoyo.wd.item.MouseItem.holdsRemote(net.minecraft.client.Minecraft.getInstance().player, data);
        }
        return isLocalOwner(data) || !data.viewOnly;
    }

    /**
     * Keyboard and tabs. Hybrid: carrying the display's linked remote anywhere in your inventory is enough (owner
     * included). Other modes: the owner always, other players unless the display is view-only; Solo is free.
     */
    public static boolean canType(ScreenData data) {
        if (data == null) return false;
        if (data.hybridMode) {
            return net.montoyo.wd.item.MouseItem.hasRemote(net.minecraft.client.Minecraft.getInstance().player, data);
        }
        if (data.soloMode || isLocalOwner(data)) return true;
        return !data.viewOnly;
    }

    /** Hybrid viewer: open ("tadd") or close ("tclose") a tab in the owner's browser. */
    public static void tabRemote(ScreenData data, BlockPos pos, BlockSide side, String op) {
        if (canType(data) && isRemote(data)) send(pos, side, op);
    }

    /** True when input must be forwarded to the owner instead of the local (stream page) browser. */
    public static boolean isRemote(ScreenData data) {
        return data != null && data.hybridMode && !isLocalOwner(data);
    }

    public static void mouseMove(ScreenData data, BlockPos pos, BlockSide side, int x, int y, boolean leave) {
        if (!canControl(data)) return;
        if (isRemote(data)) {
            long now = System.currentTimeMillis();
            if (!leave && now - lastRemoteMoveAt < 33) return; // ~30 Hz is plenty for hover
            lastRemoteMoveAt = now;
            send(pos, side, "m|" + x + "|" + y + "|" + (leave ? 1 : 0));
        } else if (data.browser != null) {
            MCEFHelper.sendMouseMove(data.browser, x, y, leave);
        }
    }

    public static void click(ScreenData data, BlockPos pos, BlockSide side, int x, int y, int button, boolean release, int count) {
        if (!canControl(data)) return;
        if (isRemote(data)) send(pos, side, "c|" + x + "|" + y + "|" + button + "|" + (release ? 1 : 0) + "|" + count);
        else if (data.browser != null) MCEFHelper.sendMouseClick(data.browser, x, y, button, release, count);
    }

    public static void wheel(ScreenData data, BlockPos pos, BlockSide side, int x, int y, double amount) {
        if (!canControl(data)) return;
        if (isRemote(data)) send(pos, side, "w|" + x + "|" + y + "|" + amount);
        else if (data.browser != null) MCEFHelper.sendMouseWheel(data.browser, x, y, amount, 0);
    }

    public static void keyPress(ScreenData data, BlockPos pos, BlockSide side, int key, int scan, int mods) {
        if (!canType(data)) return;
        if (isRemote(data)) send(pos, side, "kp|" + key + "|" + scan + "|" + mods);
        else if (data.browser != null) MCEFHelper.sendKeyPress(data.browser, key, scan, mods);
    }

    public static void keyRelease(ScreenData data, BlockPos pos, BlockSide side, int key, int scan, int mods) {
        if (!canType(data)) return;
        if (isRemote(data)) send(pos, side, "kr|" + key + "|" + scan + "|" + mods);
        else if (data.browser != null) MCEFHelper.sendKeyRelease(data.browser, key, scan, mods);
    }

    public static void keyChar(ScreenData data, BlockPos pos, BlockSide side, char c) {
        if (!canType(data)) return;
        if (isRemote(data)) send(pos, side, "ch|" + (int) c);
        else if (data.browser != null) MCEFHelper.sendKeyEvent(data.browser, c);
    }

    /** Hybrid only: ask the owner's browser to open {@code url}. */
    public static void navigateRemote(ScreenData data, BlockPos pos, BlockSide side, String url) {
        if (canType(data) && isRemote(data) && url.length() <= 2048) send(pos, side, "nav|" + url);
    }

    private static void send(BlockPos pos, BlockSide side, String event) {
        ClientPlayNetworking.send(CHANNEL, ScreenActionPayload.remoteInput(pos, side.id, event).toPacket());
    }

    /** Owner side: apply an event another player sent for this display. */
    public static void applyRemote(ScreenData data, String event) {
        Object browser = data.browser;
        if (browser == null || !data.hybridMode || !isLocalOwner(data)) return;
        String[] p = event.split("\\|", 2);
        try {
            switch (p[0]) {
                case "nav" -> {
                    String url = ScreenBlockEntity.url(p[1]);
                    MCEFHelper.loadBrowserUrl(browser, net.montoyo.wd.WebDisplays.applyBlacklist(url));
                }
                case "m" -> {
                    String[] a = p[1].split("\\|");
                    MCEFHelper.sendMouseMove(browser, clampX(data, a[0]), clampY(data, a[1]), "1".equals(a[2]));
                }
                case "c" -> {
                    String[] a = p[1].split("\\|");
                    int button = Math.max(0, Math.min(2, Integer.parseInt(a[2])));
                    int count = Math.max(1, Math.min(3, Integer.parseInt(a[4])));
                    MCEFHelper.sendMouseClick(browser, clampX(data, a[0]), clampY(data, a[1]), button, "1".equals(a[3]), count);
                }
                case "w" -> {
                    String[] a = p[1].split("\\|");
                    double amount = Math.max(-2000, Math.min(2000, Double.parseDouble(a[2])));
                    MCEFHelper.sendMouseWheel(browser, clampX(data, a[0]), clampY(data, a[1]), amount, 0);
                }
                case "kp", "kr" -> {
                    String[] a = p[1].split("\\|");
                    int key = Integer.parseInt(a[0]), scan = Integer.parseInt(a[1]), mods = Integer.parseInt(a[2]);
                    if ("kp".equals(p[0])) MCEFHelper.sendKeyPress(browser, key, scan, mods);
                    else MCEFHelper.sendKeyRelease(browser, key, scan, mods);
                }
                case "ch" -> {
                    int code = Integer.parseInt(p[1]);
                    if (code > 0 && code <= 0xFFFF) MCEFHelper.sendKeyEvent(browser, (char) code);
                }
                case "tadd" -> {
                    Object created = data.addTab(data.resolution.x, data.resolution.y);
                    if (created != null) ScreenBlockEntity.ensureWindowOpenOverride(created);
                }
                case "tclose" -> {
                    if (data.tabCount() > 1) data.removeTab(data.activeTab());
                }
                default -> { }
            }
        } catch (Exception e) {
            Log.warning("Ignoring malformed remote input '{}': {}", event.length() > 64 ? event.substring(0, 64) : event, e.toString());
        }
    }

    private static int clampX(ScreenData data, String value) {
        int x = Integer.parseInt(value);
        return x < 0 ? -1 : Math.min(x, data.resolution.x - 1);
    }

    private static int clampY(ScreenData data, String value) {
        int y = Integer.parseInt(value);
        return y < 0 ? -1 : Math.min(y, data.resolution.y - 1);
    }
}
