package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.Direction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;
import org.joml.Vector3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ScreenBlockEntity extends BlockEntity {

    private static final List<ScreenBlockEntity> clientScreens = new ArrayList<>();
    private static volatile boolean clientScreensDirty = true;
    private static List<ScreenBlockEntity> clientScreensSnapshot = List.of();

    /** Client only: closes every browser (stopping audio) and Hybrid stream; used when leaving a world/server. */
    public static void shutdownAllClientScreens() {
        List<ScreenBlockEntity> all;
        synchronized (clientScreens) {
            all = new ArrayList<>(clientScreens);
            clientScreens.clear();
            clientScreensDirty = true;
        }
        for (ScreenBlockEntity entity : all) {
            for (ScreenData data : entity.screens) {
                data.clearHybridSession();
                data.hybridSeenAt = 0;
                data.unload();
            }
            entity.loaded = false;
        }
    }

    /** Loaded server-side screens, used to reset ownership when an owner leaves. */
    private static final java.util.Set<ScreenBlockEntity> serverScreens = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Server only: displays owned by the player lose their owner; Hybrid displays fall back to Sync. */
    public static void releaseOwnedBy(java.util.UUID uuid, String name) {
        for (ScreenBlockEntity entity : serverScreens) {
            boolean changed = false;
            for (ScreenData data : new ArrayList<>(entity.screens)) {
                boolean owned = data.ownerUuid != null ? data.ownerUuid.equals(uuid.toString())
                        : data.owner != null && data.owner.equals(name);
                if (!owned) continue;
                data.owner = null;
                data.ownerUuid = null;
                if (data.hybridMode) {
                    entity.setMode(data.side, "sync");
                    net.montoyo.wd.network.HybridSignalRelay.forgetSession(data.hybridSessionId);
                    data.hybridSessionId = null;
                    data.hybridViewerToken = null;
                }
                changed = true;
            }
            if (changed) {
                entity.setChanged();
                if (entity.level != null) {
                    entity.level.sendBlockUpdated(entity.worldPosition, entity.getBlockState(), entity.getBlockState(), 3);
                }
            }
        }
    }

    public static List<ScreenBlockEntity> getClientScreens() {
        if (clientScreensDirty) {
            synchronized (clientScreens) {
                clientScreensSnapshot = new ArrayList<>(clientScreens);
                clientScreensDirty = false;
            }
        }
        return clientScreensSnapshot;
    }
    private final ArrayList<ScreenData> screens = new ArrayList<>();
    private boolean loaded = false;
    private AABB renderBB;
    private float ytVolume = 1.0f;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.SCREEN_BLOCK_ENTITY, pos, state);
    }

    // === Network Sync ===

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level.isClientSide) {
            synchronized (clientScreens) {
                if (!clientScreens.contains(this)) {
                    clientScreens.add(this);
                    clientScreensDirty = true;
                }
            }
            load();
        } else {
            serverScreens.add(this);
            // Hybrid sessions live only in memory, so a display that loads as Hybrid (server restart, chunk
            // reload) has no stream behind it: fall back to Sync.
            boolean reset = false;
            for (ScreenData data : screens) {
                if (data.hybridMode) {
                    data.hybridMode = false;
                    data.hybridSessionId = null;
                    data.hybridViewerToken = null;
                    reset = true;
                }
            }
            if (reset) setChanged();
        }
    }

    @Override
    public void setRemoved() {
        serverScreens.remove(this);
        if (level != null && level.isClientSide) {
            for (ScreenData screen : screens) {
                if (screen.browser != null) {
                    screen.unload();
                }
            }
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
        }
        super.setRemoved();
    }

    // === Screen Management ===

    public int screenCount() { return screens.size(); }

    public ScreenData getScreen(int index) {
        return (index >= 0 && index < screens.size()) ? screens.get(index) : null;
    }

    public ScreenData getScreen(BlockSide side) {
        for (ScreenData sc : screens) if (sc.side == side) return sc;
        return null;
    }

    public boolean hasScreen(BlockSide side) { return getScreen(side) != null; }

    /**
     * Auto size: grow/shrink every auto-sized display to the largest free rectangle of screen blocks extending to
     * the picture's right and up from its bottom-left block (the block it was created from). When that moves the
     * rectangle's minimum corner, the display is handed to the new anchor block.
     */
    public boolean refreshSizeFromScreenBlocks() {
        if (level == null || level.isClientSide) return false;
        boolean changed = false;
        for (ScreenData screen : new ArrayList<>(screens)) {
            if (screen.autoSize && refreshSize(screen)) changed = true;
        }
        return changed;
    }

    private boolean refreshSize(ScreenData screen) {
        int[] right = pictureRight(screen);
        int[] up = pictureUp(screen);
        BlockPos origin = pictureOrigin(screen);
        int bestWidth = 0;
        int bestHeight = 0;
        int widestPossible = 100;
        for (int y = 0; y < 100; y++) {
            int rowWidth = 0;
            while (rowWidth < widestPossible) {
                BlockPos cell = origin.offset(right[0] * rowWidth + up[0] * y, right[1] * rowWidth + up[1] * y,
                        right[2] * rowWidth + up[2] * y);
                if (!level.getBlockState(cell).is(WDRegistries.SCREEN_BLOCK)) break;
                ScreenBlockEntity covering = findCovering(level, cell, screen.side);
                if (covering != null && covering != this) break;
                rowWidth++;
            }
            widestPossible = Math.min(widestPossible, rowWidth);
            if (widestPossible == 0) break;
            if (widestPossible * (y + 1) > bestWidth * bestHeight) {
                bestWidth = widestPossible;
                bestHeight = y + 1;
            }
        }
        if (bestWidth < 1 || bestHeight < 1
                || (bestWidth == screen.imageWidthBlocks() && bestHeight == screen.imageHeightBlocks())) return false;

        Placement placement = placement(origin, screen.side, bestWidth, bestHeight, screen.upDir);
        ScreenBlockEntity target = placement.anchor().equals(worldPosition) ? this
                : level.getBlockEntity(placement.anchor()) instanceof ScreenBlockEntity other ? other : null;
        if (target == null || (target != this && target.getScreen(screen.side) != null)) return false;

        screen.size.set(placement.sizeX(), placement.sizeY());
        if (screen.autoResolution) {
            screen.resolution.set(screen.imageWidthBlocks() * 320, screen.imageHeightBlocks() * 320);
        }
        if (target == this) {
            updateAABB();
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } else {
            moveScreenTo(screen, target);
        }
        return true;
    }

    /** Hands a display to another anchor block (server side), keeping all its state. */
    private void moveScreenTo(ScreenData screen, ScreenBlockEntity target) {
        BlockPos from = worldPosition;
        BlockPos to = target.worldPosition;
        screens.remove(screen);
        updateAABB();
        setChanged();
        target.screens.add(screen);
        target.updateAABB();
        target.setChanged();
        // Sent before the block updates so clients move the live browser instead of reloading the page.
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.montoyo.wd.network.ServerNetHandler.broadcastDisplayMoved(serverLevel, from, to, screen.side);
        }
        level.sendBlockUpdated(from, getBlockState(), getBlockState(), 3);
        level.sendBlockUpdated(to, target.getBlockState(), target.getBlockState(), 3);
    }

    /** Client: take a display out of this block entity without closing its browser (it is being moved). */
    public ScreenData detachScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen == null) return null;
        screens.remove(screen);
        updateAABB();
        return screen;
    }

    /** Client: adopt a display (with its live browser) moved from another anchor block. */
    public void attachScreen(ScreenData screen) {
        if (getScreen(screen.side) != null) return;
        screens.add(screen);
        updateAABB();
    }

    /** World step of the picture's right edge direction. */
    private static int[] pictureRight(ScreenData screen) {
        return switch (screen.side) {
            case NORTH -> new int[]{-1, 0, 0};
            case SOUTH -> new int[]{1, 0, 0};
            case WEST -> new int[]{0, 0, 1};
            case EAST -> new int[]{0, 0, -1};
            case BOTTOM, TOP -> {
                Direction right = screen.upDir.getClockWise();
                yield new int[]{right.getStepX(), 0, right.getStepZ()};
            }
        };
    }

    /** World step of the picture's top edge direction. */
    private static int[] pictureUp(ScreenData screen) {
        return switch (screen.side) {
            case BOTTOM, TOP -> new int[]{screen.upDir.getStepX(), 0, screen.upDir.getStepZ()};
            default -> new int[]{0, 1, 0};
        };
    }

    /** The picture's bottom-left block for a display anchored here (its minimum corner is worldPosition). */
    private BlockPos pictureOrigin(ScreenData screen) {
        int extentX, extentY, extentZ;
        switch (screen.side) {
            case NORTH, SOUTH -> { extentX = screen.size.x; extentY = screen.size.y; extentZ = 1; }
            case WEST, EAST -> { extentX = 1; extentY = screen.size.y; extentZ = screen.size.x; }
            default -> { extentX = screen.size.x; extentY = 1; extentZ = screen.size.y; }
        }
        int[] right = pictureRight(screen);
        int[] up = pictureUp(screen);
        return worldPosition.offset(
                right[0] < 0 || up[0] < 0 ? extentX - 1 : 0,
                right[1] < 0 || up[1] < 0 ? extentY - 1 : 0,
                right[2] < 0 || up[2] < 0 ? extentZ - 1 : 0);
    }

    private BlockPos screenCellPos(BlockSide side, int x, int y) {
        return switch (side) {
            case NORTH, SOUTH -> worldPosition.offset(x, y, 0);
            case WEST, EAST -> worldPosition.offset(0, y, x);
            case BOTTOM, TOP -> worldPosition.offset(x, 0, y);
        };
    }

    /**
     * A display fits when every cell is a screen block whose face on {@code side} is not already part of another
     * display. Different faces of the same block can each hold their own display.
     */
    public boolean canFitScreen(BlockSide side, int width, int height) {
        if (level == null || width < 1 || height < 1 || width > 100 || height > 100) return false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                BlockPos cellPos = screenCellPos(side, x, y);
                if (!level.getBlockState(cellPos).is(WDRegistries.SCREEN_BLOCK)) return false;
                ScreenBlockEntity covering = findCovering(level, cellPos, side);
                if (covering != null && covering != this) return false;
            }
        }
        return true;
    }

    /** True when {@code cell}'s face on the display's side is part of display {@code data} anchored here. */
    public boolean covers(ScreenData data, BlockPos cell) {
        int dx = cell.getX() - worldPosition.getX();
        int dy = cell.getY() - worldPosition.getY();
        int dz = cell.getZ() - worldPosition.getZ();
        return switch (data.side) {
            case NORTH, SOUTH -> dz == 0 && dx >= 0 && dx < data.size.x && dy >= 0 && dy < data.size.y;
            case WEST, EAST -> dx == 0 && dz >= 0 && dz < data.size.x && dy >= 0 && dy < data.size.y;
            case BOTTOM, TOP -> dy == 0 && dx >= 0 && dx < data.size.x && dz >= 0 && dz < data.size.y;
        };
    }

    /** The loaded display (its anchor block entity) whose {@code side} face covers {@code cell}, or null. */
    public static ScreenBlockEntity findCovering(Level level, BlockPos cell, BlockSide side) {
        if (level == null) return null;
        Iterable<ScreenBlockEntity> candidates = level.isClientSide ? getClientScreens() : serverScreens;
        for (ScreenBlockEntity entity : candidates) {
            if (entity.level != level || entity.isRemoved()) continue;
            ScreenData data = entity.getScreen(side);
            if (data != null && entity.covers(data, cell)) return entity;
        }
        return null;
    }

    /** Where a new display goes: anchor block, stored size (world axes) and picture orientation. */
    public record Placement(BlockPos anchor, int sizeX, int sizeY, Direction upDir) {}

    /**
     * Displays grow to the viewer's right and up from the clicked block. Walls: "up" is +Y. Floors/ceilings: "up"
     * is the direction the player faces, "right" is the player's right. Storage always extends along positive
     * axes from the anchor, so the anchor is the rectangle's minimum corner.
     */
    public static Placement placement(BlockPos clicked, BlockSide side, int width, int height, Direction facing) {
        return switch (side) {
            case NORTH -> new Placement(clicked.offset(-(width - 1), 0, 0), width, height, Direction.NORTH);
            case EAST -> new Placement(clicked.offset(0, 0, -(width - 1)), width, height, Direction.NORTH);
            case SOUTH, WEST -> new Placement(clicked, width, height, Direction.NORTH);
            case BOTTOM, TOP -> {
                Direction up = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
                Direction right = up.getClockWise();
                int anchorX = clicked.getX() + Math.min(0, right.getStepX() * (width - 1)) + Math.min(0, up.getStepX() * (height - 1));
                int anchorZ = clicked.getZ() + Math.min(0, right.getStepZ() * (width - 1)) + Math.min(0, up.getStepZ() * (height - 1));
                int sizeX = Math.abs(right.getStepX()) * width + Math.abs(up.getStepX()) * height;
                int sizeZ = Math.abs(right.getStepZ()) * width + Math.abs(up.getStepZ()) * height;
                yield new Placement(new BlockPos(anchorX, clicked.getY(), anchorZ), sizeX, sizeZ, up);
            }
        };
    }

    public void setRemoteLink(BlockSide side, String linkId) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.remoteLinkId = linkId;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void setViewOnly(BlockSide side, boolean viewOnly) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.viewOnly = viewOnly;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public boolean addScreen(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        if (getScreen(side) != null || !canFitScreen(side, size.x, size.y)) return false;
        screens.add(new ScreenData(side, resolution, size, owner));
        updateAABB();
        setChanged();
        if (level != null && level.isClientSide) {
            load();
        }
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
    }

    public void removeScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.unload();
        screens.remove(screen);
        updateAABB();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // === Screen Configuration ===

    public void setScreenURL(BlockSide side, String url) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.url = WebDisplays.applyBlacklist(url);
        if (level != null && level.isClientSide && screen.browser != null) {
            MCEFHelper.loadBrowserUrl(screen.browser, screen.url);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }


    public void setResolution(BlockSide side, Vector2i res) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        res.x = Math.max(64, Math.min(res.x, 32000));
        res.y = Math.max(64, Math.min(res.y, 32000));
        screen.resolution.set(res.x, res.y);
        if (level != null && level.isClientSide) {
            screen.resizeBrowsers(res.x, res.y);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        updateAABB();
    }

    public boolean setDisplaySize(BlockSide side, int width, int height) {
        ScreenData screen = getScreen(side);
        if (screen == null || !canFitScreen(side, width, height)) return false;
        screen.size.set(width, height);
        if (screen.autoResolution) {
            screen.resolution.set(screen.imageWidthBlocks() * 320, screen.imageHeightBlocks() * 320);
            if (level != null && level.isClientSide) screen.resizeBrowsers(screen.resolution.x, screen.resolution.y);
        }
        setChanged();
        updateAABB();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        return true;
    }

    /** @param mode "sync", "solo" or "hybrid" */
    public void setMode(BlockSide side, String mode) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.hybridMode = "hybrid".equals(mode);
        screen.soloMode = "solo".equals(mode);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setAutoResolution(BlockSide side, boolean enabled) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.autoResolution = enabled;
        if (enabled) {
            screen.resolution.set(screen.imageWidthBlocks() * 320, screen.imageHeightBlocks() * 320);
            if (level != null && level.isClientSide) screen.resizeBrowsers(screen.resolution.x, screen.resolution.y);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setRotation(BlockSide side, Rotation rot) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.rotation = rot;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setAutoSize(BlockSide side, boolean enabled) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.autoSize = enabled;
        if (enabled) refreshSizeFromScreenBlocks();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setAutoVolume(BlockSide side, boolean value) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.autoVolume = value;
        setChanged();
    }

    public void setOwner(BlockSide side, String owner) {
        ScreenData screen = getScreen(side);
        if (screen == null) return;
        screen.owner = owner;
        setChanged();
    }

    // === Mouse Interaction ===

    public void click(Player player, BlockHitResult hit) {
        BlockSide side = BlockSide.fromDirection(hit.getDirection());
        clickAt(player, side, hit);
    }

    public void hitToScreenCoords(ScreenData screen, double localX, double localY, double localZ, Vector2i out) {
        float w = screen.size.x;
        float h = screen.size.y;
        double u, v;
        switch (screen.side) {
            case NORTH -> {
                u = 1.0 - localX / w;
                v = 1.0 - localY / h;
            }
            case SOUTH -> {
                u = localX / w;
                v = 1.0 - localY / h;
            }
            case WEST -> {
                u = localZ / w;
                v = 1.0 - localY / h;
            }
            case EAST -> {
                u = 1.0 - localZ / w;
                v = 1.0 - localY / h;
            }
            case BOTTOM, TOP -> {
                double[] flat = flatUV(screen.upDir, localX / w, localZ / h);
                u = flat[0];
                v = flat[1];
            }
            default -> {
                u = 0; v = 0;
            }
        }
        switch (screen.rotation) {
            case ROT_90 -> { double tu = u; u = v; v = 1.0 - tu; }
            case ROT_180 -> { u = 1.0 - u; v = 1.0 - v; }
            case ROT_270 -> { double tu = u; u = 1.0 - v; v = tu; }
        }
        out.x = Math.max(0, Math.min((int) (u * screen.resolution.x), screen.resolution.x - 1));
        out.y = Math.max(0, Math.min((int) (v * screen.resolution.y), screen.resolution.y - 1));
    }

    /**
     * Picture coordinates (0..1, v down) at a point of a floor/ceiling display, given as fractions of its x and z
     * extent. The top of the picture points to {@code upDir}; its right edge to the clockwise neighbour.
     */
    public static double[] flatUV(Direction upDir, double fx, double fz) {
        return switch (upDir) {
            case SOUTH -> new double[]{1.0 - fx, 1.0 - fz};
            case EAST -> new double[]{fz, 1.0 - fx};
            case WEST -> new double[]{1.0 - fz, fx};
            default -> new double[]{fx, fz};
        };
    }

    public void clickAt(Player player, BlockSide side, BlockHitResult hit) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return;

        Vec3 hitLoc = hit.getLocation();
        double localX = hitLoc.x - worldPosition.getX();
        double localY = hitLoc.y - worldPosition.getY();
        double localZ = hitLoc.z - worldPosition.getZ();

        Vector2i clickPos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, clickPos);

        long now = System.currentTimeMillis();
        int clickCount = (now - screen.lastClickTime < 500) ? 2 : 1;
        screen.lastClickTime = now;

        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, false, clickCount);
        MCEFHelper.sendMouseClick(screen.browser, clickPos.x, clickPos.y, 0, true, clickCount);
    }

    public boolean handleMouseEvent(BlockSide side, double hitX, double hitY, double hitZ) {
        ScreenData screen = getScreen(side);
        if (screen == null || screen.browser == null) return false;

        double localX = hitX - worldPosition.getX();
        double localY = hitY - worldPosition.getY();
        double localZ = hitZ - worldPosition.getZ();

        Vector2i mousePos = new Vector2i();
        hitToScreenCoords(screen, localX, localY, localZ, mousePos);

        MCEFHelper.sendMouseMove(screen.browser, mousePos.x, mousePos.y, false);
        return true;
    }

    // === Keyboard Input ===

    public void type(String text) {
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                for (char c : text.toCharArray()) MCEFHelper.sendKeyEvent(screen.browser, c);
            }
        }
    }

    // === URL Processing ===

    public static String url(String input) throws IOException {
        if (input.startsWith("mod://") || input.startsWith("webdisplays://")) return input;
        if (!input.startsWith("http://") && !input.startsWith("https://")) return "https://" + input;
        return input;
    }

    // === Lifecycle ===

    public boolean isLoaded() { return loaded; }

    public void load() {
        if (level != null && level.isClientSide && MCEFHelper.isMCEFAvailable()) {
            if (!MCEFHelper.isMCEFInitialized()) {
                return;
            }
            for (ScreenData screen : screens) {
                if (screen.browser == null) {
                    String loadUrl = "about:blank";
                    if (screen.url != null && !screen.url.isEmpty()) {
                        try { loadUrl = url(screen.url); } catch (IOException e) { Log.warning("Invalid URL: {}", screen.url); }
                    }
                    Object browser = MCEFHelper.createBrowser(loadUrl, false, screen.resolution.x, screen.resolution.y);
                    screen.setBrowser(browser);
                    if (screen.browser != null) {
                        Log.info("Created browser for screen at {} side {}", worldPosition, screen.side);
                        injectScripts(screen.browser);
                        if (screen.tabUrls.size() > 1) {
                            screen.reconcileTabs(new ArrayList<>(screen.tabUrls), screen.activeTab());
                        }
                    }
                }
            }
        }
        loaded = true;
    }

    public boolean needsBrowserRetry() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return true;
        for (ScreenData screen : screens) {
            if (screen.browser == null) return true;
        }
        return false;
    }

    public boolean retryCreateBrowsers() {
        if (level == null || !level.isClientSide || !MCEFHelper.isMCEFAvailable()) return false;
        if (!MCEFHelper.isMCEFInitialized()) return false;
        boolean created = false;
        for (ScreenData screen : screens) {
            if (screen.browser == null) {
                String loadUrl = "about:blank";
                if (screen.url != null && !screen.url.isEmpty()) {
                    try { loadUrl = url(screen.url); } catch (IOException e) { Log.warning("Invalid URL: {}", screen.url); }
                }
                Object browser = MCEFHelper.createBrowser(loadUrl, false, screen.resolution.x, screen.resolution.y);
                screen.setBrowser(browser);
                if (screen.browser != null) {
                    Log.info("Created browser for screen at {} side {}", worldPosition, screen.side);
                    injectScripts(screen.browser);
                    created = true;
                }
            }
        }
        return created;
    }

    public void activate() { load(); }
    public void deactivate() {
        for (ScreenData screen : screens) {
            if (screen.browser != null) {
                MCEFHelper.injectJavascript(screen.browser, MUTE_AUDIO_JS);
            }
            screen.unload();
        }
        loaded = false;
    }
    public void unload() { deactivate(); }

    public void disableScreen(BlockSide side) {
        ScreenData screen = getScreen(side);
        if (screen != null) screen.unload();
    }

    public void onDestroy() {
        if (level != null && level.isClientSide) {
            for (ScreenData screen : screens) {
                if (screen.browser != null) {
                    screen.unload();
                }
            }
            synchronized (clientScreens) {
                clientScreens.remove(this);
                clientScreensDirty = true;
            }
        }
        screens.clear();
        loaded = false;
    }

    public static final String WINDOW_OPEN_OVERRIDE_JS = "if(typeof window.__wdPatched==='undefined'){window.__wdPatched=true;window.open=function(){return null};document.addEventListener('click',function(e){var a=e.target.closest('a');if(a&&a.target==='_blank'){e.preventDefault();e.stopPropagation()}},true)}";

    public static final String MUTE_AUDIO_JS = "(function(){try{document.querySelectorAll('video,audio').forEach(function(el){el.pause();el.muted=true;el.src=''});if(window.__wdAudioCtx)window.__wdAudioCtx.close();window.__wdAudioCtx=null}catch(e){}})()";
    public static final String PROXIMITY_AUDIO_JS = "(function(){try{var gain=__WD_AUDIO_GAIN__,pan=__WD_AUDIO_PAN__;window.__wdAudioGain=gain;document.querySelectorAll('video,audio').forEach(function(el){if(!el.__wdPanner){try{var c=window.__wdAudioCtx||(window.__wdAudioCtx=new (window.AudioContext||window.webkitAudioContext)()),s=c.createMediaElementSource(el),g=c.createGain(),p=c.createStereoPanner();s.connect(g);g.connect(p);p.connect(c.destination);el.__wdPanner={gain:g,pan:p};}catch(e){}}if(el.__wdPanner){el.__wdPanner.gain.gain.value=gain;el.__wdPanner.pan.pan.value=pan;}else{if(el.dataset.wdBaseVolume===undefined)el.dataset.wdBaseVolume=String(el.volume);el.volume=Math.max(0,Math.min(1,Number(el.dataset.wdBaseVolume)*gain))}})}catch(e){}})()";

    private void injectScripts(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
        MCEFHelper.injectJavascript(browser, PROXIMITY_AUDIO_JS
                .replace("__WD_AUDIO_GAIN__", "1")
                .replace("__WD_AUDIO_PAN__", "0"));
    }

    public static void ensureWindowOpenOverride(Object browser) {
        if (browser == null) return;
        MCEFHelper.injectJavascript(browser, WINDOW_OPEN_OVERRIDE_JS);
    }

    // === Sound ===

    public void playSound(float volume) {
        if (level != null) {
            level.playLocalSound(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                    worldPosition.getZ() + 0.5, WDRegistries.KEYBOARD_TYPE, SoundSource.BLOCKS,
                    volume, 1.0f, false);
        }
    }

    // === Render Bounding Box ===

    public AABB getRenderBoundingBox() {
        if (renderBB == null) updateAABB();
        return renderBB != null ? renderBB : new AABB(worldPosition);
    }

    private void updateAABB() {
        if (screens.isEmpty()) {
            renderBB = new AABB(worldPosition);
            return;
        }
        double minX = worldPosition.getX(), minY = worldPosition.getY(), minZ = worldPosition.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        for (ScreenData screen : screens) {
            switch (screen.side) {
                case NORTH, SOUTH -> { maxX = Math.max(maxX, minX + screen.size.x); maxY = Math.max(maxY, minY + screen.size.y); }
                case WEST, EAST -> { maxZ = Math.max(maxZ, minZ + screen.size.x); maxY = Math.max(maxY, minY + screen.size.y); }
                case BOTTOM, TOP -> { maxX = Math.max(maxX, minX + screen.size.x); maxZ = Math.max(maxZ, minZ + screen.size.y); }
            }
        }
        renderBB = new AABB(minX - 0.01, minY - 0.01, minZ - 0.01, maxX + 0.01, maxY + 0.01, maxZ + 0.01);
    }

    // === NBT Serialization ===

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        ListTag screenList = new ListTag();
        for (ScreenData screen : screens) {
            CompoundTag screenTag = new CompoundTag();
            screenTag.putInt("side", screen.side.id);
            screenTag.putInt("resX", screen.resolution.x);
            screenTag.putInt("resY", screen.resolution.y);
            screenTag.putInt("sizeX", screen.size.x);
            screenTag.putInt("sizeY", screen.size.y);
            screenTag.putInt("rotation", screen.rotation.id);
            screenTag.putBoolean("autoVolume", screen.autoVolume);
            screenTag.putBoolean("autoSize", screen.autoSize);
            screenTag.putBoolean("autoResolution", screen.autoResolution);
            screenTag.putBoolean("hybridMode", screen.hybridMode);
            screenTag.putBoolean("soloMode", screen.soloMode);
            screenTag.putBoolean("viewOnly", screen.viewOnly);
            if (screen.remoteLinkId != null) screenTag.putString("remoteLink", screen.remoteLinkId);
            screenTag.putInt("upDir", screen.upDir.get2DDataValue());
            if (screen.owner != null) screenTag.putString("owner", screen.owner);
            if (screen.ownerUuid != null) screenTag.putString("ownerUuid", screen.ownerUuid);
            if (screen.url != null) screenTag.putString("url", screen.url);
            ListTag tabs = new ListTag();
            for (String tabUrl : screen.tabUrls) {
                CompoundTag tab = new CompoundTag();
                tab.putString("url", tabUrl);
                tabs.add(tab);
            }
            screenTag.put("tabs", tabs);
            screenTag.putInt("activeTab", screen.activeTab());
            screenTag.putDouble("mediaTime", screen.mediaTime);
            screenTag.putBoolean("mediaPlaying", screen.mediaPlaying);
            screenTag.putLong("mediaRevision", screen.mediaRevision);
            screenTag.putLong("mediaUpdatedAt", screen.mediaUpdatedAt);

            screenList.add(screenTag);
        }
        tag.put("screens", screenList);
        tag.putFloat("ytVolume", ytVolume);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        ArrayList<ScreenData> previousScreens = new ArrayList<>(screens);
        screens.clear();
        if (tag.contains("screens")) {
            ListTag screenList = tag.getList("screens", Tag.TAG_COMPOUND);
            for (int i = 0; i < screenList.size(); i++) {
                CompoundTag screenTag = screenList.getCompound(i);
                BlockSide side = BlockSide.fromInt(screenTag.getInt("side"));
                Vector2i res = new Vector2i(screenTag.getInt("resX"), screenTag.getInt("resY"));
                Vector2i size = new Vector2i(screenTag.getInt("sizeX"), screenTag.getInt("sizeY"));
                Rotation rot = Rotation.fromInt(screenTag.getInt("rotation"));
                boolean autoVol = screenTag.getBoolean("autoVolume");
                boolean autoSize = !screenTag.contains("autoSize") || screenTag.getBoolean("autoSize");
                boolean autoResolution = !screenTag.contains("autoResolution") || screenTag.getBoolean("autoResolution");
                boolean hybridMode = screenTag.getBoolean("hybridMode");
                boolean soloMode = screenTag.getBoolean("soloMode");
                boolean viewOnly = screenTag.getBoolean("viewOnly");
                String remoteLink = screenTag.contains("remoteLink") ? screenTag.getString("remoteLink") : null;
                Direction upDir = screenTag.contains("upDir") ? Direction.from2DDataValue(screenTag.getInt("upDir")) : Direction.NORTH;
                String owner = screenTag.contains("owner") ? screenTag.getString("owner") : null;
                String ownerUuid = screenTag.contains("ownerUuid") ? screenTag.getString("ownerUuid") : null;
                String url = screenTag.contains("url") ? screenTag.getString("url") : null;
                double mediaTime = screenTag.getDouble("mediaTime");
                boolean mediaPlaying = screenTag.getBoolean("mediaPlaying");
                long mediaRevision = screenTag.getLong("mediaRevision");
                long mediaUpdatedAt = screenTag.getLong("mediaUpdatedAt");
                List<String> tabUrls = new ArrayList<>();
                if (screenTag.contains("tabs", Tag.TAG_LIST)) {
                    ListTag tabs = screenTag.getList("tabs", Tag.TAG_COMPOUND);
                    for (int tabIndex = 0; tabIndex < Math.min(tabs.size(), 32); tabIndex++) {
                        tabUrls.add(tabs.getCompound(tabIndex).getString("url"));
                    }
                }
                int activeTab = screenTag.getInt("activeTab");

                ScreenData screen = null;
                for (ScreenData previous : previousScreens) {
                    if (previous.side == side) {
                        screen = previous;
                        break;
                    }
                }
                if (screen == null) {
                    screen = new ScreenData(side, res, size, owner);
                } else {
                    int oldWidth = screen.resolution.x;
                    int oldHeight = screen.resolution.y;
                    screen.resolution.set(res.x, res.y);
                    screen.size.set(size.x, size.y);
                    if (level != null && level.isClientSide
                            && (oldWidth != res.x || oldHeight != res.y)) {
                        screen.resizeBrowsers(res.x, res.y);
                    }
                    screen.owner = owner;
                }
                screen.ownerUuid = ownerUuid;
                boolean mediaChanged = screen.mediaRevision != mediaRevision;
                screen.rotation = rot;
                screen.mediaTime = mediaTime;
                screen.mediaPlaying = mediaPlaying;
                screen.mediaRevision = mediaRevision;
                screen.mediaUpdatedAt = mediaUpdatedAt;
                if (mediaChanged && !soloMode && !hybridMode && level != null && level.isClientSide && screen.browser != null) {
                    screen.applyMediaState();
                }
                screen.autoVolume = autoVol;
                screen.autoSize = autoSize;
                screen.autoResolution = autoResolution;
                boolean wasHybrid = screen.hybridMode;
                screen.hybridMode = hybridMode;
                screen.soloMode = soloMode;
                screen.viewOnly = viewOnly;
                screen.remoteLinkId = remoteLink;
                screen.upDir = upDir;
                boolean clientSide = level != null && level.isClientSide;
                if (clientSide && wasHybrid && !hybridMode) screen.clearHybridSession();
                // Solo and Hybrid displays keep their own local tabs (Hybrid: the owner's browser is the source, the
                // server never learns its URLs); the server's tab list must not override them. Leaving Hybrid
                // restores the shared tabs.
                boolean keepLocalTabs = clientSide && !(wasHybrid && !hybridMode) && (soloMode || hybridMode);
                screen.url = url;
                if (tabUrls.isEmpty()) tabUrls.add(url == null ? "about:blank" : url);
                int previousActiveTab = screen.activeTab();
                if (!keepLocalTabs) screen.setActiveTabIndex(Math.max(0, Math.min(activeTab, tabUrls.size() - 1)));
                boolean tabsChanged = !keepLocalTabs
                        && (!screen.tabUrls.equals(tabUrls) || previousActiveTab != screen.activeTab());
                if (tabsChanged) {
                    screen.tabUrls.clear();
                    screen.tabUrls.addAll(tabUrls);
                    if (level != null && level.isClientSide && screen.browser != null) {
                        screen.reconcileTabs(tabUrls, activeTab);
                    }
                }
                if (screen.tabUrls.isEmpty() && !keepLocalTabs) screen.tabUrls.addAll(tabUrls);

                screens.add(screen);
            }
        }
        if (level != null && level.isClientSide) {
            for (ScreenData previous : previousScreens) {
                if (!screens.contains(previous)) {
                    previous.clearHybridSession();
                    previous.unload();
                }
            }
        }
        if (tag.contains("ytVolume")) {
            ytVolume = tag.getFloat("ytVolume");
        }
        updateAABB();
        if (level != null && level.isClientSide) {
            load();
        }
    }

    public Vec3 centerPoint(ScreenData screen) {
        double x = worldPosition.getX() + screen.size.x / 2.0;
        double y = worldPosition.getY() + 0.5;
        double z = worldPosition.getZ() + screen.size.y / 2.0;
        switch (screen.side) {
            case NORTH -> { y += screen.size.y / 2.0; z = worldPosition.getZ(); }
            case SOUTH -> { y += screen.size.y / 2.0; z = worldPosition.getZ() + 1.0; }
            case WEST -> {
                y += screen.size.y / 2.0;
                x = worldPosition.getX();
                z = worldPosition.getZ() + screen.size.x / 2.0;
            }
            case EAST -> {
                y += screen.size.y / 2.0;
                x = worldPosition.getX() + 1.0;
                z = worldPosition.getZ() + screen.size.x / 2.0;
            }
            case BOTTOM -> y = worldPosition.getY();
            case TOP -> y = worldPosition.getY() + 1.0;
        }
        return new Vec3(x + screen.side.normal.x * 0.01,
                y + screen.side.normal.y * 0.01,
                z + screen.side.normal.z * 0.01);
    }

    public Vec3 closestPointTo(ScreenData screen, Vec3 position) {
        double cx = worldPosition.getX() + screen.side.right.x * screen.size.x / 2.0
                + screen.side.up.x * screen.size.y / 2.0 + screen.side.normal.x * 0.01;
        double cy = worldPosition.getY() + screen.side.right.y * screen.size.x / 2.0
                + screen.side.up.y * screen.size.y / 2.0 + screen.side.normal.y * 0.01;
        double cz = worldPosition.getZ() + screen.side.right.z * screen.size.x / 2.0
                + screen.side.up.z * screen.size.y / 2.0 + screen.side.normal.z * 0.01;
        double dx = position.x - cx, dy = position.y - cy, dz = position.z - cz;
        double u = dx * screen.side.right.x + dy * screen.side.right.y + dz * screen.side.right.z;
        double v = dx * screen.side.up.x + dy * screen.side.up.y + dz * screen.side.up.z;
        u = Math.max(-screen.size.x / 2.0, Math.min(screen.size.x / 2.0, u));
        v = Math.max(-screen.size.y / 2.0, Math.min(screen.size.y / 2.0, v));
        return new Vec3(cx + screen.side.right.x * u + screen.side.up.x * v,
                cy + screen.side.right.y * u + screen.side.up.y * v,
                cz + screen.side.right.z * u + screen.side.up.z * v);
    }

    // === Distance Calculation ===

    public double distanceTo(Vec3 position) {
        double dist = Double.POSITIVE_INFINITY;
        for (ScreenData scrn : screens) {
            Vector3d p = new Vector3d(
                (scrn.side.right.x * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.x) / 2.0,
                (scrn.side.right.y * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.y) / 2.0,
                (scrn.side.right.z * scrn.size.x) / 2.0 + (scrn.size.y * scrn.side.up.z) / 2.0
            ).add(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
            dist = Math.min(dist, position.distanceTo(new Vec3(p.x, p.y, p.z)));
        }
        return dist;
    }

    // === Getters ===

    public List<ScreenData> getScreens() { return screens; }
    public float getYtVolume() { return ytVolume; }
    public void setYtVolume(float vol) { this.ytVolume = vol; setChanged(); }

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
