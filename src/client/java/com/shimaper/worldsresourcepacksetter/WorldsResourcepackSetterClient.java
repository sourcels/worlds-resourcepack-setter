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
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WorldsResourcepackSetterClient implements ClientModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("setresourcepack")
                    .executes(context -> startExport(context, "force", "externed"))
                    .then(ClientCommandManager.argument("compatibility", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                builder.suggest("force");
                                builder.suggest("compatible");
                                return builder.buildFuture();
                            })
                            .executes(context -> startExport(context, StringArgumentType.getString(context, "compatibility"), "externed"))
                            .then(ClientCommandManager.argument("source", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        builder.suggest("everything");
                                        builder.suggest("externed");
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
            context.getSource().sendError(Component.translatable("commands.wrs.only_singleplayer")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            return 0;
        }

        context.getSource().sendFeedback(Component.translatable("commands.wrs.start",
                Component.translatable("commands.wrs.arg." + compatibility),
                Component.translatable("commands.wrs.arg." + source)).withStyle(ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                processResourcePacks(client, compatibility, source, context);
            } catch (Exception e) {
                client.execute(() -> context.getSource().sendError(Component.translatable("commands.wrs.error", e.getMessage())));
            }
        });

        return 1;
    }

    private void processResourcePacks(Minecraft client, String compatibility, String source, CommandContext<FabricClientCommandSource> context) throws IOException {
        Path worldDir = client.getSingleplayerServer().getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        Path targetDir = worldDir.resolve("resources");
        Path targetZip = worldDir.resolve("resources.zip");

        if (Files.exists(targetDir)) deleteDirectoryRecursively(targetDir);
        if (Files.exists(targetZip)) Files.delete(targetZip);

        Files.createDirectories(targetDir.resolve("assets"));
        createPackMcmeta(targetDir);

        List<Pack> enabledPacks = new ArrayList<>(client.getResourcePackRepository().getSelectedPacks());
        int count = 0;

        for (Pack pack : enabledPacks) {
            if (compatibility.equals("compatible") && !pack.getCompatibility().isCompatible()) continue;

            if (source.equals("externed")) {
                if (pack.getPackSource() == PackSource.DEFAULT ||
                        pack.getPackSource() == PackSource.BUILT_IN ||
                        pack.isRequired() ||
                        pack.isFixedPosition() ||
                        pack.getId().equals("vanilla")) {
                    continue;
                }
            }

            copyPackAssets(pack, targetDir);
            count++;
        }

        final int finalCount = count;
        client.execute(() -> context.getSource().sendFeedback(Component.translatable("commands.wrs.success", finalCount)
                .withStyle(ChatFormatting.GREEN)));
    }

    private void copyPackAssets(Pack pack, Path targetRoot) {
        try (PackResources resources = pack.open()) {
            for (String namespace : resources.getNamespaces(PackType.CLIENT_RESOURCES)) {
                resources.listResources(PackType.CLIENT_RESOURCES, namespace, "", (resourceLocation, ioSupplier) -> {
                    try {
                        Path destPath = targetRoot.resolve("assets").resolve(resourceLocation.getNamespace()).resolve(resourceLocation.getPath());
                        Files.createDirectories(destPath.getParent());
                        try (InputStream in = ioSupplier.get()) {
                            Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private void createPackMcmeta(Path targetDir) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("min_format", 69);
        pack.addProperty("max_format", 75);

        com.google.gson.JsonArray descArray = new com.google.gson.JsonArray();
        JsonObject descObj = new JsonObject();
        descObj.addProperty("text", "Autogenerated ");
        descObj.addProperty("color", "gray");
        descArray.add(descObj);

        pack.add("description", descArray);
        root.add("pack", pack);
        Files.writeString(targetDir.resolve("pack.mcmeta"), GSON.toJson(root));
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
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