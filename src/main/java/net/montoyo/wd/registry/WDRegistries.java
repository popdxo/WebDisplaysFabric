package net.montoyo.wd.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.montoyo.wd.block.KeyboardBlockLeft;
import net.montoyo.wd.block.KeyboardBlockRight;
import net.montoyo.wd.block.ScreenBlock;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;

import java.util.function.Function;
import java.util.function.Supplier;

public class WDRegistries {

    // === BLOCKS ===
    public static final ScreenBlock SCREEN_BLOCK = registerBlock("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL)));
    public static final KeyboardBlockLeft KEYBOARD_LEFT = registerBlock("kb_left",
            () -> new KeyboardBlockLeft(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL)));
    public static final KeyboardBlockRight KEYBOARD_RIGHT = registerBlock("kb_right",
            () -> new KeyboardBlockRight(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL)));


    // === BLOCK ITEMS ===
    public static final Item SCREEN_ITEM = registerBlockItem("screen", SCREEN_BLOCK);
    public static final Item KEYBOARD_ITEM = registerItem("keyboard", net.montoyo.wd.item.KeyboardItem::new);
    public static final Item KEYBOARD_LEFT_ITEM = registerBlockItem("kb_left", KEYBOARD_LEFT);
    public static final Item KEYBOARD_RIGHT_ITEM = registerBlockItem("kb_right", KEYBOARD_RIGHT);


    // === ITEMS ===
    public static final Item CONFIGURATOR = registerItem("screencfg", net.montoyo.wd.item.ItemScreenConfigurator::new);
    public static final Item LINKER = registerItem("linker", net.montoyo.wd.item.ItemLinker::new);

    // === BLOCK ENTITIES ===
    public static BlockEntityType<ScreenBlockEntity> SCREEN_BLOCK_ENTITY;
    public static BlockEntityType<KeyboardBlockEntity> KEYBOARD_BLOCK_ENTITY;

    // === SOUNDS ===
    public static SoundEvent KEYBOARD_TYPE;


    // === CREATIVE TAB ===
    public static CreativeModeTab CREATIVE_TAB;

    private static <T extends Block> T registerBlock(String name, Supplier<T> factory) {
        T block = factory.get();
        net.minecraft.core.Registry.register(BuiltInRegistries.BLOCK, id(name), block);
        return block;
    }

    private static Item registerBlockItem(String name, Block block) {
        Item item = new BlockItem(block, new Item.Properties());
        net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, id(name), item);
        return item;
    }

    private static Item registerItem(String name, Function<Item.Properties, Item> factory) {
        Item item = factory.apply(new Item.Properties());
        net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, id(name), item);
        return item;
    }

    private static SoundEvent registerSound(String name) {
        ResourceLocation soundId = id(name);
        SoundEvent event = SoundEvent.createVariableRangeEvent(soundId);
        net.minecraft.core.Registry.register(BuiltInRegistries.SOUND_EVENT, soundId, event);
        return event;
    }

    public static void register() {
        // Register sounds
        KEYBOARD_TYPE = registerSound("keyboard_type");


        // Register block entities
        SCREEN_BLOCK_ENTITY = net.minecraft.core.Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("screen"),
                FabricBlockEntityTypeBuilder.create(ScreenBlockEntity::new, SCREEN_BLOCK).build());
        KEYBOARD_BLOCK_ENTITY = net.minecraft.core.Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("kb_left"),
                FabricBlockEntityTypeBuilder.create(KeyboardBlockEntity::new, KEYBOARD_LEFT, KEYBOARD_RIGHT).build());

        // Register creative tab
        CREATIVE_TAB = net.minecraft.core.Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                id("main"),
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                        .title(Component.translatable("itemGroup.webdisplays"))
                        .icon(() -> new ItemStack(SCREEN_BLOCK))
                        .displayItems((params, output) -> {
                            output.accept(SCREEN_ITEM);
                            output.accept(KEYBOARD_ITEM);
                            output.accept(CONFIGURATOR);
                            output.accept(LINKER);
                        })
                        .build());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation("webdisplays", path);
    }
}
