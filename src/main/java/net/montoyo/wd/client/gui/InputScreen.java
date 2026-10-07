package net.montoyo.wd.client.gui;

import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.montoyo.wd.network.ScreenActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.client.ScreenCursorTracker;
import net.montoyo.wd.client.ClientBookmarks;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class InputScreen extends Screen {

    private final BlockPos screenPos;
    private final BlockSide screenSide;
    private EditBox addressBar;
    private int tabButtonStartX;
    private int tabButtonWidth;
    private int plusButtonX;
    private final List<Button> tabButtons = new ArrayList<>();

    public InputScreen(BlockPos screenPos, BlockSide screenSide) {
        super(Component.literal("WebDisplays Input"));
        this.screenPos = screenPos;
        this.screenSide = screenSide;
    }

    @Override
    protected void init() {
        int backX = 6;
        int controlY = 7;
        int backWidth = 22;
        int forwardWidth = 22;
        int closeWidth = 22;
        int controlGap = 2;
        int goWidth = 34;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> navigateHistory(true))
                .bounds(backX, controlY, backWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> navigateHistory(false))
                .bounds(backX + backWidth + controlGap, controlY, forwardWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> closeTab())
                .bounds(backX + backWidth + controlGap + forwardWidth + controlGap, controlY, closeWidth, 20).build());
        int addressX = backX + backWidth + controlGap + forwardWidth + controlGap + closeWidth + controlGap + 4;
        int goX = width - goWidth - 6;
        int addressWidth = Math.max(40, goX - addressX - 4);
        addressBar = new EditBox(font, addressX, controlY, addressWidth, 20, Component.literal("Address"));
        addressBar.setMaxLength(2048);
        addressBar.setValue(getCurrentUrl());
        addRenderableWidget(addressBar);
        addRenderableWidget(Button.builder(Component.literal("Go"), button -> navigate())
                .bounds(goX, controlY, goWidth, 20).build());

        ScreenData data = getScreenData();
        int tabCount = data == null ? 0 : data.tabCount();
        tabButtonStartX = 6;
        int tabAreaWidth = Math.max(0, width - 36);
        tabButtonWidth = tabCount == 0 ? 0 : Math.max(1,
                Math.min(220, (tabAreaWidth - 24 - Math.max(0, tabCount - 1) * 2) / tabCount));
        tabButtons.clear();
        if (data != null) {
            for (int i = 0; i < tabCount; i++) {
                final int tab = i;
                Button tabButton = Button.builder(Component.literal(getTabTitle(data.tab(i), i)), button -> selectTab(tab))
                        .bounds(tabButtonStartX + i * (tabButtonWidth + 2), 32, tabButtonWidth, 20).build();
                tabButtons.add(tabButton);
                addRenderableWidget(tabButton);
            }
        }
        int plusX = tabButtonStartX + tabCount * (tabButtonWidth + 2);
        plusButtonX = Math.min(plusX, Math.max(6, width - 28));
        addRenderableWidget(Button.builder(Component.literal("+"), button -> addTab())
                .bounds(plusButtonX, 32, 22, 20).build());

        int bookmarkY = 56;
        addRenderableWidget(Button.builder(Component.literal("★"), button -> saveBookmark())
                .bounds(6, bookmarkY, 22, 18).build());
        {
            int x = 32;
            for (String url : ClientBookmarks.urls()) {
                int available = width - x - 6;
                if (available < 96) break;
                int urlWidth = Math.min(220, available - 23);
                String label = clipText(url, urlWidth - 12);
                addRenderableWidget(Button.builder(Component.literal(label), button -> openBookmark(url))
                        .bounds(x, bookmarkY, urlWidth, 18).build());
                addRenderableWidget(Button.builder(Component.literal("×"), button -> removeBookmark(url))
                        .bounds(x + urlWidth + 2, bookmarkY, 21, 18).build());
                x += urlWidth + 26;
            }
        }
    }

    private void saveBookmark() {
        String url = getCurrentUrl();
        if (url == null || url.isBlank() || url.length() > 2048 || !ClientBookmarks.add(url)) return;
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.addBookmark(screenPos, screenSide.id, url).toPacket());
        rebuildWidgets();
    }

    private void removeBookmark(String url) {
        if (!ClientBookmarks.remove(url)) return;
        ClientPlayNetworking.send(new ResourceLocation("webdisplays", "screen_action"),
                ScreenActionPayload.removeBookmark(url).toPacket());
        rebuildWidgets();
    }

    private void openBookmark(String url) {
        Object browser = getBrowser();
        if (browser == null) return;
        try {
            if (MCEFHelper.loadBrowserUrl(browser, ScreenBlockEntity.url(url))) refreshAddress();
        } catch (java.io.IOException ignored) {
        }
    }

    private String clipText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) end--;
        return end == 0 ? "" : text.substring(0, end) + "…";
    }

    private void navigate() {
        Object browser = getBrowser();
        if (browser == null || addressBar == null) return;
        String value = addressBar.getValue().trim();
        if (value.isEmpty()) return;
        try {
            String url = ScreenBlockEntity.url(value);
            if (!MCEFHelper.loadBrowserUrl(browser, url)) return;
            addressBar.setValue(url);
        } catch (java.io.IOException e) {
            return;
        }
        addressBar.setFocused(false);
        setFocused(null);
    }

    private void navigateHistory(boolean back) {
        Object browser = getBrowser();
        if (browser == null) return;
        if (back) MCEFHelper.goBack(browser);
        else MCEFHelper.goForward(browser);
        refreshAddress();
    }

    private void selectTab(int index) {
        ScreenData data = getScreenData();
        if (data != null && data.selectTab(index)) {
            ScreenCursorTracker.clear();
            rebuildWidgets();
        }
    }

    private void addTab() {
        ScreenData data = getScreenData();
        if (data == null) return;
        Object browser = data.addTab(data.resolution.x, data.resolution.y);
        if (browser != null) {
            ScreenBlockEntity.ensureWindowOpenOverride(browser);
            ScreenCursorTracker.clear();
            rebuildWidgets();
        }
    }

    private void closeTab() {
        ScreenData data = getScreenData();
        if (data == null || data.tabCount() <= 1) return;
        int oldIndex = data.activeTab();
        data.removeTab(oldIndex);
        ScreenCursorTracker.clear();
        rebuildWidgets();
    }

    private String getTabTitle(Object browser, int index) {
        String title = MCEFHelper.getBrowserTitle(browser);
        if (title == null || title.isBlank()) {
            title = MCEFHelper.getBrowserUrl(browser);
        }
        if (title == null || title.isBlank() || "about:blank".equals(title)) {
            return "New tab";
        }
        int maxWidth = Math.max(0, tabButtonWidth - 12);
        if (font.width(title) <= maxWidth) return title;
        String ellipsis = "…";
        int end = title.length();
        while (end > 0 && font.width(title.substring(0, end) + ellipsis) > maxWidth) end--;
        return end == 0 ? "" : title.substring(0, end) + ellipsis;
    }

    private void refreshAddress() {
        if (addressBar != null) addressBar.setValue(getCurrentUrl());
    }

    private String getCurrentUrl() {
        Object browser = getBrowser();
        return browser != null ? MCEFHelper.getBrowserUrl(browser) : "";
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (addressBar != null && addressBar.isFocused()) {
            if (keyCode == 257) navigate();
            else super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD)
                && hasControlDown()) {
            ScreenCursorTracker.adjustZoom(getScreenData(), 0.1);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT)
                && hasControlDown()) {
            ScreenCursorTracker.adjustZoom(getScreenData(), -0.1);
            return true;
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        Object browser = getBrowser();
        if (browser != null) MCEFHelper.sendKeyPress(browser, keyCode, scanCode, modifiers);
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (addressBar != null && addressBar.isFocused()) {
            super.keyReleased(keyCode, scanCode, modifiers);
            return true;
        }
        Object browser = getBrowser();
        if (browser != null) MCEFHelper.sendKeyRelease(browser, keyCode, scanCode, modifiers);
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (addressBar != null && addressBar.isFocused()) {
            super.charTyped(codePoint, modifiers);
            return true;
        }
        if (super.charTyped(codePoint, modifiers)) return true;
        Object browser = getBrowser();
        if (browser != null) MCEFHelper.sendKeyEvent(browser, codePoint);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 32 && mouseY < 52) {
            if (mouseX >= plusButtonX && mouseX < plusButtonX + 22) {
                addTab();
                return true;
            }
            ScreenData data = getScreenData();
            if (data != null && tabButtonWidth > 1) {
                int index = (int) ((mouseX - tabButtonStartX) / (tabButtonWidth + 2));
                int offset = (int) ((mouseX - tabButtonStartX) % (tabButtonWidth + 2));
                if (index >= 0 && index < data.tabCount() && offset < tabButtonWidth) {
                    selectTab(index);
                    return true;
                }
            }
        }
        if (button == 0 && mouseY >= 7 && mouseY < 27
                && mouseX >= width - 40 && mouseX < width - 6) {
            navigate();
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (cursor != null && cursor.screenData != null && cursor.screenData.browser != null) {
            long now = System.currentTimeMillis();
            int clickCount = (now - cursor.screenData.lastClickTime < 500) ? 2 : 1;
            cursor.screenData.lastClickTime = now;
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, button, false, clickCount);
            MCEFHelper.sendMouseClick(cursor.screenData.browser, cursor.pixelX, cursor.pixelY, button, true, clickCount);
        }
        return true;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.fill(0, 0, width, 78, 0xDD20242A);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        ScreenData data = getScreenData();
        if (data != null) {
            for (int i = 0; i < Math.min(data.tabCount(), tabButtons.size()); i++) {
                tabButtons.get(i).setMessage(Component.literal(getTabTitle(data.tab(i), i)));
            }
        }
        guiGraphics.drawString(font, "Keyboard types into the page unless the address bar is focused · Esc exits", 8, height - 14, 0xFFBBBBBB, false);
    }

    @Override
    public void onClose() {
        ScreenCursorTracker.clear();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public boolean isFor(BlockPos pos, BlockSide side) {
        return screenPos.equals(pos) && screenSide == side;
    }

    public BlockPos getScreenPos() { return screenPos; }
    public BlockSide getScreenSide() { return screenSide; }

    private ScreenData getScreenData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(screenPos) instanceof ScreenBlockEntity screen) {
            return screen.getScreen(screenSide);
        }
        return null;
    }

    private Object getBrowser() {
        ScreenData data = getScreenData();
        return data != null ? data.browser : null;
    }
}
