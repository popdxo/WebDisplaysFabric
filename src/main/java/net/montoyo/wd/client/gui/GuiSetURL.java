package net.montoyo.wd.client.gui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.utilities.data.BlockSide;
import net.minecraft.client.Minecraft;

public class GuiSetURL extends Screen {
    private final BlockPos blockPos;
    private final BlockSide side;
    private EditBox urlField;

    public GuiSetURL(BlockPos pos, BlockSide side) {
        super(Component.translatable("webdisplays.gui.seturl.url"));
        this.blockPos = pos;
        this.side = side;
    }

    @Override
    protected void init() {
        urlField = new EditBox(this.font, this.width / 2 - 100, this.height / 2 - 30, 200, 20,
                Component.translatable("webdisplays.gui.seturl.url"));
        urlField.setMaxLength(2048);
        urlField.setValue("https://");
        urlField.setTextColor(0xFFFFFFFF);
        this.addWidget(urlField);
        this.setInitialFocus(urlField);

        this.addRenderableWidget(Button.builder(
                Component.translatable("webdisplays.gui.seturl.ok"),
                button -> {
                    String url = urlField.getValue();
                    if (!url.isEmpty()) {
                        try {
                            String finalUrl = ScreenBlockEntity.url(url);
                            net.minecraft.world.level.block.entity.BlockEntity be =
                                    Minecraft.getInstance().level.getBlockEntity(blockPos);
                            boolean solo = be instanceof ScreenBlockEntity sbe && sbe.getScreen(side) != null
                                    && sbe.getScreen(side).soloMode;
                            if (!solo) { // Solo displays navigate locally only
                                ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                                        ScreenActionPayload.setUrl(blockPos, side.id, finalUrl).toPacket());
                            }
                            if (be instanceof ScreenBlockEntity screen) {
                                screen.setScreenURL(side, finalUrl);
                            }
                        } catch (Exception e) {
                        }
                    }
                    this.onClose();
                }).bounds(this.width / 2 - 100, this.height / 2 + 10, 95, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("webdisplays.gui.seturl.cancel"),
                button -> this.onClose()
        ).bounds(this.width / 2 + 5, this.height / 2 + 10, 95, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        guiGraphics.fill(urlField.getX() - 2, urlField.getY() - 2, urlField.getX() + urlField.getWidth() + 2, urlField.getY() + urlField.getHeight() + 2, 0xFF555555);
        urlField.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
