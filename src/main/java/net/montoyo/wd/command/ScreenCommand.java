package net.montoyo.wd.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.montoyo.wd.registry.WDRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScreenCommand {

    private static final Map<String, ItemStack> ITEMS = new LinkedHashMap<>();

    public static void init() {
        ITEMS.put("screen", new ItemStack(WDRegistries.SCREEN_ITEM));
        ITEMS.put("configurator", new ItemStack(WDRegistries.CONFIGURATOR));
        ITEMS.put("linker", new ItemStack(WDRegistries.LINKER));
        ITEMS.put("keyboard", new ItemStack(WDRegistries.KEYBOARD_ITEM));
        ITEMS.put("mouse", new ItemStack(WDRegistries.MOUSE_ITEM));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("screen")
                .requires(source -> source.hasPermission(0))
                .executes(ctx -> {
                    printHelp(ctx.getSource());
                    return 1;
                })
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> {
                            String item = StringArgumentType.getString(ctx, "item");
                            return giveItem(ctx.getSource(), item, 1);
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    String item = StringArgumentType.getString(ctx, "item");
                                    int count = IntegerArgumentType.getInteger(ctx, "count");
                                    return giveItem(ctx.getSource(), item, count);
                                }))
                )
        );
    }

    private static int giveItem(CommandSourceStack source, String name, int count) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        ItemStack stack = ITEMS.get(name);
        if (stack == null) {
            source.sendFailure(Component.literal("Unknown item: " + name + ". Use /screen for list"));
            return 0;
        }

        ItemStack give = stack.copy();
        give.setCount(count);
        player.getInventory().add(give);
        source.sendSuccess(() -> Component.literal("Gave " + count + "x " + name), true);
        return 1;
    }

    private static void printHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6=== WebDisplays Items ==="), false);
        for (String name : ITEMS.keySet()) {
            source.sendSuccess(() -> Component.literal("§e/screen " + name + " [count]"), false);
        }
    }
}
