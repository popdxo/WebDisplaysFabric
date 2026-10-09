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
import net.montoyo.wd.stream.HybridWebService;
import net.montoyo.wd.utilities.Log;

import java.net.MalformedURLException;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    private final HybridWebService hybridWebService = new HybridWebService();
    private boolean hybridWebEnabled = true;
    private String hybridWebBind = "127.0.0.1";
    private String hybridWebPublicHost = "localhost";
    private int hybridWebPort = 8765;

    public static WebDisplays getInstance() {
        return instance;
    }

    public HybridWebService getHybridWebService() {
        return hybridWebService;
    }

    public String getHybridWebBaseUrl() {
        // localhost is treated as a trustworthy origin for browser display capture.
        return "http://" + hybridWebPublicHost + ":" + hybridWebPort;
    }

    @Override
    public void onInitialize() {
        instance = this;
        loadHybridWebConfig();
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
            net.minecraft.server.level.ServerPlayer leaving = handler.getPlayer();
            server.execute(() -> net.montoyo.wd.entity.ScreenBlockEntity.releaseOwnedBy(
                    leaving.getUUID(), leaving.getName().getString()));
        });

        Log.info("WebDisplays initialized!");
    }

    private void onServerStarting(MinecraftServer server) {
        Log.info("Server starting...");
        if (hybridWebEnabled) hybridWebService.start(hybridWebBind, hybridWebPort);
    }

    private void onServerStopping(MinecraftServer server) {
        hybridWebService.stop();
        Log.info("Server stopping...");
    }

    private void loadHybridWebConfig() {
        Path configFile = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("webdisplays-hybrid.properties");
        Properties properties = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream input = Files.newInputStream(configFile)) {
                properties.load(input);
            } catch (IOException exception) {
                Log.warning("Could not read Hybrid web config: {}", exception.getMessage());
            }
        }
        hybridWebEnabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
        hybridWebBind = properties.getProperty("bind", "127.0.0.1").trim();
        hybridWebPublicHost = properties.getProperty("publicHost", "localhost").trim();
        if (hybridWebPublicHost.isBlank() || hybridWebPublicHost.contains("/") || hybridWebPublicHost.contains(":")) {
            hybridWebPublicHost = "localhost";
        }
        try {
            hybridWebPort = Math.max(0, Math.min(65535,
                    Integer.parseInt(properties.getProperty("port", "8765").trim())));
        } catch (NumberFormatException exception) {
            hybridWebPort = 8765;
        }
        if (!Files.exists(configFile)) {
            properties.setProperty("enabled", Boolean.toString(hybridWebEnabled));
            properties.setProperty("bind", hybridWebBind);
            properties.setProperty("publicHost", hybridWebPublicHost);
            properties.setProperty("port", Integer.toString(hybridWebPort));
            try {
                Files.createDirectories(configFile.getParent());
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "WebDisplays Hybrid web service");
                }
            } catch (IOException exception) {
                Log.warning("Could not write Hybrid web config: {}", exception.getMessage());
            }
        }
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