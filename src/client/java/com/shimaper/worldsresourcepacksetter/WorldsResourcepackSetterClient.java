package com.shimaper.worldsresourcepacksetter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

public class WorldsResourcepackSetterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WRS");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void onInitializeClient() {
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

    private int startExport(CommandContext<FabricClientCommandSource> context, String compatibility, String source) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() == null) {
            context.getSource().sendError(Component.translatable("commands.wrs.only_singleplayer").withStyle(ChatFormatting.RED));
            return 0;
        }

        Path targetZip = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("resources.zip");
        context.getSource().sendFeedback(Component.translatable("commands.wrs.start", compatibility, source).withStyle(ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                if (Files.exists(targetZip)) {
                    Files.delete(targetZip);
                }

                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
                    zos.setLevel(ZipOutputStream.STORED);

                    int count = processResourcePacks(compatibility, source, zos);
                    createPackMcmeta(zos);

                    client.execute(() -> {
                        context.getSource().sendFeedback(Component.translatable("commands.wrs.success", count).withStyle(ChatFormatting.GREEN));
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Export failed", e);
                client.execute(() -> context.getSource().sendError(Component.translatable("commands.wrs.error", e.getMessage())));
            }
        });
        return 1;
    }

    private int processResourcePacks(String compatibility, String source, ZipOutputStream zos) {
        var packRepository = Minecraft.getInstance().getResourcePackRepository();
        List<Pack> packsToProcess = new ArrayList<>();

        boolean force = "force".equalsIgnoreCase(compatibility);
        boolean isExternalOnly = "external".equalsIgnoreCase(source);

        if ("everything".equalsIgnoreCase(source)) {
            packsToProcess.addAll(packRepository.getAvailablePacks());
        } else {
            packsToProcess.addAll(packRepository.getSelectedPacks());
        }

        Path globalResourcePacksDir = Minecraft.getInstance().getResourcePackDirectory();
        int successCount = 0;

        for (Pack pack : packsToProcess) {
            if (!force && !pack.getCompatibility().isCompatible()) {
                continue;
            }

            String packId = pack.getId();

            if (isExternalOnly && !packId.startsWith("file/")) {
                continue;
            }

            try {
                if (packId.startsWith("file/")) {
                    String fileName = packId.substring(5);
                    Path zipPath = globalResourcePacksDir.resolve(fileName);

                    if (Files.exists(zipPath) && !Files.isDirectory(zipPath)) {
                        extractAssetsFromZip(zipPath, zos);
                    } else {
                        copyPackAssets(pack, zos);
                    }
                } else {
                    copyPackAssets(pack, zos);
                }
                successCount++;
            } catch (Exception e) {
                LOGGER.error("Failed to process pack: {}", packId, e);
            }
        }
        return successCount;
    }

    private void extractAssetsFromZip(Path zipFilePath, ZipOutputStream zos) {
        LOGGER.info("[WRS] Extracting from ZIP: {}", zipFilePath.getFileName());
        try (FileSystem zipFs = FileSystems.newFileSystem(zipFilePath, (ClassLoader) null)) {
            Path assetsInZip = zipFs.getPath("/assets");
            if (Files.exists(assetsInZip)) {
                Files.walkFileTree(assetsInZip, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                        String relativePath = file.toString();
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }

                        if (!relativePath.equals(relativePath.toLowerCase(Locale.ROOT))) {
                            return FileVisitResult.CONTINUE;
                        }

                        try (InputStream is = Files.newInputStream(file)) {
                            writeToZip(zos, relativePath, is);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            LOGGER.error("[WRS] Error while reading archive {}: {}", zipFilePath, e.getMessage());
        }
    }

    private void copyPackAssets(Pack pack, ZipOutputStream zos) {
        LOGGER.info("[WRS] Getting pack: {}", pack.getId());
        try (PackResources resources = pack.open()) {
            var namespaces = resources.getNamespaces(PackType.CLIENT_RESOURCES);
            if (namespaces.isEmpty()) {
                namespaces = java.util.Set.of("minecraft");
            }

            for (String namespace : namespaces) {
                resources.listResources(PackType.CLIENT_RESOURCES, namespace, "", (location, streamSupplier) -> {
                    String fullPath = "assets/" + location.getNamespace() + "/" + location.getPath();

                    if (!fullPath.equals(fullPath.toLowerCase(Locale.ROOT))) {
                        return;
                    }

                    try (InputStream is = streamSupplier.get()) {
                        writeToZip(zos, fullPath, is);
                    } catch (IOException e) {
                        //
                    }
                });
            }
            LOGGER.info("[WRS] Extraction completed for: {}", pack.getId());
        } catch (Exception e) {
            LOGGER.error("[WRS] Critical error while opening file {}: {}", pack.getId(), e.getMessage());
        }
    }

    private void writeToZip(ZipOutputStream zos, String path, InputStream is) throws IOException {
        try {
            ZipEntry entry = new ZipEntry(path);
            zos.putNextEntry(entry);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            zos.closeEntry();
        } catch (ZipException e) {
            //
        }
    }

    private void createPackMcmeta(ZipOutputStream zos) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject packObj = new JsonObject();
        packObj.addProperty("pack_format", 34);

        JsonObject descObj = new JsonObject();
        descObj.addProperty("text", "Embedded resources.");

        packObj.add("description", descObj);
        root.add("pack", packObj);

        String json = GSON.toJson(root);
        ZipEntry entry = new ZipEntry("pack.mcmeta");
        zos.putNextEntry(entry);
        zos.write(json.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}