package com.shimaper.worldsresourcepacksetter;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WrsConfigScreen extends Screen {
    private final Screen parent;
    private final ConfigManager configManager;

    private EditBox fileNameEdit;
    private EditBox descEdit;
    private EditBox pathEdit;
    private Button saveButton;
    private boolean tempIncludeVanilla;

    public WrsConfigScreen(Screen parent, ConfigManager configManager) {
        super(Component.literal("WRS Configuration"));
        this.parent = parent;
        this.configManager = configManager;
        this.tempIncludeVanilla = configManager.getConfig().includeVanilla;
    }

    @Override
    protected void init() {
        WrsCommon.ModConfig config = configManager.getConfig();
        int centerX = this.width / 2;
        int y = 50;
        int spacing = 30;

        this.addRenderableWidget(CycleButton.onOffBuilder(tempIncludeVanilla)
                .create(centerX - 100, y, 200, 20, Component.literal("Include Vanilla RP"), (btn, val) -> {
                    tempIncludeVanilla = val;
                    updateSaveButtonSensitivity();
                }));

        String currentName = config.customFileName.equals("wps_resources") ? "" : config.customFileName;
        fileNameEdit = new EditBox(this.font, centerX - 100, y + spacing, 200, 20, Component.literal("File Name"));
        fileNameEdit.setValue(currentName);
        fileNameEdit.setHint(Component.literal("wps_resources").withStyle(ChatFormatting.GRAY));
        fileNameEdit.setResponder(val -> updateSaveButtonSensitivity());
        this.addRenderableWidget(fileNameEdit);

        String currentDesc = config.customDescription.equals("Embedded resources.") ? "" : config.customDescription;
        descEdit = new EditBox(this.font, centerX - 100, y + spacing * 2, 200, 20, Component.literal("Description"));
        descEdit.setValue(currentDesc);
        descEdit.setHint(Component.literal("Embedded resources.").withStyle(ChatFormatting.GRAY));
        descEdit.setResponder(val -> updateSaveButtonSensitivity());
        this.addRenderableWidget(descEdit);

        pathEdit = new EditBox(this.font, centerX - 100, y + spacing * 3, 200, 20, Component.literal("Save Path"));
        pathEdit.setValue(config.savePath);
        pathEdit.setHint(Component.literal("Current World Path").withStyle(ChatFormatting.GRAY));
        pathEdit.setResponder(val -> updateSaveButtonSensitivity());
        this.addRenderableWidget(pathEdit);

        int buttonY = this.height - 40;
        saveButton = Button.builder(Component.translatable("gui.done"), btn -> {
            applyChanges();
            configManager.save();
            this.onClose();
        }).bounds(centerX - 105, buttonY, 100, 20).build();
        this.addRenderableWidget(saveButton);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> this.onClose())
                .bounds(centerX + 5, buttonY, 100, 20).build());

        updateSaveButtonSensitivity();
    }

    private void updateSaveButtonSensitivity() {
        if (saveButton == null) return;
        WrsCommon.ModConfig cfg = configManager.getConfig();

        String inputName = fileNameEdit.getValue().isEmpty() ? "wps_resources" : fileNameEdit.getValue();
        String inputDesc = descEdit.getValue().isEmpty() ? "Embedded resources." : descEdit.getValue();

        saveButton.active = tempIncludeVanilla != cfg.includeVanilla ||
                !inputName.equals(cfg.customFileName) ||
                !inputDesc.equals(cfg.customDescription) ||
                !pathEdit.getValue().equals(cfg.savePath);
    }

    private void applyChanges() {
        WrsCommon.ModConfig cfg = configManager.getConfig();
        cfg.includeVanilla = tempIncludeVanilla;
        cfg.customFileName = fileNameEdit.getValue().isEmpty() ? "wps_resources" : fileNameEdit.getValue();
        cfg.customDescription = descEdit.getValue().isEmpty() ? "Embedded resources." : descEdit.getValue();
        cfg.savePath = pathEdit.getValue();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFF);

        String currentPath = pathEdit.getValue();
        if (!currentPath.isEmpty()) {
            try {
                Path p = Paths.get(currentPath);
                if (!Files.exists(p) || !Files.isDirectory(p)) {
                    graphics.drawCenteredString(this.font, "⚠ Folder not found", centerX, pathEdit.getY() + 22, 0xFF5555);
                    pathEdit.setTextColor(0xFF5555);
                } else {
                    pathEdit.setTextColor(0xFFFFFF);
                }
            } catch (Exception e) {
                pathEdit.setTextColor(0xFF5555);
            }
        } else {
            pathEdit.setTextColor(0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}