package com.autoeater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AutoEaterConfig {

    public static boolean killSwitch = false;
    public static int threshold = 6;
    // Blacklist stores vanilla items as "apple" (without "minecraft:") and mod items with full id.
    public static List<String> blacklist = new ArrayList<>(List.of(
            "rotten_flesh",
            "golden_apple",
            "enchanted_golden_apple",
            "pufferfish",
            "suspicious_stew",
            "chorus_fruit",
            "poisonous_potato"
    ));

    public static boolean isBlacklisted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        // For vanilla items, compare using path; for mod items, use full id.
        if ("minecraft".equals(id.getNamespace())) {
            return blacklist.contains(id.getPath());
        } else {
            return blacklist.contains(id.toString());
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auto_eater.json");

    public static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            saveConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            Data data = GSON.fromJson(json, Data.class);
            if (data != null) apply(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveConfig() {
        try {
            Data data = toData();
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Data toData() {
        Data d = new Data();
        d.killSwitch = killSwitch;
        d.threshold = threshold;
        d.blacklist = new ArrayList<>(blacklist);
        return d;
    }

    private static void apply(Data d) {
        killSwitch = d.killSwitch;
        threshold = d.threshold;
        blacklist = new ArrayList<>(d.blacklist);
    }

    private static class Data {
        boolean killSwitch;
        int threshold;
        List<String> blacklist;
    }

    public static Screen getConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Auto Eater Settings"));
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        general.addEntry(eb.startBooleanToggle(Text.literal("Kill Switch"), killSwitch)
                .setDefaultValue(false)
                .setSaveConsumer(val -> killSwitch = val)
                .build());

        general.addEntry(eb.startIntSlider(Text.literal("Hunger threshold"), threshold, 1, 20)
                .setDefaultValue(6)
                .setSaveConsumer(val -> threshold = val)
                .build());

        List<String> tempList = new ArrayList<>(blacklist);
        general.addEntry(eb.startStrList(Text.literal("Blacklist"), tempList)
                .setDefaultValue(tempList)
                .setSaveConsumer(list -> {
                    List<String> validated = list.stream()
                            .map(String::trim)                    // 1. Trim
                            .filter(s -> !s.isEmpty())             // 2. Check if empty
                            .map(s -> s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s) // 3. Remove "minecraft:"
                            .distinct()                          // 4. Remove duplicates
                            .filter(s -> {
                                // 5. If ":" exists, check mod id validity
                                if (s.contains(":")) {
                                    String[] parts = s.split(":", 2);
                                    String modid = parts[0];
                                    return modid.matches("[a-z0-9_-]+");
                                }
                                return true;
                            })
                            .filter(s -> {
                                // 6. Validate the item; for vanilla, prepend "minecraft:".
                                Identifier id = s.contains(":")
                                        ? Identifier.tryParse(s)
                                        : Identifier.tryParse("minecraft:" + s);
                                if (id == null) return false;
                                Item item = Registries.ITEM.get(id);
                                return id.toString().equals("minecraft:air") || !item.equals(Items.AIR);
                            })
                            .collect(Collectors.toList());
                    blacklist = validated;
                })
                .build());

        builder.setSavingRunnable(AutoEaterConfig::saveConfig);
        return builder.build();
    }
}