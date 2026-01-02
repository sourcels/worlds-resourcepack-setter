package com.shimaper.worldsresourcepacksetter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public class WrsCommon {
    public static final Logger LOGGER = LoggerFactory.getLogger("WRS");
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String DEFAULT_FILENAME = "wps_resources";
    public static final String DEFAULT_DESCRIPTION = "Embedded resources.";
    public static final String DEFAULT_PATH = "";

    public static class ModConfig {
        public List<String> pendingWorlds = new ArrayList<>();
        public boolean includeVanilla = false;

        public String savePath = DEFAULT_PATH;
        public String customFileName = DEFAULT_FILENAME;
        public String customDescription = DEFAULT_DESCRIPTION;
    }
}