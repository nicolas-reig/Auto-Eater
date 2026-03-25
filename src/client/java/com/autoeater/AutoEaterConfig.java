package com.autoeater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AutoEaterConfig {
    public static final int DEFAULT_TOGGLE_KEY_CODE = GLFW.GLFW_KEY_COMMA;

    public static boolean killSwitch = false;
    public static int threshold = 0;
    public static int cancelCooldownSeconds = 7;
    public static int toggleKeyCode = DEFAULT_TOGGLE_KEY_CODE;

    public static final List<String> DEFAULT_BLACKLIST = List.of(
            "rotten_flesh",
            "golden_apple",
            "enchanted_golden_apple",
            "pufferfish",
            "suspicious_stew",
            "chorus_fruit",
            "poisonous_potato"
    );

    public static List<String> blacklist = new ArrayList<>(DEFAULT_BLACKLIST);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("auto_eater.json");

    private AutoEaterConfig() {
    }

    public static boolean isBlacklisted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }

        return "minecraft".equals(id.getNamespace())
                ? blacklist.contains(id.getPath())
                : blacklist.contains(id.toString());
    }

    public static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            saveConfig();
            return;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            Data configData = GSON.fromJson(json, Data.class);
            if (configData != null) {
                apply(configData);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveConfig() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(toData()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Data toData() {
        Data data = new Data();
        data.killSwitch = killSwitch;
        data.threshold = threshold;
        data.cancelCooldownSeconds = cancelCooldownSeconds;
        data.toggleKeyCode = toggleKeyCode;
        data.blacklist = new ArrayList<>(blacklist);
        return data;
    }

    private static void apply(Data data) {
        killSwitch = data.killSwitch;
        threshold = data.threshold;
        cancelCooldownSeconds = Math.max(0, data.cancelCooldownSeconds);
        toggleKeyCode = getConfiguredToggleKeyCode(data);
        blacklist = data.blacklist != null ? validateBlacklist(data.blacklist) : new ArrayList<>(DEFAULT_BLACKLIST);
    }

    public static Component getToggleKeyDisplayName() {
        return InputConstants.getKey(new KeyEvent(toggleKeyCode, 0, 0)).getDisplayName();
    }

    private static int getConfiguredToggleKeyCode(Data data) {
        if (data.toggleKeyCode != null && data.toggleKeyCode >= 0) {
            return data.toggleKeyCode;
        }

        if (data.toggleKey != null && !data.toggleKey.isEmpty()) {
            return switch (data.toggleKey.charAt(0)) {
                case ',' -> GLFW.GLFW_KEY_COMMA;
                case '.' -> GLFW.GLFW_KEY_PERIOD;
                case '/' -> GLFW.GLFW_KEY_SLASH;
                case ';' -> GLFW.GLFW_KEY_SEMICOLON;
                case '\'' -> GLFW.GLFW_KEY_APOSTROPHE;
                case '[' -> GLFW.GLFW_KEY_LEFT_BRACKET;
                case ']' -> GLFW.GLFW_KEY_RIGHT_BRACKET;
                case '\\' -> GLFW.GLFW_KEY_BACKSLASH;
                case '`' -> GLFW.GLFW_KEY_GRAVE_ACCENT;
                case '-' -> GLFW.GLFW_KEY_MINUS;
                case '=' -> GLFW.GLFW_KEY_EQUAL;
                default -> legacyAlphaNumericKeyCode(data.toggleKey.charAt(0));
            };
        }

        return DEFAULT_TOGGLE_KEY_CODE;
    }

    private static int legacyAlphaNumericKeyCode(char key) {
        if (Character.isLetter(key)) {
            return GLFW.GLFW_KEY_A + (Character.toUpperCase(key) - 'A');
        }
        if (Character.isDigit(key)) {
            return GLFW.GLFW_KEY_0 + (key - '0');
        }
        return DEFAULT_TOGGLE_KEY_CODE;
    }

    private static List<String> validateBlacklist(List<String> values) {
        List<String> validated = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }

            String normalized = value.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.startsWith("minecraft:")) {
                normalized = normalized.substring("minecraft:".length());
            }
            if (validated.contains(normalized)) {
                continue;
            }
            if (normalized.contains(":")) {
                String namespace = normalized.substring(0, normalized.indexOf(':'));
                if (!namespace.matches("[a-z0-9_-]+")) {
                    continue;
                }
            }

            Identifier id = normalized.contains(":")
                    ? Identifier.tryParse(normalized)
                    : Identifier.withDefaultNamespace(normalized);
            if (id == null) {
                continue;
            }

            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (id.equals(Identifier.withDefaultNamespace("air")) || item != Items.AIR) {
                validated.add(normalized);
            }
        }

        return validated;
    }

    public static List<String> parseBlacklist(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }

        List<String> raw = new ArrayList<>();
        for (String entry : value.split(",")) {
            raw.add(entry);
        }
        return validateBlacklist(raw);
    }

    public static String formatBlacklist() {
        return String.join(", ", blacklist);
    }

    public static Screen getConfigScreen(Screen parent) {
        return new AutoEaterConfigScreen(parent);
    }

    private static class Data {
        boolean killSwitch;
        int threshold;
        int cancelCooldownSeconds;
        Integer toggleKeyCode;
        String toggleKey;
        List<String> blacklist;
    }
}
