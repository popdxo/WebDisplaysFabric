package net.montoyo.wd;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.montoyo.wd.command.ScreenCommand;
import net.montoyo.wd.network.ScreenActionPayload;
import net.montoyo.wd.network.ServerNetHandler;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.Log;

import java.net.MalformedURLException;
import java.net.URL;

public class WebDisplays implements ModInitializer {
    public static final String MOD_ID = "webdisplays";
    public static final Gson GSON = new Gson();
    public static final String BLACKLIST_URL = "mod://webdisplays/blacklisted.html";

    // Default values for config
    public double unloadDistance2 = 4.0;
    public double loadDistance2 = 3.0;
    public double padResX = 640;
    public double padResY = 400;
    public float ytVolume = 1.0f;
    public float avDist100 = 2.0f;
    public float avDist0 = 5.0f;
    public int miniservPort = 0;
    public long miniservQuota = 0;

    private static WebDisplays instance;

    public static WebDisplays getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;
        Log.info("WebDisplays initializing (Fabric)...");

        // Register all blocks, items, block entities, sounds, creative tab
        WDRegistries.register();


        // Register server-side network handlers
        ServerNetHandler.register();

        // Init command items and register command
        ScreenCommand.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ScreenCommand.register(dispatcher);
        });

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Register player connection events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Log.info("Player joined: {}", handler.getPlayer().getName().getString());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Log.info("Player left: {}", handler.getPlayer().getName().getString());
        });

        Log.info("WebDisplays initialized!");
    }

    private void onServerStarting(MinecraftServer server) {
        Log.info("Server starting...");
    }

    private void onServerStopping(MinecraftServer server) {
        Log.info("Server stopping...");
    }

    public static boolean isSiteBlacklisted(String url) {
        try {
            URL url2 = new URL(addProtocol(url));
            // For now, no blacklist checking - config not implemented yet
            return false;
        } catch (MalformedURLException ex) {
            return false;
        }
    }

    public static String applyBlacklist(String url) {
        return isSiteBlacklisted(url) ? BLACKLIST_URL : url;
    }

    private static String addProtocol(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }
}