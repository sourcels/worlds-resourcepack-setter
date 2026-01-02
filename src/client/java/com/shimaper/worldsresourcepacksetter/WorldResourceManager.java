package com.shimaper.worldsresourcepacksetter;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.shimaper.worldsresourcepacksetter.WrsCommon.LOGGER;

public class WorldResourceManager {
    private final ConfigManager configManager;
    private final Path mcRoot = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();

    public WorldResourceManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void checkPendingWorldsOnStart() {
        Iterator<String> iterator = configManager.getConfig().pendingWorlds.iterator();
        boolean changed = false;

        while (iterator.hasNext()) {
            String pathStr = iterator.next();
            Path worldPath = mcRoot.resolve(pathStr).toAbsolutePath().normalize();

            if (Files.exists(worldPath)) {
                if (tryCleanAndApplyLatest(worldPath)) {
                    iterator.remove();
                    changed = true;
                    LOGGER.info("[WRS] Applied resources to: {}", pathStr);
                }
            } else {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) configManager.save();
    }

    public Path resolveExportPath(Path currentWorldPath) {
        WrsCommon.ModConfig cfg = configManager.getConfig();
        Path targetFolder;

        if (cfg.savePath == null || cfg.savePath.trim().isEmpty()) {
            targetFolder = currentWorldPath;
        } else {
            targetFolder = Paths.get(cfg.savePath).toAbsolutePath().normalize();
        }

        try {
            if (!Files.exists(targetFolder)) {
                Files.createDirectories(targetFolder);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create target directory", e);
            return null;
        }

        if (Files.exists(targetFolder.resolve("level.dat"))) {
            Path mainResources = targetFolder.resolve("resources.zip");

            if (!Files.exists(mainResources)) {
                return mainResources;
            }

            String prefix = cfg.customFileName;
            if (prefix == null || prefix.isEmpty()) prefix = WrsCommon.DEFAULT_FILENAME;
            if (prefix.endsWith(".zip")) prefix = prefix.substring(0, prefix.length() - 4);

            return findNextIndexedPath(targetFolder, prefix + "_");
        }

        else {
            String fileName = cfg.customFileName;
            if (fileName == null || fileName.isEmpty()) fileName = WrsCommon.DEFAULT_FILENAME;
            if (!fileName.endsWith(".zip")) fileName += ".zip";

            Path targetFile = targetFolder.resolve(fileName);

            if (Files.exists(targetFile)) {
                String nameWithoutExt = fileName.substring(0, fileName.length() - 4);
                return findNextIndexedPath(targetFolder, nameWithoutExt + "_");
            }

            return targetFile;
        }
    }

    private Path findNextIndexedPath(Path folder, String prefix) {
        int i = 1;
        while (Files.exists(folder.resolve(prefix + i + ".zip"))) {
            i++;
        }
        return folder.resolve(prefix + i + ".zip");
    }

    private boolean tryCleanAndApplyLatest(Path worldPath) {
        try {
            WrsCommon.ModConfig cfg = configManager.getConfig();
            String baseName = cfg.customFileName;
            if (baseName == null || baseName.isEmpty()) baseName = WrsCommon.DEFAULT_FILENAME;

            if (baseName.endsWith(".zip")) baseName = baseName.substring(0, baseName.length() - 4);

            List<Path> allCandidates = new ArrayList<>();
            Path latestFile = null;
            int maxIndex = -1;

            Pattern pattern = Pattern.compile(Pattern.quote(baseName) + "_(\\d+)\\.zip");

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldPath, baseName + "_*.zip")) {
                for (Path entry : stream) {
                    Matcher m = pattern.matcher(entry.getFileName().toString());
                    if (m.matches()) {
                        allCandidates.add(entry);
                        int idx = Integer.parseInt(m.group(1));
                        if (idx > maxIndex) {
                            latestFile = entry;
                            maxIndex = idx;
                        }
                    }
                }
            }

            if (latestFile != null) {
                Path target = worldPath.resolve("resources.zip");
                Files.deleteIfExists(target);
                Files.move(latestFile, target);

                for (Path candidate : allCandidates) {
                    if (!candidate.equals(latestFile)) {
                        try {
                            Files.deleteIfExists(candidate);
                        } catch (Exception ignored) {
                            //
                        }
                    }
                }
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Cleanup error in world: {}", worldPath, e);
        }
        return false;
    }
}