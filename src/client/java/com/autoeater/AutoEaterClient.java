package com.autoeater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.item.ItemStack;

public class AutoEaterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        final int[] consumeTicks = {0};
        final boolean[] eating = {false};
        final int[] previousSlot = {0};
        String key = "minecraft:consumable=>class_10124[consumeSeconds=";

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // 1) If we are currently eating, finish that first.
            if (eating[0]) {
                if (consumeTicks[0] > 0) {
                    // Still eating, hold right-click and decrement ticks.
                    client.options.useKey.setPressed(true);
                    consumeTicks[0]--;
                } else {
                    // Finished eating: restore the previous slot, release right-click.
                    client.player.getInventory().selectedSlot = previousSlot[0];
                    eating[0] = false;
                    client.options.useKey.setPressed(false);
                }
                // Important: Skip checking for new food this tick to avoid immediate re-check.
                return;
            }

            // 2) We are NOT currently eating, so see if we need to start.
            int hunger = client.player.getHungerManager().getFoodLevel();
            // If hunger is still above the threshold, do nothing.
            if (hunger > 20 - AutoEaterConfig.threshold) {
                return;
            }

            // 3) Iterate the hotbar for a valid food item that isn't blacklisted.
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                String compStr = stack.getComponents().toString();

                if (compStr.contains(key) && !AutoEaterConfig.isBlacklisted(stack)) {
                    // We found a valid food item. Parse its consume time and begin eating.
                    previousSlot[0] = client.player.getInventory().selectedSlot;

                    int startIndex = compStr.indexOf(key) + key.length();
                    int endIndex = compStr.indexOf(",", startIndex);
                    if (endIndex == -1) {
                        endIndex = compStr.indexOf("]", startIndex);
                    }
                    try {
                        consumeTicks[0] = (int) (Double.parseDouble(compStr.substring(startIndex, endIndex).trim()) * 20.0);
                    } catch (NumberFormatException e) {
                        // If parsing fails, skip this item.
                        continue;
                    }
                    // Switch to the food slot and begin auto-eating.
                    client.player.getInventory().selectedSlot = slot;
                    eating[0] = true;
                    // Start the first "hold right-click" for this tick.
                    client.options.useKey.setPressed(true);
                    consumeTicks[0]--;
                    // Only process one item per tick.
                    break;
                }
            }
        });
    }
}