package net.montoyo.wd.entity;

import net.montoyo.wd.client.mcef.MCEFHelper;
import java.util.ArrayList;
import java.util.List;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;

public class ScreenData {
    public BlockSide side;
    public Vector2i resolution;
    public Vector2i size;
    public Object browser; // active MCEFBrowser at runtime via reflection
    private final List<Object> browserTabs = new ArrayList<>();
    private int activeTab = 0;
    public int mouseType;
    public Rotation rotation;
    public boolean autoVolume;
    public boolean autoSize = true;
    public boolean autoResolution = true;
    public String owner;
    public String url;

    public long lastClickTime;
    public String lastUrl = ""; // for detecting page navigation
    public double zoomLevel = 1.0; // browser page zoom (1.0 = 100%)

    public ScreenData(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        this.side = side;
        this.resolution = resolution;
        this.size = size;
        this.owner = owner;
        this.url = null;
        this.rotation = Rotation.ROT_0;
        this.autoVolume = false;
        this.mouseType = 0;
        this.browser = null;
        this.lastClickTime = 0;
    }

    public boolean isLoaded() {
        return browser != null;
    }

    public int tabCount() {
        return browserTabs.size();
    }

    public int activeTab() {
        return activeTab;
    }

    public void setBrowser(Object newBrowser) {
        browser = newBrowser;
        browserTabs.clear();
        if (newBrowser != null) browserTabs.add(newBrowser);
        activeTab = 0;
    }

    public Object addTab(int width, int height) {
        Object newBrowser = MCEFHelper.createBrowser("about:blank", false, width, height);
        if (newBrowser != null) {
            browserTabs.add(newBrowser);
            activeTab = browserTabs.size() - 1;
            browser = newBrowser;
        }
        return newBrowser;
    }

    public Object tab(int index) {
        return index >= 0 && index < browserTabs.size() ? browserTabs.get(index) : null;
    }

    public void resizeBrowsers(int width, int height) {
        for (Object tab : browserTabs) {
            MCEFHelper.resizeBrowser(tab, width, height);
        }
    }

    public boolean selectTab(int index) {
        if (index < 0 || index >= browserTabs.size()) return false;
        activeTab = index;
        browser = browserTabs.get(index);
        return true;
    }

    public boolean removeTab(int index) {
        if (browserTabs.size() <= 1 || index < 0 || index >= browserTabs.size()) return false;
        Object removed = browserTabs.remove(index);
        MCEFHelper.closeBrowser(removed);
        activeTab = Math.min(activeTab, browserTabs.size() - 1);
        browser = browserTabs.get(activeTab);
        return true;
    }

    public void unload() {
        for (Object tab : browserTabs) MCEFHelper.closeBrowser(tab);
        browserTabs.clear();
        browser = null;
        activeTab = 0;
    }
}