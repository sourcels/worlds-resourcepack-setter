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
                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                builder.suggest("force");
                                builder.suggest("compatible");
                                return builder.buildFuture();
                            })
                            .executes(this::executeCommand)));
        });
    }

    private int executeCommand(CommandContext<FabricClientCommandSource> context) {
        String mode = StringArgumentType.getString(context, "mode");
        boolean enforceCompatibility = mode.equals("compatible");

        if (!mode.equals("force") && !mode.equals("compatible")) {
            context.getSource().sendError(Component.literal("Invalid mode. Use 'force' or 'compatible'."));
            return 0;
        }

        Minecraft client = Minecraft.getInstance();

        if (client.getSingleplayerServer() == null) {
            context.getSource().sendError(Component.literal("This command can only be used in Singleplayer worlds!"));
            return 0;
        }

        context.getSource().sendFeedback(Component.literal("Starting resource pack export (" + mode + ")...").withStyle(ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                processResourcePacks(client, enforceCompatibility, context);
            } catch (Exception e) {
                e.printStackTrace();
                client.execute(() -> context.getSource().sendError(Component.literal("Error occurred: " + e.getMessage())));
            }
        });

        return 1;
    }

    private void processResourcePacks(Minecraft client, boolean enforceCompatibility, CommandContext<FabricClientCommandSource> context) throws IOException {
        Path worldDir = client.getSingleplayerServer().getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        if (worldDir == null) throw new IOException("Could not determine world directory.");

        Path targetResourcesDir = worldDir.resolve("resources");
        Path targetAssetsDir = targetResourcesDir.resolve("assets");

        if (Files.exists(targetResourcesDir)) {
            deleteDirectoryRecursively(targetResourcesDir);
        }
        Files.createDirectories(targetAssetsDir);

        createPackMcmeta(targetResourcesDir);

        List<Pack> enabledPacks = new ArrayList<>(client.getResourcePackRepository().getSelectedPacks());

        int count = 0;

        for (Pack pack : enabledPacks) {
            if (pack.getId().equals("vanilla") || pack.getId().equals("fabric")) {
                continue;
            }

            if (enforceCompatibility && !pack.getCompatibility().isCompatible()) {
                continue;
            }

            copyPackAssets(pack, targetResourcesDir);
            count++;
        }

        final int finalCount = count;
        client.execute(() -> context.getSource().sendFeedback(Component.literal("Successfully exported " + finalCount + " packs to world/resources!").withStyle(ChatFormatting.GREEN)));
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
                    } catch (IOException e) {
                        System.err.println("Failed to copy resource: " + resourceLocation + " from pack " + pack.getId());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("Failed to open pack: " + pack.getId());
        }
    }

    private void createPackMcmeta(Path targetDir) throws IOException {
        JsonObject packObject = new JsonObject();
        packObject.addProperty("min_format", 69);
        packObject.addProperty("max_format", 75);

        JsonObject descObject = new JsonObject();
        descObject.addProperty("text", "Autogenerated ");
        descObject.addProperty("color", "gray");

        com.google.gson.JsonArray descArray = new com.google.gson.JsonArray();
        descArray.add(descObject);

        packObject.add("description", descArray);

        JsonObject root = new JsonObject();
        root.add("pack", packObject);

        Files.writeString(targetDir.resolve("pack.mcmeta"), GSON.toJson(root));
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}