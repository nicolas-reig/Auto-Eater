package com.autoeater;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

public class AutoEaterClient implements ClientModInitializer {
    private static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.auto-eater.toggle",
            GLFW.GLFW_KEY_COMMA,
            KeyMapping.Category.MISC
    );
    private static TickState activeState;

    @Override
    public void onInitializeClient() {
        AutoEaterConfig.loadConfig();
        KeyMappingHelper.registerKeyMapping(TOGGLE_KEY);

        String legacyToggleKeyName = AutoEaterConfig.takeLegacyToggleKeyName();
        if (legacyToggleKeyName != null) {
            setToggleKey(legacyToggleKeyName);
        }

        TickState state = new TickState();
        state.threshold = AutoEaterConfig.threshold;
        activeState = state;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null || client.gameMode == null) {
                return;
            }
            if (client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
                return;
            }

            state.clientTicks++;
            initializePlayerState(client, state);
            processToggleKey(client, state);

            if (cancelForDamage(client, state)
                    || cancelForScrollAway(client, state)
                    || cancelForManualAttack(client, state)
                    || cancelForManualUse(client, state)) {
                return;
            }

            if (client.screen != null) {
                if (state.eating) {
                    stopEating(client, state);
                }
                updatePlayerState(client, state);
                return;
            }

            if (state.eating) {
                handleEating(client, state);
                updatePlayerState(client, state);
                return;
            }

            if (isCancelActive(state)
                    || AutoEaterConfig.killSwitch
                    || client.options.keyUse.isDown()
                    || client.options.keyAttack.isDown()
                    || isLookingAtUsableBlock(client)
                    || isLookingAtEntity(client)) {
                updatePlayerState(client, state);
                return;
            }

            int hunger = client.player.getFoodData().getFoodLevel();
            updateThreshold(client, state);

            if (hunger > 20 - state.threshold) {
                updatePlayerState(client, state);
                return;
            }

            tryAutoEat(client, state);
            updatePlayerState(client, state);
        });
    }

    private static class TickState {
        boolean eating;
        boolean switchedSlotForEating;
        int previousSlot;
        int foodSlot;
        int initialFoodCount;
        int threshold;
        long cancelUntilTick;
        long clientTicks;
        float lastCombinedHealth;
        boolean startedUsingItem;
    }

    public static void cancelEating() {
        TickState state = activeState;
        if (state == null) {
            return;
        }

        int cooldownTicks = getConfiguredCancelTicks();
        state.cancelUntilTick = Math.max(state.cancelUntilTick, state.clientTicks + cooldownTicks);

        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            stopEating(client, state);
        }
    }

    public static boolean isEatingCancelled() {
        TickState state = activeState;
        return state != null && isCancelActive(state);
    }

    private static void processToggleKey(Minecraft client, TickState state) {
        while (TOGGLE_KEY.consumeClick()) {
            AutoEaterConfig.killSwitch = !AutoEaterConfig.killSwitch;
            AutoEaterConfig.saveConfig();
            String message = AutoEaterConfig.killSwitch ? "Auto eating Disabled" : "Auto eating Enabled";
            ChatFormatting color = AutoEaterConfig.killSwitch ? ChatFormatting.RED : ChatFormatting.GREEN;
            client.player.sendOverlayMessage(Component.literal(message).withStyle(color));
        }
    }

    public static String getToggleKeyName() {
        return TOGGLE_KEY.saveString();
    }

    public static Component getToggleKeyDisplayName() {
        return TOGGLE_KEY.getTranslatedKeyMessage();
    }

    public static void setToggleKey(String keyName) {
        TOGGLE_KEY.setKey(InputConstants.getKey(keyName));
        KeyMapping.resetMapping();

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.options != null) {
            client.options.save();
        }
    }

    private static void handleEating(Minecraft client, TickState state) {
        if (isCancelActive(state) || AutoEaterConfig.killSwitch) {
            stopEating(client, state);
            return;
        }

        if (client.player.getInventory().getSelectedSlot() != state.foodSlot) {
            stopEating(client, state);
            return;
        }

        ItemStack heldStack = client.player.getMainHandItem();
        if (heldStack.isEmpty() || !hasFoodComponent(heldStack)) {
            stopEating(client, state);
            return;
        }

        client.options.keyUse.setDown(true);

        if (client.player.isUsingItem()) {
            state.startedUsingItem = true;
        } else if (state.startedUsingItem || heldStack.getCount() < state.initialFoodCount) {
            stopEating(client, state);
        }
    }

    private static void initializePlayerState(Minecraft client, TickState state) {
        if (state.clientTicks == 1) {
            state.lastCombinedHealth = getCombinedHealth(client.player);
        }
    }

    private static boolean cancelForDamage(Minecraft client, TickState state) {
        float combinedHealth = getCombinedHealth(client.player);
        if (combinedHealth < state.lastCombinedHealth) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForScrollAway(Minecraft client, TickState state) {
        if (state.eating && client.player.getInventory().getSelectedSlot() != state.foodSlot) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForManualAttack(Minecraft client, TickState state) {
        if (client.options.keyAttack.isDown()) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static boolean cancelForManualUse(Minecraft client, TickState state) {
        ItemStack mainHandStack = client.player.getMainHandItem();
        ItemStack offHandStack = client.player.getOffhandItem();

        if (!state.eating
                && client.options.keyUse.isDown()
                && !hasFoodComponent(mainHandStack)
                && !hasFoodComponent(offHandStack)) {
            cancelEating();
            updatePlayerState(client, state);
            return true;
        }
        return false;
    }

    private static void updatePlayerState(Minecraft client, TickState state) {
        state.lastCombinedHealth = getCombinedHealth(client.player);
    }

    private static float getCombinedHealth(Player player) {
        return player.getHealth() + player.getAbsorptionAmount();
    }

    private static int getConfiguredCancelTicks() {
        return Math.max(0, AutoEaterConfig.cancelCooldownSeconds) * 20;
    }

    private static void updateThreshold(Minecraft client, TickState state) {
        switch (AutoEaterConfig.threshold) {
            case 0 -> {
                int minNutrition = 20;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.get(DataComponents.FOOD).nutrition();
                        if (nutrition < minNutrition) {
                            minNutrition = nutrition;
                        }
                    }
                }
                state.threshold = minNutrition != 20 ? minNutrition : AutoEaterConfig.threshold;
            }
            case 20 -> {
                int maxNutrition = 0;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.get(DataComponents.FOOD).nutrition();
                        if (nutrition > maxNutrition) {
                            maxNutrition = nutrition;
                        }
                    }
                }
                state.threshold = maxNutrition != 0 ? maxNutrition : AutoEaterConfig.threshold;
            }
            default -> state.threshold = AutoEaterConfig.threshold;
        }
    }

    private static boolean isLookingAtUsableBlock(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK || client.level == null) {
            return false;
        }

        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        BlockPos blockPos = blockHitResult.getBlockPos();
        BlockState blockState = client.level.getBlockState(blockPos);
        if (blockState.isAir()) {
            return false;
        }

        if (blockState.getMenuProvider(client.level, blockPos) != null) {
            return true;
        }

        Class<?> blockClass = blockState.getBlock().getClass();
        while (blockClass != null && Block.class.isAssignableFrom(blockClass)) {
            try {
                Method useWithoutItem = blockClass.getDeclaredMethod(
                        "useWithoutItem",
                        BlockState.class,
                        Level.class,
                        BlockPos.class,
                        Player.class,
                        BlockHitResult.class
                );
                if (useWithoutItem.getDeclaringClass() != Block.class) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method useItemOn = blockClass.getDeclaredMethod(
                        "useItemOn",
                        ItemStack.class,
                        BlockState.class,
                        Level.class,
                        BlockPos.class,
                        Player.class,
                        InteractionHand.class,
                        BlockHitResult.class
                );
                if (useItemOn.getDeclaringClass() != Block.class) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
            }

            if (blockClass == Block.class) {
                break;
            }
            blockClass = blockClass.getSuperclass();
        }

        return false;
    }

    private static boolean isLookingAtEntity(Minecraft client) {
        HitResult hitResult = client.hitResult;
        return hitResult != null && hitResult.getType() == HitResult.Type.ENTITY;
    }

    private static void tryAutoEat(Minecraft client, TickState state) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                int nutrition = stack.get(DataComponents.FOOD).nutrition();
                if ((AutoEaterConfig.threshold == 0 || AutoEaterConfig.threshold == 20) && nutrition != state.threshold) {
                    continue;
                }

                state.previousSlot = client.player.getInventory().getSelectedSlot();
                state.foodSlot = slot;
                state.initialFoodCount = stack.getCount();
                state.switchedSlotForEating = state.previousSlot != slot;
                state.startedUsingItem = false;
                client.player.getInventory().setSelectedSlot(slot);
                state.eating = true;
                client.options.keyUse.setDown(true);
                break;
            }
        }
    }

    private static void stopEating(Minecraft client, TickState state) {
        client.options.keyUse.setDown(false);

        if (client.player != null
                && state.switchedSlotForEating
                && client.player.getInventory().getSelectedSlot() == state.foodSlot) {
            client.player.getInventory().setSelectedSlot(state.previousSlot);
        }

        state.eating = false;
        state.switchedSlotForEating = false;
        state.foodSlot = 0;
        state.initialFoodCount = 0;
        state.startedUsingItem = false;
    }

    private static boolean isCancelActive(TickState state) {
        return state.clientTicks < state.cancelUntilTick;
    }

    private static boolean hasFoodComponent(ItemStack stack) {
        return stack.has(DataComponents.FOOD);
    }
}
