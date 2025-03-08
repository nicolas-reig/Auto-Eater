package com.autoeater;

import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;


public class AutoEaterConfig {
    // Default threshold value (how many hunger points below full before auto-eating).
    public static int threshold = 6;
    public static Identifier[] blacklist = {
            Identifier.tryParse("minecraft:rotten_flesh"),
            Identifier.tryParse("minecraft:golden_apple"),
            Identifier.tryParse("minecraft:enchanted_golden_apple"),
            Identifier.tryParse("minecraft:pufferfish"),
            Identifier.tryParse("minecraft:suspicious_stew"),
            Identifier.tryParse("minecraft:chorus_fruit"),
            Identifier.tryParse("minecraft:poisonous_potato")
        };
    public static boolean isBlacklisted(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        for (Identifier blacklisted : blacklist) {
            if (blacklisted.equals(itemId)) {
                return true;
            }
        }
        return false;
    }
}