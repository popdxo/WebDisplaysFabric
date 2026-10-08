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
    public final List<String> tabUrls = new ArrayList<>();
    private int activeTab = 0;
    public int mouseType;
    public Rotation rotation;
    public boolean autoVolume;
    public boolean autoSize = true;
    public boolean autoResolution = true;
    public String owner;
    public String ownerUuid;
    public String url;
    public double mediaTime;
    public boolean mediaPlaying;
    public long mediaRevision;
    public long mediaUpdatedAt;
    public boolean mediaReporterInstalled;
    public String lastMediaEvent = "";
    public boolean mediaMarkerObserved;
    public boolean mediaMarkerMissingLogged;
    public long lastMediaTitleDiagnosticTime;
    public volatile String latestMediaMessage = "";
    public boolean mediaPacketSentLogged;
    public boolean mediaServerAcceptedLogged;
    public boolean mediaOwnerDiagnosticLogged;
    public long lastMediaPollTime;

    public long lastClickTime;
    public String lastUrl = ""; // for detecting page navigation
    public String lastReportedUrl = "";
    public double zoomLevel = 1.0; // browser page zoom (1.0 = 100%)

    public ScreenData(BlockSide side, Vector2i resolution, Vector2i size, String owner) {
        this.side = side;
        this.resolution = resolution;
        this.size = size;
        this.owner = owner;
        this.url = null;
        this.tabUrls.add("about:blank");
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

    public int browserTabsCount() {
        return browserTabs.size();
    }

    public void reconcileTabs(List<String> urls, int selected) {
        if (browser == null) return;
        while (browserTabs.size() > urls.size() && browserTabs.size() > 1) {
            Object removed = browserTabs.remove(browserTabs.size() - 1);
            MCEFHelper.closeBrowser(removed);
        }
        while (browserTabs.size() < urls.size()) {
            Object created = MCEFHelper.createBrowser("about:blank", false, resolution.x, resolution.y);
            if (created == null) break;
            browserTabs.add(created);
        }
        tabUrls.clear();
        tabUrls.addAll(urls);
        int count = Math.min(browserTabs.size(), urls.size());
        for (int i = 0; i < count; i++) {
            String target = urls.get(i);
            String current = MCEFHelper.getBrowserUrl(browserTabs.get(i));
            if (target != null && !target.equals(current)) MCEFHelper.loadBrowserUrl(browserTabs.get(i), target);
        }
        selectTab(Math.max(0, Math.min(selected, browserTabs.size() - 1)));
    }

    public int activeTab() {
        return activeTab;
    }

    public void setActiveTabIndex(int index) {
        if (index >= 0 && index < tabUrls.size()) activeTab = index;
    }

    public boolean removeTabState(int index) {
        if (tabUrls.size() <= 1 || index < 0 || index >= tabUrls.size()) return false;
        tabUrls.remove(index);
        activeTab = Math.min(activeTab, tabUrls.size() - 1);
        return true;
    }

    public void addTabState() {
        if (tabUrls.size() < 32) {
            tabUrls.add("about:blank");
            activeTab = tabUrls.size() - 1;
        }
    }

    public void setTabUrl(int index, String value) {
        if (index >= 0 && index < tabUrls.size()) tabUrls.set(index, value);
    }

    public void installMediaReporter() {
        if (browser == null) return;
        mediaReporterInstalled = true;
        MCEFHelper.registerConsoleMessageListener(browser, message -> {
            if (message.startsWith("__WD_MEDIA__|")) latestMediaMessage = message;
        });
        MCEFHelper.injectJavascript(browser, "(function(){if(window.__wdMediaReporter)return;window.__wdMediaReporter=true;console.log('[WebDisplays] media reporter installed');window.__wdMediaEvent='';window.__wdMediaEventId=0;document.addEventListener('play',function(e){if(e.target instanceof HTMLMediaElement&&!window.__wdApplyingMedia){window.__wdMediaEvent='play';window.__wdMediaEventId++}},true);document.addEventListener('pause',function(e){if(e.target instanceof HTMLMediaElement&&!window.__wdApplyingMedia){window.__wdMediaEvent='pause';window.__wdMediaEventId++}},true);setInterval(function(){try{var m=document.querySelector('video,audio');var t=m?m.currentTime:(window.yt&&yt.player&&yt.player.getCurrentTime?yt.player.getCurrentTime():null);var p=m?m.paused:(window.yt&&yt.player&&yt.player.getPlayerState?yt.player.getPlayerState()!==1:true);if(t===null)return;if(!window.__wdMediaFound){window.__wdMediaFound=true;console.log('[WebDisplays] media state bridge active time='+t+' playing='+(!p))}console.log('__WD_MEDIA__|'+(window.__wdMediaEvent||'tick')+'|'+t+'|'+(p?'0':'1')+'|'+window.__wdMediaEventId+'|') }catch(e){}},500)} )()");
    }

    public void applyMediaState() {
        if (browser == null) return;
        double elapsed = Math.max(0, (System.currentTimeMillis() - mediaUpdatedAt) / 1000.0);
        double targetTime = mediaTime + (mediaPlaying ? elapsed : 0);
        String js = "(function(){try{var m=document.querySelector('video,audio');if(!m)return;window.__wdApplyingMedia=true;var t="
                + targetTime + ";if(Math.abs(m.currentTime-t)>1.25)m.currentTime=t;if(" + mediaPlaying
                + "){if(m.paused){var p=m.play();if(p&&p.catch)p.catch(function(){})}}else if(!m.paused)m.pause();setTimeout(function(){window.__wdApplyingMedia=false},250)}catch(e){window.__wdApplyingMedia=false}})()";
        MCEFHelper.injectJavascript(browser, js);
    }

    public void setBrowser(Object newBrowser) {
        browser = newBrowser;
        browserTabs.clear();
        if (newBrowser != null) browserTabs.add(newBrowser);
        if (tabUrls.isEmpty()) tabUrls.add(url == null ? "about:blank" : url);
        activeTab = 0;
    }

    public Object addTab(int width, int height) {
        Object newBrowser = MCEFHelper.createBrowser("about:blank", false, width, height);
        if (newBrowser != null) {
            browserTabs.add(newBrowser);
            tabUrls.add("about:blank");
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
        if (index < tabUrls.size()) tabUrls.remove(index);
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