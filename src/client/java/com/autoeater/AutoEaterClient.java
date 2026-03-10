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

    @Override
    public void onInitializeClient() {
        AutoEaterConfig.loadConfig();

        TickState state = new TickState();
        state.eatStartCountMarker = 0;
        state.eating = false;
        state.previousSlot = 0;
        state.threshold = AutoEaterConfig.threshold;
        state.toggleKeyPressed = false;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            
            processToggleKey(client, state);

            if (state.eating) {
                handleEating(client, state);
                return;
            }
            
            if (AutoEaterConfig.killSwitch) return;
            
            if (client.options.useKey.isPressed()) return;

            if (isLookingAtUsableBlock(client)) return;

            if (isLookingAtEntity(client)) return;

            int hunger = client.player.getHungerManager().getFoodLevel();
            updateThreshold(client, state);

            // Do nothing if hunger is above threshold or if the attack key is pressed.
            if (hunger > 20 - state.threshold || client.options.attackKey.isPressed()) {
                return;
            }

            tryAutoEat(client, state);
        });
    }

    
    // Holds mutable state between ticks.
    
    private static class TickState {
        int eatStartCountMarker;
        boolean eating;
        int previousSlot;
        int threshold;
        boolean toggleKeyPressed;
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
        client.options.useKey.setPressed(true);

        ItemStack heldStack = client.player.getMainHandStack();

        // First tick of this eating cycle: store the initial held stack size.
        // `state.eatStartCountMarker` is initialized in tryAutoEat; reuse it to avoid widening TickState.
        if (state.eatStartCountMarker > 0) {
            state.eatStartCountMarker = -heldStack.getCount();
            return;
        }

        int initialCount = Math.max(1, -state.eatStartCountMarker);
        boolean consumedOne = heldStack.getCount() < initialCount;
        boolean heldItemChanged = !hasFoodComponent(heldStack);

        if (consumedOne || heldItemChanged) {
            // Consumption confirmed by inventory change: restore previous slot and stop holding use.
            client.player.getInventory().setSelectedSlot(state.previousSlot);
            state.eating = false;
            state.eatStartCountMarker = 0;
            client.options.useKey.setPressed(false);
        }
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
                state.eatStartCountMarker = stack.getComponents().get(DataComponentTypes.CONSUMABLE).getConsumeTicks();
                client.player.getInventory().setSelectedSlot(slot);;
                state.eating = true;
                client.options.useKey.setPressed(true);
                state.eatStartCountMarker--;
                break;
            }
        }
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
