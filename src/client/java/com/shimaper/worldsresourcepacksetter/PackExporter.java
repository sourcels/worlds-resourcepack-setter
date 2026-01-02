package com.shimaper.worldsresourcepacksetter;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.shimaper.worldsresourcepacksetter.WrsCommon.GSON;
import static com.shimaper.worldsresourcepacksetter.WrsCommon.LOGGER;

public class PackExporter {
    private final ConfigManager configManager;

    public PackExporter(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public int processResourcePacks(String compatibility, String source, ZipOutputStream zos) {
        List<Pack> selectedPacks = new ArrayList<>(Minecraft.getInstance().getResourcePackRepository().getSelectedPacks());
        Collections.reverse(selectedPacks);

        boolean force = "force".equalsIgnoreCase(compatibility);
        boolean isExternalOnly = "external".equalsIgnoreCase(source);
        boolean includeVanilla = configManager.getConfig().includeVanilla;

        int successCount = 0;
        for (Pack pack : selectedPacks) {
            String id = pack.getId();
            if (!includeVanilla && "vanilla".equals(id)) continue;
            if ("file/resources.zip".equals(id) || "resources.zip".equals(id)) continue;
            if (!force && !pack.getCompatibility().isCompatible()) continue;
            if (isExternalOnly && !id.startsWith("file/")) continue;

            try (PackResources resources = pack.open()) {
                for (String namespace : resources.getNamespaces(PackType.CLIENT_RESOURCES)) {
                    resources.listResources(PackType.CLIENT_RESOURCES, namespace, "", (location, streamSupplier) -> {
                        String path = "assets/" + location.getNamespace() + "/" + location.getPath();
                        if (!path.equals(path.toLowerCase(Locale.ROOT))) return;
                        try (InputStream is = streamSupplier.get()) {
                            writeToZip(zos, path, is);
                        } catch (IOException ignored) {}
                    });
                }
                successCount++;
            } catch (Exception e) { LOGGER.error("Pack error: {}", id, e); }
        }
        return successCount;
    }

    public void createPackMcmeta(ZipOutputStream zos) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject packObj = new JsonObject();
        packObj.addProperty("pack_format", 34);
        JsonObject descObj = new JsonObject();
        descObj.addProperty("text", configManager.getConfig().customDescription);
        packObj.add("description", descObj);
        root.add("pack", packObj);

        ZipEntry entry = new ZipEntry("pack.mcmeta");
        zos.putNextEntry(entry);
        zos.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void writeToZip(ZipOutputStream zos, String path, InputStream is) throws IOException {
        try {
            zos.putNextEntry(new ZipEntry(path));
            is.transferTo(zos);
            zos.closeEntry();
        } catch (Exception ignored) {}
    }
}