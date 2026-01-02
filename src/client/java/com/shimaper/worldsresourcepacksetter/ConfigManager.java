package com.shimaper.worldsresourcepacksetter;

import net.fabricmc.loader.api.FabricLoader;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.shimaper.worldsresourcepacksetter.WrsCommon.GSON;
import static com.shimaper.worldsresourcepacksetter.WrsCommon.LOGGER;

public class ConfigManager {
    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("wrs_config.json");
    private final Path mcRoot = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private WrsCommon.ModConfig config = new WrsCommon.ModConfig();

    public void load() {
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                config = GSON.fromJson(reader, WrsCommon.ModConfig.class);
            } catch (Exception e) {
                LOGGER.error("Failed to load WRS config", e);
            }
        }
        if (config == null) config = new WrsCommon.ModConfig();
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            LOGGER.error("Failed to save WRS config", e);
        }
    }

    public WrsCommon.ModConfig getConfig() {
        return config;
    }

    public void addPendingWorld(Path absolutePath) {
        try {
            Path normalizedAbs = absolutePath.toAbsolutePath().normalize();
            String pathString;

            if (normalizedAbs.startsWith(mcRoot)) {
                pathString = mcRoot.relativize(normalizedAbs).toString().replace("\\", "/");
            } else {
                pathString = normalizedAbs.toString().replace("\\", "/");
            }

            if (!config.pendingWorlds.contains(pathString)) {
                config.pendingWorlds.add(pathString);
                save();
            }
        } catch (Exception e) {
            LOGGER.error("Error relativizing path", e);
        }
    }
}