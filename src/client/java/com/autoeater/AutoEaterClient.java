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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
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
    private static final int HOTBAR_SIZE = Inventory.SELECTION_SIZE;
    private static final int INVENTORY_SIZE = Inventory.INVENTORY_SIZE;
    private static final int INVENTORY_FALLBACK_HOTBAR_SLOT = HOTBAR_SIZE - 1;
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

            if (AutoEaterConfig.killSwitch) {
                if (state.eating) {
                    stopEating(client, state);
                }
                updatePlayerState(client, state);
                return;
            }

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
        boolean swappedInventorySlotForEating;
        int swappedInventorySourceSlot = -1;
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
            if (state.eating) {
                stopEating(client, state);
            }
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
            if (client.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                state.startedUsingItem = true;
                return;
            }

            stopEating(client, state);
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
                for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.get(DataComponents.FOOD).nutrition();
                        if (nutrition < minNutrition) {
                            minNutrition = nutrition;
                        }
                    }
                }

                if (minNutrition == 20 && AutoEaterConfig.inventoryScanEnabled) {
                    for (int slot = HOTBAR_SIZE; slot < INVENTORY_SIZE; slot++) {
                        ItemStack stack = client.player.getInventory().getItem(slot);
                        if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                            int nutrition = stack.get(DataComponents.FOOD).nutrition();
                            if (nutrition < minNutrition) {
                                minNutrition = nutrition;
                            }
                        }
                    }
                }

                state.threshold = minNutrition != 20 ? minNutrition : AutoEaterConfig.threshold;
            }
            case 20 -> {
                int maxNutrition = 0;
                for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                        int nutrition = stack.get(DataComponents.FOOD).nutrition();
                        if (nutrition > maxNutrition) {
                            maxNutrition = nutrition;
                        }
                    }
                }

                if (maxNutrition == 0 && AutoEaterConfig.inventoryScanEnabled) {
                    for (int slot = HOTBAR_SIZE; slot < INVENTORY_SIZE; slot++) {
                        ItemStack stack = client.player.getInventory().getItem(slot);
                        if (hasFoodComponent(stack) && !AutoEaterConfig.isBlacklisted(stack)) {
                            int nutrition = stack.get(DataComponents.FOOD).nutrition();
                            if (nutrition > maxNutrition) {
                                maxNutrition = nutrition;
                            }
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
        int hotbarFoodSlot = findFoodSlot(client, state, 0, HOTBAR_SIZE);
        if (hotbarFoodSlot >= 0) {
            ItemStack hotbarStack = client.player.getInventory().getItem(hotbarFoodSlot);
            startEating(client, state, hotbarFoodSlot, hotbarStack, false, -1);
            return;
        }

        if (!AutoEaterConfig.inventoryScanEnabled) {
            return;
        }

        int inventoryFoodSlot = findFoodSlot(client, state, HOTBAR_SIZE, INVENTORY_SIZE);
        if (inventoryFoodSlot < 0 || client.gameMode == null) {
            return;
        }

        if (!swapInventorySlotIntoHotbar(client, inventoryFoodSlot, INVENTORY_FALLBACK_HOTBAR_SLOT)) {
            return;
        }

        ItemStack swappedStack = client.player.getInventory().getItem(INVENTORY_FALLBACK_HOTBAR_SLOT);
        if (!isFoodCandidate(swappedStack, state)) {
            return;
        }

        startEating(client, state, INVENTORY_FALLBACK_HOTBAR_SLOT, swappedStack, true, inventoryFoodSlot);
    }

    private static int findFoodSlot(Minecraft client, TickState state, int startSlot, int endSlotExclusive) {
        for (int slot = startSlot; slot < endSlotExclusive; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (isFoodCandidate(stack, state)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isFoodCandidate(ItemStack stack, TickState state) {
        if (!hasFoodComponent(stack) || AutoEaterConfig.isBlacklisted(stack)) {
            return false;
        }

        if (AutoEaterConfig.threshold == 0 || AutoEaterConfig.threshold == 20) {
            return stack.get(DataComponents.FOOD).nutrition() == state.threshold;
        }

        return true;
    }

    private static void startEating(
            Minecraft client,
            TickState state,
            int foodSlot,
            ItemStack foodStack,
            boolean swappedInventorySlotForEating,
            int swappedInventorySourceSlot
    ) {
        state.previousSlot = client.player.getInventory().getSelectedSlot();
        state.foodSlot = foodSlot;
        state.initialFoodCount = foodStack.getCount();
        state.switchedSlotForEating = state.previousSlot != foodSlot;
        state.startedUsingItem = false;
        state.swappedInventorySlotForEating = swappedInventorySlotForEating;
        state.swappedInventorySourceSlot = swappedInventorySourceSlot;
        client.player.getInventory().setSelectedSlot(foodSlot);
        state.eating = true;
        client.options.keyUse.setDown(true);
    }

    private static boolean swapInventorySlotIntoHotbar(Minecraft client, int inventorySlot, int hotbarSlot) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        int menuSlotIndex = getMenuSlotIndex(menu, inventorySlot);
        if (menuSlotIndex < 0) {
            return false;
        }

        client.gameMode.handleContainerInput(
                menu.containerId,
                menuSlotIndex,
                hotbarSlot,
                ContainerInput.SWAP,
                client.player
        );
        return true;
    }

    private static int getMenuSlotIndex(AbstractContainerMenu menu, int inventorySlot) {
        for (int slotIndex = 0; slotIndex < menu.slots.size(); slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (slot.container instanceof Inventory && slot.getContainerSlot() == inventorySlot) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static void restoreInventoryFallbackSwap(Minecraft client, TickState state) {
        if (!state.swappedInventorySlotForEating
                || state.swappedInventorySourceSlot < HOTBAR_SIZE
                || state.swappedInventorySourceSlot >= INVENTORY_SIZE) {
            return;
        }

        swapInventorySlotIntoHotbar(client, state.swappedInventorySourceSlot, INVENTORY_FALLBACK_HOTBAR_SLOT);
    }

    private static void stopEating(Minecraft client, TickState state) {
        forceReleaseUseAction(client);

        if (client.player != null
                && state.switchedSlotForEating
                && client.player.getInventory().getSelectedSlot() == state.foodSlot) {
            client.player.getInventory().setSelectedSlot(state.previousSlot);
        }

        restoreInventoryFallbackSwap(client, state);

        state.eating = false;
        state.switchedSlotForEating = false;
        state.foodSlot = 0;
        state.initialFoodCount = 0;
        state.startedUsingItem = false;
        state.swappedInventorySlotForEating = false;
        state.swappedInventorySourceSlot = -1;
    }

    private static void forceReleaseUseAction(Minecraft client) {
        client.options.keyUse.setDown(false);

        if (client.player == null) {
            return;
        }

        if (client.player.isUsingItem()) {
            client.player.stopUsingItem();
        }

        if (client.gameMode == null) {
            return;
        }

        try {
            Method stopUsingItem = client.gameMode.getClass().getMethod("stopUsingItem", Player.class);
            stopUsingItem.invoke(client.gameMode, client.player);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method releaseUsingItem = client.gameMode.getClass().getMethod("releaseUsingItem", Player.class);
            releaseUsingItem.invoke(client.gameMode, client.player);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean isCancelActive(TickState state) {
        return state.clientTicks < state.cancelUntilTick;
    }

    private static boolean hasFoodComponent(ItemStack stack) {
        return stack.has(DataComponents.FOOD);
    }
}
