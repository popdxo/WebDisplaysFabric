package net.montoyo.wd.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorldBookmarks extends SavedData {
    private static final String DATA_NAME = "webdisplays_bookmarks";
    private final List<String> urls = new ArrayList<>();

    public static WorldBookmarks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WorldBookmarks::load, WorldBookmarks::new, DATA_NAME);
    }

    public static WorldBookmarks load(CompoundTag tag) {
        WorldBookmarks data = new WorldBookmarks();
        ListTag list = tag.getList("urls", Tag.TAG_STRING);
        for (int i = 0; i < list.size() && data.urls.size() < 100; i++) {
            String url = list.getString(i);
            if (!url.isBlank() && url.length() <= 2048 && !data.urls.contains(url)) data.urls.add(url);
        }
        return data;
    }

    public boolean add(String url) {
        if (url == null || url.isBlank() || url.length() > 2048 || urls.contains(url) || urls.size() >= 100) return false;
        urls.add(url);
        setDirty();
        return true;
    }

    public boolean remove(String url) {
        if (url == null || !urls.remove(url)) return false;
        setDirty();
        return true;
    }

    public List<String> urls() {
        return Collections.unmodifiableList(urls);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (String url : urls) list.add(StringTag.valueOf(url));
        tag.put("urls", list);
        return tag;
    }
}
