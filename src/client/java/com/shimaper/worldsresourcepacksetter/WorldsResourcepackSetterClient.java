package com.shimaper.worldsresourcepacksetter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

public class WorldsResourcepackSetterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WRS");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String FILE_PREFIX = "wrs_resources_";
    private static final Pattern RESOURCE_PATTERN = Pattern.compile(FILE_PREFIX + "(\\d+)\\.zip");

    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("wrs_worlds.json");
    private Set<String> pendingWorlds = new HashSet<>();

    @Override
    public void onInitializeClient() {
        loadConfig();
        checkPendingWorldsOnStart();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("setResources")
                    .executes(context -> startExport(context, "force", "external"))
                    .then(ClientCommandManager.argument("compatibility", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                builder.suggest("force");
                                builder.suggest("compatible");
                                return builder.buildFuture();
                            })
                            .executes(context -> startExport(context, StringArgumentType.getString(context, "compatibility"), "external"))
                            .then(ClientCommandManager.argument("source", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        builder.suggest("everything");
                                        builder.suggest("external");
                                        return builder.buildFuture();
                                    })
                                    .executes(context -> startExport(context,
                                            StringArgumentType.getString(context, "compatibility"),
                                            StringArgumentType.getString(context, "source")))
                            )
                    )
            );
        });
    }

    private void checkPendingWorldsOnStart() {
        LOGGER.info("[WRS] Checking for pending resource updates in worlds...");
        Iterator<String> iterator = pendingWorlds.iterator();
        while (iterator.hasNext()) {
            Path worldPath = Paths.get(iterator.next());
            if (Files.exists(worldPath)) {
                if (tryCleanAndApplyLatest(worldPath)) {
                    iterator.remove();
                    LOGGER.info("[WRS] Successfully updated resources for world: {}", worldPath);
                }
            } else {
                iterator.remove();
            }
        }
        saveConfig();
    }

    private boolean tryCleanAndApplyLatest(Path worldPath) {
        try {
            Path latestFile = null;
            int maxIndex = -1;
            List<Path> toDelete = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldPath, FILE_PREFIX + "*.zip")) {
                for (Path entry : stream) {
                    String fileName = entry.getFileName().toString();
                    Matcher matcher = RESOURCE_PATTERN.matcher(fileName);
                    if (matcher.matches()) {
                        int index = Integer.parseInt(matcher.group(1));
                        if (index > maxIndex) {
                            if (latestFile != null) toDelete.add(latestFile);
                            maxIndex = index;
                            latestFile = entry;
                        } else {
                            toDelete.add(entry);
                        }
                    }
                }
            }

            if (latestFile != null) {
                Path mainZip = worldPath.resolve("resources.zip");
                Files.deleteIfExists(mainZip);
                Files.move(latestFile, mainZip, StandardCopyOption.REPLACE_EXISTING);

                for (Path path : toDelete) {
                    Files.deleteIfExists(path);
                }
                return true;
            }
        } catch (IOException e) {
            LOGGER.error("[WRS] Error processing folder during startup: {}", worldPath, e);
        }
        return false;
    }

    private int startExport(CommandContext<FabricClientCommandSource> context, String compatibility, String source) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() == null) {
            context.getSource().sendError(Component.translatable("commands.wrs.only_singleplayer").withStyle(ChatFormatting.RED));
            return 0;
        }

        Path worldPath = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
        Path targetZip = findNextWrsPath(worldPath);
        String finalFileName = targetZip.getFileName().toString();

        context.getSource().sendFeedback(Component.translatable("commands.wrs.start", compatibility, source).withStyle(ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
                    zos.setLevel(ZipOutputStream.STORED);
                    int count = processResourcePacks(compatibility, source, zos);
                    createPackMcmeta(zos);

                    pendingWorlds.add(worldPath.toAbsolutePath().toString());
                    saveConfig();

                    client.execute(() -> {
                        context.getSource().sendFeedback(Component.translatable("commands.wrs.success",
                                        count,
                                        Component.literal(finalFileName).withStyle(ChatFormatting.YELLOW))
                                .withStyle(ChatFormatting.GREEN));

                        context.getSource().sendFeedback(Component.translatable("commands.wrs.hint")
                                .withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.ITALIC));
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Export failed", e);
                client.execute(() -> context.getSource().sendError(Component.translatable("commands.wrs.error", e.getMessage()).withStyle(ChatFormatting.RED)));
            }
        });
        return 1;
    }

    private void loadConfig() {
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                pendingWorlds = GSON.fromJson(reader, new TypeToken<Set<String>>(){}.getType());
            } catch (Exception e) {
                LOGGER.error("Failed to load WRS config", e);
            }
        }
        if (pendingWorlds == null) pendingWorlds = new HashSet<>();
    }

    private void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(pendingWorlds, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save WRS config", e);
        }
    }

    private Path findNextWrsPath(Path worldPath) {
        int i = 1;
        while (Files.exists(worldPath.resolve(FILE_PREFIX + i + ".zip"))) {
            i++;
        }
        return worldPath.resolve(FILE_PREFIX + i + ".zip");
    }

    private int processResourcePacks(String compatibility, String source, ZipOutputStream zos) {
        var packRepository = Minecraft.getInstance().getResourcePackRepository();
        List<Pack> packsToProcess = new ArrayList<>();
        boolean force = "force".equalsIgnoreCase(compatibility);
        boolean isExternalOnly = "external".equalsIgnoreCase(source);
        if ("everything".equalsIgnoreCase(source)) packsToProcess.addAll(packRepository.getAvailablePacks());
        else packsToProcess.addAll(packRepository.getSelectedPacks());
        Path globalResourcePacksDir = Minecraft.getInstance().getResourcePackDirectory();
        int successCount = 0;
        for (Pack pack : packsToProcess) {
            if (!force && !pack.getCompatibility().isCompatible()) continue;
            String packId = pack.getId();
            if (isExternalOnly && !packId.startsWith("file/")) continue;
            try {
                if (packId.startsWith("file/")) {
                    Path zipPath = globalResourcePacksDir.resolve(packId.substring(5));
                    if (Files.exists(zipPath) && !Files.isDirectory(zipPath)) extractAssetsFromZip(zipPath, zos);
                    else copyPackAssets(pack, zos);
                } else copyPackAssets(pack, zos);
                successCount++;
            } catch (Exception e) { LOGGER.error("Failed to process pack: {}", packId, e); }
        }
        return successCount;
    }

    private void extractAssetsFromZip(Path zipFilePath, ZipOutputStream zos) {
        try (FileSystem zipFs = FileSystems.newFileSystem(zipFilePath, (ClassLoader) null)) {
            Path assetsInZip = zipFs.getPath("/assets");
            if (Files.exists(assetsInZip)) {
                Files.walkFileTree(assetsInZip, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                        String relPath = file.toString().startsWith("/") ? file.toString().substring(1) : file.toString();
                        if (!relPath.equals(relPath.toLowerCase(Locale.ROOT))) return FileVisitResult.CONTINUE;
                        try (InputStream is = Files.newInputStream(file)) { writeToZip(zos, relPath, is); }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) { LOGGER.error("[WRS] Error ZIP {}: {}", zipFilePath, e.getMessage()); }
    }

    private void copyPackAssets(Pack pack, ZipOutputStream zos) {
        try (PackResources resources = pack.open()) {
            for (String namespace : resources.getNamespaces(PackType.CLIENT_RESOURCES)) {
                resources.listResources(PackType.CLIENT_RESOURCES, namespace, "", (location, streamSupplier) -> {
                    String fullPath = "assets/" + location.getNamespace() + "/" + location.getPath();
                    if (!fullPath.equals(fullPath.toLowerCase(Locale.ROOT))) return;
                    try (InputStream is = streamSupplier.get()) { writeToZip(zos, fullPath, is); }
                    catch (IOException ignored) {}
                });
            }
        } catch (Exception e) { LOGGER.error("[WRS] Error pack {}: {}", pack.getId(), e.getMessage()); }
    }

    private void writeToZip(ZipOutputStream zos, String path, InputStream is) throws IOException {
        try {
            ZipEntry entry = new ZipEntry(path);
            zos.putNextEntry(entry);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
            zos.closeEntry();
        } catch (ZipException ignored) {}
    }

    private void createPackMcmeta(ZipOutputStream zos) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject packObj = new JsonObject();
        packObj.addProperty("pack_format", 34);
        JsonObject descObj = new JsonObject();
        descObj.addProperty("text", "Embedded resources.");
        packObj.add("description", descObj);
        root.add("pack", packObj);
        ZipEntry entry = new ZipEntry("pack.mcmeta");
        zos.putNextEntry(entry);
        zos.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}