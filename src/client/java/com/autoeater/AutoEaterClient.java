package com.autoeater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

public class AutoEaterClient implements ClientModInitializer {
    private static TickState activeState;

    @Override
    public void onInitializeClient() {
        AutoEaterConfig.loadConfig();

        TickState state = new TickState();
        state.threshold = AutoEaterConfig.threshold;
        activeState = state;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            state.clientTicks++;
            initializePlayerState(client, state);

            processToggleKey(client, state);

            if (cancelForDamage(client, state)) return;

            if (cancelForScrollAway(client, state)) return;

            if (cancelForManualAttack(client, state)) return;

            if (cancelForManualUse(client, state)) return;

            if (state.eating) {
                handleEating(client, state);
                updatePlayerState(client, state);
                return;
            }

            if (isCancelActive(state)) {
                updatePlayerState(client, state);
                return;
            }

            if (AutoEaterConfig.killSwitch) {
                updatePlayerState(client, state);
                return;
            }

            if (client.options.useKey.isPressed()) {
                updatePlayerState(client, state);
                return;
            }

            if (isLookingAtUsableBlock(client)) {
                updatePlayerState(client, state);
                return;
            }

            if (isLookingAtEntity(client)) {
                updatePlayerState(client, state);
                return;
            }

            if (client.options.attackKey.isPressed()) {
                updatePlayerState(client, state);
                return;
            }

            if (client.options.useKey.isPressed()) {
                updatePlayerState(client, state);
                return;
            }

            int hunger = client.player.getHungerManager().getFoodLevel();
            updateThreshold(client, state);

            // Do nothing if hunger is above threshold or if the attack key is pressed.
            if (hunger > 20 - state.threshold) {
                updatePlayerState(client, state);
                return;
            }

            tryAutoEat(client, state);
            updatePlayerState(client, state);
        });
    }

    
    // Holds mutable state between ticks.
    
    private static class TickState {
        boolean eating;
        int previousSlot;
        int foodSlot;
        int initialFoodCount;
        int threshold;
        long cancelUntilTick;
        long clientTicks;
        float lastCombinedHealth;
        boolean startedUsingItem;
        boolean toggleKeyPressed;
    }

    public static void cancelEating() {
        TickState state = activeState;
        if (state == null) return;

        int cooldownTicks = getConfiguredCancelTicks();
        state.cancelUntilTick = Math.max(state.cancelUntilTick, state.clientTicks + cooldownTicks);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            stopEating(client, state);
        }
    }

    public static boolean isEatingCancelled() {
        TickState state = activeState;
        return state != null && isCancelActive(state);
    }

    
    //Processes the toggle hotkey with edge detection.
     
    private static void processToggleKey(MinecraftClient client, TickState state) {
        if (!state.toggleKeyPressed && isToggleKeyPressed(client)) {
            state.toggleKeyPressed = true;
            AutoEaterConfig.killSwitch = !AutoEaterConfig.killSwitch;
            String message = AutoEaterConfig.killSwitch ? "Auto eating Disabled" : "Auto eating Enabled";
            Formatting color = AutoEaterConfig.killSwitch ? Formatting.RED : Formatting.GREEN;
            client.player.sendMessage(Text.literal(message).formatted(color), true);
        } else if (!isToggleKeyPressed(client)) {
            state.toggleKeyPressed = false;
        }
    }

    //Handles the ongoing eating process.
    private static void handleEating(MinecraftClient client, TickState state) {
        if (isCancelActive(state) || AutoEaterConfig.killSwitch) {
            stopEating(client, state);
            return;
        }

        if (client.player.getInventory().getSelectedSlot() != state.foodSlot) {
            stopEating(client, state);
            return;
        }

        ItemStack heldStack = client.player.getMainHandStack();

        if (heldStack.isEmpty() || !hasFoodComponent(heldStack)) {
            stopEating(client, state);
            return;
        }

        client.options.useKey.setPressed(true);

        if (client.player.isUsingItem()) {
            state.startedUsingItem = true;
        } else if (state.startedUsingItem) {
            stopEating(client, state);
            return;
        }

        if (heldStack.getCount() < state.initialFoodCount) {
            stopEating(client, state);
        }
    }

    private static void initializePlayerState(MinecraftClient client, TickState state) {
        if (state.clientTicks != 1) {
            return;
        }

        state.lastCombinedHealth = getCombinedHealth(client.player);
    }

    private static boolean cancelForDamage(MinecraftClient client, TickState state) {
        float combinedHealth = getCombinedHealth(client.player);
        if (combinedHealth < state.lastCombinedHealth) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForScrollAway(MinecraftClient client, TickState state) {
        int selectedSlot = client.player.getInventory().getSelectedSlot();
        if (state.eating && selectedSlot != state.foodSlot) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForManualAttack(MinecraftClient client, TickState state) {
        if (client.options.attackKey.isPressed()) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForManualUse(MinecraftClient client, TickState state) {
        if (!state.eating && client.options.useKey.isPressed()) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static void updatePlayerState(MinecraftClient client, TickState state) {
        state.lastCombinedHealth = getCombinedHealth(client.player);
    }

    private static float getCombinedHealth(PlayerEntity player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static int getConfiguredCancelTicks() {
        if (AutoEaterConfig.cancelCooldownSeconds <= 0) {
            return 0;
        }
        return AutoEaterConfig.cancelCooldownSeconds * 20;
    }

    //Updates the dynamic threshold in auto modes.
     
    private static void updateThreshold(MinecraftClient client, TickState state) {
        // If threshold is set to auto modes, update using inventory data.
        switch (AutoEaterConfig.threshold) {
            case 0 -> {
                int minNutrition = 20;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = client.player.getInventory().getStack(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                        if (nutrition < minNutrition) {
                            minNutrition = nutrition;
                        }
                    }
                }
                state.threshold = (minNutrition != 20) ? minNutrition : AutoEaterConfig.threshold;
            }
            case 20 -> {
                int maxNutrition = 0;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = client.player.getInventory().getStack(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                        if (nutrition > maxNutrition) {
                            maxNutrition = nutrition;
                        }
                    }
                }
                state.threshold = (maxNutrition != 0) ? maxNutrition : AutoEaterConfig.threshold;
            }
            default -> state.threshold = AutoEaterConfig.threshold;
        }
    }
    
    private static boolean isLookingAtUsableBlock(MinecraftClient client){
        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null) return false;

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHitResult = (BlockHitResult) hitResult;
            BlockPos blockPos = blockHitResult.getBlockPos();
            BlockState blockState = client.world.getBlockState(blockPos);
            if (blockState.isAir()) return false;

            if (blockState.createScreenHandlerFactory(client.world, blockPos) != null) {
                return true;
            }

            Class<?> blockClass = blockState.getBlock().getClass();
            while (blockClass != null && AbstractBlock.class.isAssignableFrom(blockClass)) {
                try {
                    Method onUse = blockClass.getDeclaredMethod(
                            "onUse",
                            BlockState.class,
                            World.class,
                            BlockPos.class,
                            PlayerEntity.class,
                            BlockHitResult.class
                    );
                    if (onUse.getDeclaringClass() != AbstractBlock.class) return true;
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    Method onUseWithItem = blockClass.getDeclaredMethod(
                            "onUseWithItem",
                            ItemStack.class,
                            BlockState.class,
                            World.class,
                            BlockPos.class,
                            PlayerEntity.class,
                            Hand.class,
                            BlockHitResult.class
                    );
                    if (onUseWithItem.getDeclaringClass() != AbstractBlock.class) return true;
                } catch (NoSuchMethodException ignored) {
                }

                if (blockClass == AbstractBlock.class) break;
                blockClass = blockClass.getSuperclass();
            }
        }
        return false;
    }

    private static boolean isLookingAtEntity(MinecraftClient client){
        HitResult hitResult = client.crosshairTarget;
        return hitResult.getType() == HitResult.Type.ENTITY;
    }


    //Attempts to start auto-eating by finding a valid food item.
    private static void tryAutoEat(MinecraftClient client, TickState state) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                int foodNutrition = stack.getComponents().get(DataComponentTypes.FOOD).nutrition();
                // For auto modes, only select food with nutrition equal to the computed threshold.
                if ((AutoEaterConfig.threshold == 0 || AutoEaterConfig.threshold == 20)
                        && foodNutrition != state.threshold) {
                    continue;
                }
                state.previousSlot = client.player.getInventory().getSelectedSlot();
                state.foodSlot = slot;
                state.initialFoodCount = stack.getCount();
                state.startedUsingItem = false;
                client.player.getInventory().setSelectedSlot(slot);
                state.eating = true;
                client.options.useKey.setPressed(true);
                break;
            }
        }
    }

    private static void stopEating(MinecraftClient client, TickState state) {
        client.options.useKey.setPressed(false);

        if (client.player != null && client.player.getInventory().getSelectedSlot() == state.foodSlot) {
            client.player.getInventory().setSelectedSlot(state.previousSlot);
        }

        state.eating = false;
        state.foodSlot = 0;
        state.initialFoodCount = 0;
        state.startedUsingItem = false;
    }

    private static boolean isCancelActive(TickState state) {
        return state.clientTicks < state.cancelUntilTick;
    }


    //Checks if the given ItemStack has a food component.

    private static boolean hasFoodComponent(ItemStack stack) {
        return stack.getComponents().contains(DataComponentTypes.FOOD);
    }


    //Returns true if the configured single-character toggle key is currently pressed.

    private static boolean isToggleKeyPressed(MinecraftClient client) {
        if (AutoEaterConfig.toggleKey == null || AutoEaterConfig.toggleKey.length() != 1) {
            return false;
        }
        char keyChar = AutoEaterConfig.toggleKey.charAt(0);
        int keyCode = getKeyCode(keyChar);
        if (keyCode == -1) {
            return false;
        }
        long windowHandle = client.getWindow().getHandle();
        return GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS;
    }


    //Converts a single character into a GLFW key code.

    private static int getKeyCode(char key) {
        if (Character.isLetter(key)) {
            if ((key >= 'A' && key <= 'Z') || (key >= 'a' && key <= 'z')) {
                return GLFW.GLFW_KEY_A + (Character.toUpperCase(key) - 'A');
            } else {
                if (key == 'å' || key == 'Å') return GLFW.GLFW_KEY_WORLD_1;
                if (key == 'ä' || key == 'Ä') return GLFW.GLFW_KEY_WORLD_2;
                if (key == 'ö' || key == 'Ö') return GLFW.GLFW_KEY_WORLD_2;
                return -1;
            }
        } else if (Character.isDigit(key)) {
            return GLFW.GLFW_KEY_0 + (key - '0');
        } else {
            return switch (key) {
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
                default -> -1;
            };
        }
    }
}
