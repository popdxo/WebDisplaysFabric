package net.montoyo.wd.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;

import java.util.List;
import java.util.UUID;

/**
 * The Hybrid remote. The display owner links one mouse by using it on the display; while the display is in Hybrid
 * mode, only the player holding that exact mouse (either hand) can control it - the owner included - so there is
 * never more than one controller.
 * <p>
 * A display keeps its remote until it is unlinked in the display config, and a linked mouse cannot be linked to
 * another display until its own link is gone. Once a link is removed, the old mouse notices (next time it is in a
 * player's inventory near its display) and drops its link data, so its tooltip no longer claims to be linked.
 */
public class MouseItem extends Item {
    private static final String TAG_LINK = "WDRemoteLink";
    private static final String TAG_POS = "WDRemotePos";
    private static final String TAG_SIDE = "WDRemoteSide";
    private static final String TAG_DIM = "WDRemoteDim";

    /** What a mouse's stored link points at, as far as the server can tell right now. */
    private enum LinkState { NONE, VALID, STALE, UNKNOWN }

    public MouseItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        BlockSide side = BlockSide.fromDirection(context.getClickedFace());
        ScreenBlockEntity display = ScreenBlockEntity.findCovering(level, context.getClickedPos(), side);
        if (display == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ScreenData data = display.getScreen(side);
        ItemStack stack = context.getItemInHand();
        if (data == null) return InteractionResult.SUCCESS;
        if (isLinkedTo(stack, data)) {
            player.displayClientMessage(Component.literal("This mouse is already this display's remote."), true);
            return InteractionResult.SUCCESS;
        }
        boolean owner = data.ownerUuid != null ? player.getUUID().toString().equals(data.ownerUuid)
                : player.getName().getString().equals(data.owner);
        if (!owner) {
            player.displayClientMessage(Component.literal("Only the display owner can link a remote to it."), true);
            return InteractionResult.SUCCESS;
        }

        // This mouse: free it only if its old link is provably gone.
        LinkState mine = linkState((ServerLevel) level, stack);
        if (mine == LinkState.STALE) {
            clearLink(stack);
        } else if (mine != LinkState.NONE) {
            CompoundTag tag = stack.getTag();
            BlockPos linkedPos = BlockPos.of(tag.getLong(TAG_POS));
            player.displayClientMessage(Component.literal("This mouse is already the remote for the display at "
                    + linkedPos.toShortString() + ". Unlink it in that display's config first."), true);
            return InteractionResult.SUCCESS;
        }
        // This display: one remote at a time, replaced only after an explicit unlink.
        if (data.remoteLinkId != null) {
            player.displayClientMessage(Component.literal(
                    "This display already has a remote. Unlink it in the display config first."), true);
            return InteractionResult.SUCCESS;
        }

        String link = UUID.randomUUID().toString();
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_LINK, link);
        tag.putLong(TAG_POS, display.getBlockPos().asLong());
        tag.putInt(TAG_SIDE, side.id);
        tag.putString(TAG_DIM, level.dimension().location().toString());
        display.setRemoteLink(side, link);
        player.displayClientMessage(Component.literal(
                "Remote linked. In Hybrid mode only whoever holds this mouse can control the display (you included)."), true);
        return InteractionResult.SUCCESS;
    }

    /** Server: once a second, drop link data whose display no longer accepts it (unlinked or removed). */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || level.getGameTime() % 20 != 0 || !(level instanceof ServerLevel serverLevel)) return;
        if (linkState(serverLevel, stack) == LinkState.STALE) clearLink(stack);
    }

    private static LinkState linkState(ServerLevel current, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_LINK)) return LinkState.NONE;
        ServerLevel level = current;
        if (tag.contains(TAG_DIM)) {
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString(TAG_DIM));
            level = dim == null ? null : current.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dim));
            if (level == null) return LinkState.UNKNOWN;
        }
        BlockPos pos = BlockPos.of(tag.getLong(TAG_POS));
        if (!level.isLoaded(pos)) return LinkState.UNKNOWN;
        BlockSide side = BlockSide.fromInt(tag.getInt(TAG_SIDE));
        // The display may have been handed to another anchor block (auto size); find whatever covers the old spot.
        ScreenBlockEntity display = ScreenBlockEntity.findCovering(level, pos, side);
        ScreenData data = display == null ? null : display.getScreen(side);
        if (data == null || !tag.getString(TAG_LINK).equals(data.remoteLinkId)) return LinkState.STALE;
        if (!display.getBlockPos().equals(pos)) tag.putLong(TAG_POS, display.getBlockPos().asLong());
        return LinkState.VALID;
    }

    private static void clearLink(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        tag.remove(TAG_LINK);
        tag.remove(TAG_POS);
        tag.remove(TAG_SIDE);
        tag.remove(TAG_DIM);
        if (tag.isEmpty()) stack.setTag(null);
    }

    public static boolean isLinkedTo(ItemStack stack, ScreenData data) {
        if (data == null || data.remoteLinkId == null || !(stack.getItem() instanceof MouseItem)) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && data.remoteLinkId.equals(tag.getString(TAG_LINK));
    }

    /** Does the player carry this display's linked remote anywhere in their inventory (hands included)? */
    public static boolean hasRemote(Player player, ScreenData data) {
        return player != null && data != null && data.remoteLinkId != null
                && player.getInventory().hasAnyMatching(stack -> isLinkedTo(stack, data));
    }

    /** Is the player holding this display's linked remote in either hand? */
    public static boolean holdsRemote(Player player, ScreenData data) {
        return player != null && (isLinkedTo(player.getMainHandItem(), data) || isLinkedTo(player.getOffhandItem(), data));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_LINK)) {
            BlockPos pos = BlockPos.of(tag.getLong(TAG_POS));
            tooltip.add(Component.literal("Remote for display at " + pos.toShortString()
                    + " (" + BlockSide.fromInt(tag.getInt(TAG_SIDE)).name().toLowerCase() + ")").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("Hold to point at and click displays. Use on a display you own to make "
                    + "this its Hybrid remote.").withStyle(ChatFormatting.GRAY));
        }
    }
}
