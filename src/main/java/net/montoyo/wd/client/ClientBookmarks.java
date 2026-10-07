package net.montoyo.wd.client;

import java.util.ArrayList;
import java.util.List;

public final class ClientBookmarks {
    private static final List<String> URLS = new ArrayList<>();

    private ClientBookmarks() {}

    public static synchronized List<String> urls() {
        return List.copyOf(URLS);
    }

    public static synchronized void set(List<String> urls) {
        URLS.clear();
        URLS.addAll(urls);
    }

    public static synchronized boolean add(String url) {
        if (url == null || url.isBlank() || url.length() > 2048 || URLS.contains(url) || URLS.size() >= 100) return false;
        URLS.add(url);
        return true;
    }

    public static synchronized boolean remove(String url) {
        return URLS.remove(url);
    }
}
