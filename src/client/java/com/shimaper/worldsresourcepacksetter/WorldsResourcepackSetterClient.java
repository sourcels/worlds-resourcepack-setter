package com.shimaper.worldsresourcepacksetter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipOutputStream;

import static com.shimaper.worldsresourcepacksetter.WrsCommon.LOGGER;

public class WorldsResourcepackSetterClient implements ClientModInitializer {
    private static WorldsResourcepackSetterClient INSTANCE;

    private final ConfigManager configManager = new ConfigManager();
    private final WorldResourceManager worldResourceManager = new WorldResourceManager(configManager);
    private final PackExporter packExporter = new PackExporter(configManager);

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        configManager.load();
        worldResourceManager.checkPendingWorldsOnStart();

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

        Path currentWorldPath = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
        Path targetFile = worldResourceManager.resolveExportPath(currentWorldPath);
        Path targetFolder = targetFile.getParent();

        context.getSource().sendFeedback(Component.translatable("commands.wrs.start", compatibility, source).withStyle(ChatFormatting.GRAY));

        CompletableFuture.runAsync(() -> {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetFile))) {
                zos.setLevel(ZipOutputStream.STORED);

                int count = packExporter.processResourcePacks(compatibility, source, zos);
                packExporter.createPackMcmeta(zos);

                boolean isWorld = Files.exists(targetFolder.resolve("level.dat"));
                boolean isCurrentWorld = targetFolder.equals(currentWorldPath);

                if (isWorld) configManager.addPendingWorld(targetFolder);

                client.execute(() -> {
                    // Оригинальная логика вывода в чат
                    client.gui.getChat().addMessage(Component.translatable("commands.wrs.success", count, targetFile.getFileName()).withStyle(ChatFormatting.GREEN));

                    if (isCurrentWorld) {
                        client.gui.getChat().addMessage(Component.translatable("commands.wrs.hint").withStyle(ChatFormatting.AQUA));
                    } else if (isWorld) {
                        String folderName = targetFolder.getFileName().toString();
                        client.gui.getChat().addMessage(Component.translatable("commands.wrs.althint", folderName).withStyle(ChatFormatting.YELLOW));
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Export failed", e);
                client.execute(() -> context.getSource().sendError(Component.translatable("commands.wrs.error", e.getMessage()).withStyle(ChatFormatting.RED)));
            }
        });
        return 1;
    }

    public static WorldsResourcepackSetterClient getInstance() {
        return INSTANCE;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}