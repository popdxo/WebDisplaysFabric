package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.montoyo.wd.client.ScreenCursorTracker;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class GuiScreenConfig extends Screen {
    private final BlockPos blockPos;
    private final BlockSide side;
    private final boolean isNew;
    private ScreenData screen;
    private EditBox widthInput;
    private EditBox heightInput;
    private EditBox resolutionWidthInput;
    private EditBox resolutionHeightInput;
    private String fitError;

    public GuiScreenConfig(BlockPos pos, BlockSide side, boolean isNew) {
        super(Component.literal("Screen Settings"));
        this.blockPos = pos;
        this.side = side;
        this.isNew = isNew;
    }

    @Override
    protected void init() {
        int cx = virtualWidth() / 2;
        int top = Math.max(12, virtualHeight() / 2 - (isNew ? 100 : 148));
        net.minecraft.world.level.block.entity.BlockEntity be = Minecraft.getInstance().level.getBlockEntity(blockPos);
        if (be instanceof ScreenBlockEntity sbe) screen = sbe.getScreen(side);
        if (!isNew && screen == null) {
            onClose();
            return;
        }
        if (!isNew && screen.hybridMode && !isDisplayOwner(screen)) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Only the display owner can change this Hybrid display."), true);
            onClose();
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(cx + 95, top, 20, 20).build());

        int row = top + 24;
        if (!isNew) {
            addRenderableWidget(Button.builder(Component.literal("Size: " + (screen.autoSize ? "Auto" : "Manual")),
                    this::toggleSizeMode).bounds(cx - 95, row, 190, 20).build());
            row += 23;
        }

        widthInput = numberField(cx - 94, row, 92, screen != null ? screen.imageWidthBlocks() : 2, 3);
        addRenderableWidget(widthInput);
        heightInput = numberField(cx + 3, row, 91, screen != null ? screen.imageHeightBlocks() : 2, 3);
        addRenderableWidget(heightInput);

        if (!isNew) {
            row += 35;
            addRenderableWidget(Button.builder(Component.literal("Resolution: " + (screen.autoResolution ? "Auto" : "Manual")),
                    this::toggleAutoResolution).bounds(cx - 95, row, 190, 20).build());
            row += 23;
            resolutionWidthInput = numberField(cx - 94, row, 92, screen.resolution.x, 5);
            resolutionHeightInput = numberField(cx + 3, row, 91, screen.resolution.y, 5);
            resolutionWidthInput.active = !screen.autoResolution;
            resolutionHeightInput.active = false;
            addRenderableWidget(resolutionWidthInput);
            addRenderableWidget(resolutionHeightInput);

            row += 23;
            addRenderableWidget(Button.builder(Component.literal("Mode: " + modeLabel(screen)),
                    this::cycleMode).bounds(cx - 95, row, 94, 20).build());
            shownHybrid = screen.hybridMode;
            shownRemoteLink = screen.remoteLinkId;
            if (screen.hybridMode) {
                // Hybrid: control belongs to whoever holds the linked mouse.
                Button remoteButton = Button.builder(Component.literal(screen.remoteLinkId != null ? "Unlink Remote" : "No Remote"),
                        b -> unlinkRemote()).bounds(cx + 1, row, 94, 20).build();
                remoteButton.active = isDisplayOwner(screen) && screen.remoteLinkId != null;
                remoteButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "In Hybrid mode only the holder of the linked mouse can control this display, owner included. "
                                + "Link one by using a mouse on the display.")));
                addRenderableWidget(remoteButton);
            } else {
                Button usersButton = Button.builder(Component.literal(usersLabel(screen.viewOnly)), this::toggleViewOnly)
                        .bounds(cx + 1, row, 94, 20).build();
                usersButton.active = isDisplayOwner(screen);
                usersButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Whether other players can click, type and navigate on this display or only watch it. "
                                + "Solo displays are always free to use.")));
                addRenderableWidget(usersButton);
            }

            row += 23;
            addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applySettings())
                    .bounds(cx - 95, row, 190, 20).build());

            row += 23;
            addRenderableWidget(Button.builder(Component.literal("Remove Display"), b -> removeDisplay())
                    .bounds(cx - 95, row, 190, 20).build());

            row += 23;
            addRenderableWidget(Button.builder(Component.translatable("webdisplays.gui.screencfg.rot0"),
                    b -> setRotation(Rotation.ROT_0)).bounds(cx - 95, row, 45, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("webdisplays.gui.screencfg.rot90"),
                    b -> setRotation(Rotation.ROT_90)).bounds(cx - 47, row, 45, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("webdisplays.gui.screencfg.rot180"),
                    b -> setRotation(Rotation.ROT_180)).bounds(cx + 1, row, 45, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("webdisplays.gui.screencfg.rot270"),
                    b -> setRotation(Rotation.ROT_270)).bounds(cx + 49, row, 46, 20).build());

            row += 23;
            addRenderableWidget(Button.builder(Component.literal("−"), b -> adjustZoom(-0.1))
                    .bounds(cx - 95, row, 45, 20).build());
            addRenderableWidget(Button.builder(Component.literal("100%"), b -> resetZoom())
                    .bounds(cx - 47, row, 94, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustZoom(0.1))
                    .bounds(cx + 49, row, 46, 20).build());
        } else {
            row += 33;
            addRenderableWidget(Button.builder(Component.literal("Create"), b -> createScreen())
                    .bounds(cx - 45, row, 90, 20).build());
        }
    }

    private boolean isDisplayOwner(ScreenData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return data.ownerUuid != null
                ? mc.player.getUUID().toString().equals(data.ownerUuid)
                : mc.player.getGameProfile().getName().equals(data.owner);
    }

    private EditBox numberField(int x, int y, int fieldWidth, int value, int maxLength) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20, Component.empty());
        field.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        field.setMaxLength(maxLength);
        field.setValue(Integer.toString(value));
        return field;
    }

    private int parse(EditBox field, int fallback) {
        try { return Integer.parseInt(field.getValue()); } catch (NumberFormatException e) { return fallback; }
    }


    private void createScreen() {
        int blocksWide = Math.max(1, Math.min(100, parse(widthInput, 2)));
        int blocksHigh = Math.max(1, Math.min(100, parse(heightInput, 2)));
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        net.minecraft.core.Direction facing = mc.player.getDirection();
        ScreenBlockEntity.Placement placement = ScreenBlockEntity.placement(blockPos, side, blocksWide, blocksHigh, facing);
        if (!(mc.level.getBlockEntity(placement.anchor()) instanceof ScreenBlockEntity anchor)
                || !anchor.canFitScreen(side, placement.sizeX(), placement.sizeY())) {
            showFitError();
            return;
        }
        // The server creates the display (possibly on another anchor block) and syncs it back.
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                new ScreenActionPayload(blockPos, side.id, ScreenActionPayload.ACTION_ADD_SCREEN,
                        blocksWide + "," + blocksHigh + "," + facing.get2DDataValue()).toPacket());
        onClose();
    }

    private void applySettings() {
        ScreenBlockEntity be = getBlockEntity();
        if (be == null || screen == null) return;
        int blocksWide = Math.max(1, Math.min(100, parse(widthInput, screen.imageWidthBlocks())));
        int blocksHigh = Math.max(1, Math.min(100, parse(heightInput, screen.imageHeightBlocks())));
        boolean swapped = screen.axesSwapped();
        int resolutionWidth = Math.max(64, Math.min(32000, parse(resolutionWidthInput, screen.resolution.x)));
        int resolutionHeight = Math.max(64, Math.min(32000, parse(resolutionHeightInput, screen.resolution.y)));
        int requiredWidth = Math.max(blocksWide, (int) Math.ceil(resolutionWidth / 320.0));
        int requiredHeight = Math.max(blocksHigh, (int) Math.ceil(resolutionHeight / 320.0));
        if (!be.canFitScreen(side, swapped ? requiredHeight : requiredWidth, swapped ? requiredWidth : requiredHeight)) {
            showFitError();
            return;
        }
        int storedWidth = swapped ? blocksHigh : blocksWide;
        int storedHeight = swapped ? blocksWide : blocksHigh;
        if (!be.setDisplaySize(side, storedWidth, storedHeight)) {
            showFitError();
            return;
        }
        if (!screen.autoResolution) {
            int fixedHeight = Math.max(64, Math.min(32000,
                    (int) Math.round(resolutionWidth * blocksHigh / (double) blocksWide)));
            screen.resolution.set(resolutionWidth, fixedHeight);
            screen.resizeBrowsers(resolutionWidth, fixedHeight);
            be.setResolution(side, new Vector2i(resolutionWidth, fixedHeight));
            ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                    ScreenActionPayload.setResolution(blockPos, side.id, resolutionWidth, fixedHeight).toPacket());
        }
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.setDisplaySize(blockPos, side.id, storedWidth, storedHeight).toPacket());
        onClose();
    }

    private void showFitError() {
        fitError = "Screen must fit on free screen blocks and cannot overlap another display.";
    }

    private void toggleAutoResolution(Button button) {
        ScreenBlockEntity be = getBlockEntity();
        if (be == null || screen == null) return;
        screen.autoResolution = !screen.autoResolution;
        be.setAutoResolution(side, screen.autoResolution);
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.setAutoResolution(blockPos, side.id, screen.autoResolution).toPacket());
        button.setMessage(Component.literal("Resolution: " + (screen.autoResolution ? "Auto" : "Manual")));
        if (resolutionWidthInput != null) resolutionWidthInput.active = !screen.autoResolution;
    }

    private boolean shownHybrid;
    private String shownRemoteLink;

    /** Mode and remote link change via server updates; refresh the buttons when they do. */
    @Override
    public void tick() {
        super.tick();
        if (!isNew && screen != null && (screen.hybridMode != shownHybrid
                || !java.util.Objects.equals(screen.remoteLinkId, shownRemoteLink))) {
            rebuildWidgets();
        }
    }

    private void unlinkRemote() {
        if (screen == null || !isDisplayOwner(screen)) return;
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.unlinkRemote(blockPos, side.id).toPacket());
    }

    private static String usersLabel(boolean viewOnly) {
        return viewOnly ? "Others: View only" : "Others: Control";
    }

    private void toggleViewOnly(Button button) {
        if (screen == null || !isDisplayOwner(screen)) return;
        boolean requested = !screen.viewOnly;
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.setViewOnly(blockPos, side.id, requested).toPacket());
        button.setMessage(Component.literal(usersLabel(requested)));
    }

    private static String modeLabel(ScreenData data) {
        return data.hybridMode ? "Hybrid" : data.soloMode ? "Solo" : "Sync";
    }

    /** Sync -> Solo -> Hybrid -> Sync. The server owns the mode; flags update when its block update arrives. */
    private void cycleMode(Button button) {
        if (screen == null) return;
        String next = screen.hybridMode ? "sync" : screen.soloMode ? "hybrid" : "solo";
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.setMode(blockPos, side.id, next).toPacket());
        button.setMessage(Component.literal("Mode: " + Character.toUpperCase(next.charAt(0)) + next.substring(1)));
    }

    private void adjustZoom(double delta) {
        if (screen != null) ScreenCursorTracker.adjustZoom(screen, delta);
    }

    private void resetZoom() {
        if (screen != null) ScreenCursorTracker.resetZoom(screen);
    }

    private void toggleSizeMode(Button button) {
        ScreenBlockEntity be = getBlockEntity();
        if (be == null || screen == null) return;
        screen.autoSize = !screen.autoSize;
        be.setAutoSize(side, screen.autoSize);
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.setAutoSize(blockPos, side.id, screen.autoSize).toPacket());
        button.setMessage(Component.literal("Size: " + (screen.autoSize ? "Auto" : "Manual")));
    }

    private void removeDisplay() {
        ScreenBlockEntity be = getBlockEntity();
        if (be != null) {
            be.removeScreen(side);
            ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                    ScreenActionPayload.removeScreen(blockPos, side.id).toPacket());
        }
        onClose();
    }

    private void setRotation(Rotation rotation) {
        ScreenBlockEntity be = getBlockEntity();
        if (be != null) {
            be.setRotation(side, rotation);
            ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                    ScreenActionPayload.setRotation(blockPos, side.id, rotation.id).toPacket());
        }
        onClose();
    }

    private ScreenBlockEntity getBlockEntity() {
        if (Minecraft.getInstance().level == null) return null;
        net.minecraft.world.level.block.entity.BlockEntity be = Minecraft.getInstance().level.getBlockEntity(blockPos);
        return be instanceof ScreenBlockEntity screenBe ? screenBe : null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.pose().pushPose();
        graphics.pose().scale(UI_SCALE, UI_SCALE, 1.0f);
        int cx = virtualWidth() / 2;
        int top = Math.max(12, virtualHeight() / 2 - (isNew ? 100 : 148));
        graphics.drawCenteredString(font, "Display Configuration", cx, top - 2, 0xFFFFFF);
        graphics.drawString(font, "Blocks W × H", cx - 95, top + 13, 0xBBBBBB);
        if (!isNew) {
            graphics.drawString(font, "Resolution W × H (fixed ratio)", cx - 95, top + 71, 0xBBBBBB);
        }
        super.render(graphics, scaled(mouseX), scaled(mouseY), partialTick);
        if (fitError != null) {
            int errorY = Math.min(virtualHeight() - 24, top + (isNew ? 76 : 238));
            graphics.drawCenteredString(font, fitError, cx, errorY, 0xFF5555);
        }
        if (!isNew && screen != null) {
            graphics.drawCenteredString(font,
                    "Page scale " + (int) Math.round(screen.zoomLevel * 100) + "%", cx, top + 211, 0xAAAAAA);
        }
        graphics.pose().popPose();
    }

    // The whole menu is drawn at UI_SCALE so it fits on small windows; layout runs in unscaled coordinates and
    // mouse input is mapped back into them.
    private static final float UI_SCALE = 0.9f;

    private int virtualWidth() {
        return (int) (width / UI_SCALE);
    }

    private int virtualHeight() {
        return (int) (height / UI_SCALE);
    }

    private static int scaled(double coordinate) {
        return (int) (coordinate / UI_SCALE);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(x / UI_SCALE, y / UI_SCALE, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return super.mouseReleased(x / UI_SCALE, y / UI_SCALE, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(x / UI_SCALE, y / UI_SCALE, button, dx / UI_SCALE, dy / UI_SCALE);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return super.mouseScrolled(x / UI_SCALE, y / UI_SCALE, delta);
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(x / UI_SCALE, y / UI_SCALE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
