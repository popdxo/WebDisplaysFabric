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
        int cx = width / 2;
        int top = Math.max(12, height / 2 - (isNew ? 100 : 148));
        net.minecraft.world.level.block.entity.BlockEntity be = Minecraft.getInstance().level.getBlockEntity(blockPos);
        if (be instanceof ScreenBlockEntity sbe) screen = sbe.getScreen(side);
        if (!isNew && screen == null) {
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

        widthInput = numberField(cx - 94, row, 92, screen != null ? screen.size.x : 2, 3);
        addRenderableWidget(widthInput);
        heightInput = numberField(cx + 3, row, 91, screen != null ? screen.size.y : 2, 3);
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
            addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applySettings())
                    .bounds(cx - 95, row, 190, 20).build());

            row += 23;
            addRenderableWidget(Button.builder(Component.translatable("webdisplays.gui.screencfg.seturl"),
                    b -> Minecraft.getInstance().setScreen(new GuiSetURL(blockPos, side)))
                    .bounds(cx - 95, row, 93, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Remove Display"), b -> removeDisplay())
                    .bounds(cx + 1, row, 94, 20).build());

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
        ScreenBlockEntity be = getBlockEntity();
        if (be == null || !be.canFitScreen(side, blocksWide, blocksHigh)) {
            showFitError();
            return;
        }
        String owner = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getName().getString() : "unknown";
        Vector2i size = new Vector2i(blocksWide, blocksHigh);
        Vector2i resolution = new Vector2i(blocksWide * 320, blocksHigh * 320);
        if (!be.addScreen(side, resolution, size, owner)) return;
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                new ScreenActionPayload(blockPos, side.id, ScreenActionPayload.ACTION_ADD_SCREEN,
                        blocksWide + "," + blocksHigh).toPacket());
        onClose();
    }

    private void applySettings() {
        ScreenBlockEntity be = getBlockEntity();
        if (be == null || screen == null) return;
        int blocksWide = Math.max(1, Math.min(100, parse(widthInput, screen.size.x)));
        int blocksHigh = Math.max(1, Math.min(100, parse(heightInput, screen.size.y)));
        int resolutionWidth = Math.max(64, Math.min(32000, parse(resolutionWidthInput, screen.resolution.x)));
        int resolutionHeight = Math.max(64, Math.min(32000, parse(resolutionHeightInput, screen.resolution.y)));
        int requiredWidth = Math.max(blocksWide, (int) Math.ceil(resolutionWidth / 320.0));
        int requiredHeight = Math.max(blocksHigh, (int) Math.ceil(resolutionHeight / 320.0));
        if (!be.canFitScreen(side, requiredWidth, requiredHeight)) {
            showFitError();
            return;
        }
        if (!be.setDisplaySize(side, blocksWide, blocksHigh)) {
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
                ScreenActionPayload.setDisplaySize(blockPos, side.id, blocksWide, blocksHigh).toPacket());
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
        int cx = width / 2;
        int top = Math.max(12, height / 2 - (isNew ? 100 : 148));
        graphics.drawCenteredString(font, "Display Configuration", cx, top - 2, 0xFFFFFF);
        graphics.drawString(font, "Blocks W × H", cx - 95, top + 13, 0xBBBBBB);
        if (!isNew) {
            graphics.drawString(font, "Resolution W × H (fixed ratio)", cx - 95, top + 71, 0xBBBBBB);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (fitError != null) {
            int errorY = Math.min(height - 24, top + (isNew ? 76 : 238));
            graphics.drawCenteredString(font, fitError, cx, errorY, 0xFF5555);
        }
        if (!isNew && screen != null) {
            graphics.drawCenteredString(font,
                    "Page scale " + (int) Math.round(screen.zoomLevel * 100) + "%", cx, top + 211, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
