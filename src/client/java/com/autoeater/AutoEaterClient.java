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

        // Register a tick event.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                // If auto-eating is finished and we were eating, restore the previous selected slot.
                if (eating[0] && consumeTicks[0] <= 0) {
                    client.player.getInventory().selectedSlot = previousSlot[0];
                    eating[0] = false;
                    // Make sure the right-click key is no longer pressed.
                    client.options.useKey.setPressed(false);
                }

                // Iterate through the hotbar slots (0 to 8).
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = client.player.getInventory().getStack(slot);

                    if (client.player.getHungerManager().getFoodLevel() <= 20 - AutoEaterConfig.threshold &&
                        stack.getComponents().toString().contains("minecraft:consumable=>class_10124[consumeSeconds=")) {

                        // If not already eating, parse the consume time and store the current slot.
                        if (!eating[0]) {
                            previousSlot[0] = client.player.getInventory().selectedSlot;
                            String compStr = stack.getComponents().toString();
                            String key = "minecraft:consumable=>class_10124[consumeSeconds=";
                            int startIndex = compStr.indexOf(key) + key.length();
                            int endIndex = compStr.indexOf(",", startIndex);
                            if (endIndex == -1) {
                                endIndex = compStr.indexOf("]", startIndex);
                            }
                            consumeTicks[0] = (int) (Double.parseDouble(compStr.substring(startIndex, endIndex).trim()) * 20.0);
                            // Switch to the food slot.
                            client.player.getInventory().selectedSlot = slot;
                            eating[0] = true;
                        }

                        // If we still have ticks left to “hold right-click,” do so.
                        if (eating[0] && consumeTicks[0] > 0) {
                            client.options.useKey.setPressed(true);
                            consumeTicks[0]--;
                        } else {
                            client.options.useKey.setPressed(false);
                        }

                        // Only process one valid food item per tick.
                        break;
                    } else {
                        // Ensure the use key is released if no matching food is found.
                        if (!eating[0]) {
                            client.options.useKey.setPressed(false);
                        }
                    }
                }
            }
        });
    }
}