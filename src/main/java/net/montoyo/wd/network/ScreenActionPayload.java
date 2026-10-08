package net.montoyo.wd.network;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

public record ScreenActionPayload(BlockPos pos, int sideOrdinal, String action, String extraData) {
    public static void write(FriendlyByteBuf buf, ScreenActionPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeVarInt(payload.sideOrdinal);
        buf.writeUtf(payload.action);
        buf.writeUtf(payload.extraData);
    }

    public static ScreenActionPayload read(FriendlyByteBuf buf) {
        return new ScreenActionPayload(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(), buf.readUtf());
    }

    public FriendlyByteBuf toPacket() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        write(buf, this);
        return buf;
    }

    public static final String ACTION_ADD_SCREEN = "add_screen";
    public static final String ACTION_REMOVE_SCREEN = "remove_screen";
    public static final String ACTION_SET_URL = "set_url";
    public static final String ACTION_ADD_TAB = "add_tab";
    public static final String ACTION_SELECT_TAB = "select_tab";
    public static final String ACTION_CLOSE_TAB = "close_tab";
    public static final String ACTION_SYNC_TAB_URL = "sync_tab_url";
    public static final String ACTION_MEDIA_STATE = "media_state";
    public static final String ACTION_SET_RESOLUTION = "set_resolution";
    public static final String ACTION_SET_ROTATION = "set_rotation";
    public static final String ACTION_SET_AUTO_SIZE = "set_auto_size";
    public static final String ACTION_SET_AUTO_RESOLUTION = "set_auto_resolution";
    public static final String ACTION_SET_DISPLAY_SIZE = "set_display_size";
    public static final String ACTION_ADD_BOOKMARK = "add_bookmark";
    public static final String ACTION_REMOVE_BOOKMARK = "remove_bookmark";
    public static final ResourceLocation BOOKMARK_SYNC = new ResourceLocation("webdisplays", "bookmark_sync");

    public static ScreenActionPayload addScreen(BlockPos pos, int sideOrdinal, String owner) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_ADD_SCREEN, owner);
    }

    public static ScreenActionPayload removeScreen(BlockPos pos, int sideOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_REMOVE_SCREEN, "");
    }

    public static ScreenActionPayload setUrl(BlockPos pos, int sideOrdinal, String url) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_URL, url);
    }

    public static ScreenActionPayload setUrl(BlockPos pos, int sideOrdinal, int tabIndex, String url) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SYNC_TAB_URL, tabIndex + "\n" + url);
    }

    public static ScreenActionPayload addTab(BlockPos pos, int sideOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_ADD_TAB, "");
    }

    public static ScreenActionPayload selectTab(BlockPos pos, int sideOrdinal, int index) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SELECT_TAB, Integer.toString(index));
    }

    public static ScreenActionPayload closeTab(BlockPos pos, int sideOrdinal, int index) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_CLOSE_TAB, Integer.toString(index));
    }

    public static ScreenActionPayload mediaState(BlockPos pos, int sideOrdinal, int tabIndex,
                                                  double timeSeconds, boolean playing, String event) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_MEDIA_STATE,
                tabIndex + "," + timeSeconds + "," + playing + "," + event);
    }

    public static ScreenActionPayload setResolution(BlockPos pos, int sideOrdinal, int width, int height) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_RESOLUTION, width + "," + height);
    }

    public static ScreenActionPayload setRotation(BlockPos pos, int sideOrdinal, int rotationOrdinal) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_ROTATION, String.valueOf(rotationOrdinal));
    }

    public static ScreenActionPayload setAutoSize(BlockPos pos, int sideOrdinal, boolean enabled) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_AUTO_SIZE, Boolean.toString(enabled));
    }

    public static ScreenActionPayload setAutoResolution(BlockPos pos, int sideOrdinal, boolean enabled) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_AUTO_RESOLUTION, Boolean.toString(enabled));
    }

    public static ScreenActionPayload setDisplaySize(BlockPos pos, int sideOrdinal, int width, int height) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_SET_DISPLAY_SIZE, width + "," + height);
    }

    public static ScreenActionPayload addBookmark(BlockPos pos, int sideOrdinal, String url) {
        return new ScreenActionPayload(pos, sideOrdinal, ACTION_ADD_BOOKMARK, url);
    }

    public static ScreenActionPayload removeBookmark(String url) {
        return new ScreenActionPayload(BlockPos.ZERO, 0, ACTION_REMOVE_BOOKMARK, url);
    }
}
