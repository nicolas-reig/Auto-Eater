package com.autoeater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;

public class AutoEaterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AutoEaterConfig.loadConfig();
        final int[] consumeTicks = {0};
        final boolean[] eating = {false};
        final int[] previousSlot = {0};
        // Local threshold value that may be updated in auto modes.
        final int[] threshold = {AutoEaterConfig.threshold};

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || AutoEaterConfig.killSwitch) return;

            // 1) If we are currently eating, finish that first.
            if (eating[0]) {
                if (consumeTicks[0] > 0) {
                    // Still eating: hold right-click and decrement ticks.
                    client.options.useKey.setPressed(true);
                    consumeTicks[0]--;
                } else {
                    // Finished eating: restore the previous slot, release right-click.
                    client.player.getInventory().selectedSlot = previousSlot[0];
                    eating[0] = false;
                    client.options.useKey.setPressed(false);
                }
                // Skip checking for new food this tick.
                return;
            }

            // 2) We are NOT currently eating, so see if we need to start.
            int hunger = client.player.getHungerManager().getFoodLevel();

            // Dynamic Threshold for auto modes:
            switch (AutoEaterConfig.threshold) {
                // Auto (min. nutrition)
                case 0: {
                    int smallestNutrition = 20;
                    for (int slot = 0; slot < 9; slot++) {
                        ItemStack stack = client.player.getInventory().getStack(slot);
                        if (stack.getComponents().contains(DataComponentTypes.FOOD)
                                && !AutoEaterConfig.isBlacklisted(stack)) {
                            int nutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                            if (nutrition < smallestNutrition) {
                                smallestNutrition = nutrition;
                            }
                        }
                    }
                    if (smallestNutrition != 20) {
                        threshold[0] = smallestNutrition;
                    }
                    break;
                }
                // Auto (max. nutrition)
                case 20: {
                    int largestNutrition = 0;
                    for (int slot = 0; slot < 9; slot++) {
                        ItemStack stack = client.player.getInventory().getStack(slot);
                        if (stack.getComponents().contains(DataComponentTypes.FOOD)
                                && !AutoEaterConfig.isBlacklisted(stack)) {
                            int nutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                            if (nutrition > largestNutrition) {
                                largestNutrition = nutrition;
                            }
                        }
                    }
                    if (largestNutrition != 0) {
                        threshold[0] = largestNutrition;
                    }
                    break;
                }
                default: {
                    threshold[0] = AutoEaterConfig.threshold;
                    break;
                }
            }

            // If hunger is above the threshold, do nothing.
            if (hunger > 20 - threshold[0]) {
                return;
            }

            // 3) Iterate the hotbar for a valid food item.
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (stack.getComponents().contains(DataComponentTypes.FOOD)
                        && !AutoEaterConfig.isBlacklisted(stack)) {
                    int foodNutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                    // In auto modes, only select food with nutrition equal to the computed threshold.
                    if (AutoEaterConfig.threshold == 0 || AutoEaterConfig.threshold == 20) {
                        if (foodNutrition != threshold[0]) continue;
                    }
                    // We found a valid food item.
                    previousSlot[0] = client.player.getInventory().selectedSlot;
                    consumeTicks[0] = stack.getComponents().get(DataComponentTypes.CONSUMABLE).getConsumeTicks();

                    // Switch to the food slot and begin auto-eating.
                    client.player.getInventory().selectedSlot = slot;
                    eating[0] = true;
                    client.options.useKey.setPressed(true);
                    consumeTicks[0]--;
                    break;
                }
            }
        });
    }
}