package com.autoeater;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class AutoEaterConfigScreen extends Screen {
    private static final Component TITLE = Component.literal("Auto Eater Settings");

    private final Screen parent;
    private boolean killSwitchValue = AutoEaterConfig.killSwitch;
    private int thresholdValue = AutoEaterConfig.threshold;
    private int cancelCooldownValue = AutoEaterConfig.cancelCooldownSeconds;
    private String toggleKeyNameValue = AutoEaterClient.getToggleKeyName();
    private boolean listeningForToggleKey;
    private final List<String> blacklistValues = new ArrayList<>(AutoEaterConfig.blacklist);

    private Button killSwitchButton;
    private Button toggleKeyButton;
    private Button addBlacklistButton;
    private Button removeBlacklistButton;
    private Button resetBlacklistButton;
    private ThresholdSlider thresholdSlider;
    private CooldownSlider cancelCooldownSlider;
    private EditBox blacklistInput;
    private final List<Button> blacklistEntryButtons = new ArrayList<>();
    private final List<ScrollableWidget> scrollableWidgets = new ArrayList<>();
    private int scrollAreaTop;
    private int scrollAreaBottom;
    private int maxScroll;
    private int scrollOffset;
    private int layoutFieldX;
    private int layoutFieldWidth;
    private int layoutButtonsY;
    private int layoutControlHeight;
    private int layoutBlacklistTop;
    private int selectedBlacklistIndex = -1;
    private MainScrollBar mainScrollBar;

    public AutoEaterConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        scrollableWidgets.clear();
        scrollOffset = 0;

        int centerX = width / 2;
        int titleWidth = font.width(title);
        int titleY = 20;
        int titleGap = 20;
        int rowHeight = 27;
        int controlHeight = 20;
        int leftWidth = 110;
        int fieldWidth = 186;
        int addButtonWidth = 56;
        int panelWidth = leftWidth + 10 + fieldWidth;
        int panelLeft = centerX - (panelWidth / 2);
        int left = panelLeft;
        int fieldX = panelLeft + leftWidth + 10;
        int rowY = titleY + font.lineHeight + titleGap;
        int buttonsY = height - 28;
        layoutFieldX = fieldX;
        layoutFieldWidth = fieldWidth;
        layoutButtonsY = buttonsY;
        layoutControlHeight = controlHeight;
        scrollAreaTop = rowY;
        scrollAreaBottom = buttonsY - 8;

        addRenderableOnly(new StringWidget(centerX - (titleWidth / 2), titleY, titleWidth, controlHeight, title, font));
        mainScrollBar = addRenderableOnly(new MainScrollBar(panelLeft + panelWidth + 8, scrollAreaTop, 6, scrollAreaBottom - scrollAreaTop));

        addScrollableWidget(addRenderableOnly(new StringWidget(left, rowY + 5, leftWidth, controlHeight, Component.literal("Kill Switch"), font)), rowY + 5);
        killSwitchButton = addScrollableWidget(addRenderableWidget(Button.builder(toggleLabel(), button -> {
            killSwitchValue = !killSwitchValue;
            button.setMessage(toggleLabel());
        }).bounds(fieldX, rowY, fieldWidth, controlHeight).build()), rowY);
        killSwitchButton.setTooltip(Tooltip.create(Component.literal("Turns auto eating off completely.")));

        rowY += rowHeight;
        addScrollableWidget(addRenderableOnly(new StringWidget(left, rowY + 5, leftWidth, controlHeight, Component.literal("Toggle Key"), font)), rowY + 5);
        toggleKeyButton = addScrollableWidget(addRenderableWidget(Button.builder(toggleKeyLabel(), button -> {
            listeningForToggleKey = !listeningForToggleKey;
            button.setMessage(toggleKeyLabel());
        }).bounds(fieldX, rowY, fieldWidth, controlHeight).build()), rowY);

        rowY += rowHeight;
        addScrollableWidget(addRenderableOnly(new StringWidget(left, rowY + 5, leftWidth, controlHeight, Component.literal("Hunger Threshold"), font)), rowY + 5);
        thresholdSlider = addScrollableWidget(addRenderableWidget(new ThresholdSlider(fieldX, rowY, fieldWidth, controlHeight)), rowY);
        thresholdSlider.setTooltip(Tooltip.create(Component.literal("Auto modes use the lowest or highest food value in your hotbar.")));

        rowY += rowHeight;
        addScrollableWidget(addRenderableOnly(new StringWidget(left, rowY + 5, leftWidth, controlHeight, Component.literal("Cancel Cooldown"), font)), rowY + 5);
        cancelCooldownSlider = addScrollableWidget(addRenderableWidget(new CooldownSlider(fieldX, rowY, fieldWidth, controlHeight)), rowY);
        cancelCooldownSlider.setTooltip(Tooltip.create(Component.literal("Wait time before auto eating can start again after canceling.")));

        rowY += rowHeight;
        addScrollableWidget(addRenderableOnly(new StringWidget(left, rowY + 5, leftWidth, controlHeight, Component.literal("Blacklist"), font)), rowY + 5);
        blacklistInput = addScrollableWidget(addRenderableWidget(new EditBox(font, fieldX, rowY, fieldWidth - addButtonWidth - 5, controlHeight, Component.literal("minecraft:item"))), rowY);
        blacklistInput.setMaxLength(128);
        blacklistInput.setHint(Component.literal("minecraft:item"));
        blacklistInput.setTooltip(Tooltip.create(Component.literal("Enter an item id, like bread or minecraft:bread. mod:item also works for modded items.")));
        addBlacklistButton = addScrollableWidget(addRenderableWidget(Button.builder(Component.literal("Add"), button -> addBlacklistEntry())
                .bounds(fieldX + fieldWidth - addButtonWidth, rowY, addButtonWidth, controlHeight)
                .build()), rowY);

        rowY += rowHeight;
        layoutBlacklistTop = rowY;
        rowY = layoutBlacklistTop + 30;
        removeBlacklistButton = addScrollableWidget(addRenderableWidget(Button.builder(Component.literal("Remove"), button -> removeSelectedBlacklistEntry())
                .bounds(fieldX, rowY, (fieldWidth - 5) / 2, controlHeight)
                .build()), rowY);
        resetBlacklistButton = addScrollableWidget(addRenderableWidget(Button.builder(Component.literal("Defaults"), button -> resetBlacklistToDefaults())
                .bounds(fieldX + ((fieldWidth - 5) / 2) + 5, rowY, (fieldWidth - 5) / 2, controlHeight)
                .build()), rowY);
        resetBlacklistButton.setTooltip(Tooltip.create(Component.literal("Restores the built-in blacklist.")));
        refreshBlacklistEntries();
        removeBlacklistButton.active = false;

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            saveAndClose();
        }).bounds(centerX + 5, buttonsY, 150, controlHeight).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose()).bounds(centerX - 155, buttonsY, 150, controlHeight).build());

        setInitialFocus(blacklistInput);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listeningForToggleKey) {
            int keyCode = event.key();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listeningForToggleKey = false;
            } else {
                toggleKeyNameValue = InputConstants.getKey(event).getName();
                listeningForToggleKey = false;
            }
            toggleKeyButton.setMessage(toggleKeyLabel());
            return true;
        }

        if (blacklistInput != null
                && blacklistInput.isFocused()
                && event.key() == GLFW.GLFW_KEY_ENTER) {
            addBlacklistEntry();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (mouseY >= scrollAreaTop && mouseY <= scrollAreaBottom && maxScroll > 0) {
            scrollOffset = clampScroll(scrollOffset - (int) (verticalAmount * 16.0));
            applyScroll();
            return true;
        }
        return false;
    }

    private Component toggleLabel() {
        return Component.literal(killSwitchValue ? "Enabled" : "Disabled")
                .withStyle(killSwitchValue ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private Component thresholdLabel() {
        if (thresholdValue == 0) {
            return Component.literal("Auto (min. nutrition)");
        }
        if (thresholdValue == 20) {
            return Component.literal("Auto (max. nutrition)");
        }
        return Component.literal(Integer.toString(thresholdValue));
    }

    private Component cancelCooldownLabel() {
        return cancelCooldownValue == 0
                ? Component.literal("Off")
                : Component.literal(cancelCooldownValue + "s");
    }

    private Component toggleKeyLabel() {
        if (listeningForToggleKey) {
            Component keyName = InputConstants.getKey(toggleKeyNameValue)
                    .getDisplayName()
                    .copy()
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE);
            return Component.empty()
                    .append(Component.literal("> ").withStyle(ChatFormatting.YELLOW))
                    .append(keyName)
                    .append(Component.literal(" <").withStyle(ChatFormatting.YELLOW));
        }
        return InputConstants.getKey(toggleKeyNameValue).getDisplayName();
    }

    private void saveAndClose() {
        AutoEaterConfig.killSwitch = killSwitchValue;
        AutoEaterConfig.threshold = thresholdValue;
        AutoEaterConfig.cancelCooldownSeconds = cancelCooldownValue;
        AutoEaterConfig.blacklist = new ArrayList<>(blacklistValues);
        AutoEaterConfig.saveConfig();
        AutoEaterClient.setToggleKey(toggleKeyNameValue);
        onClose();
    }

    private void addBlacklistEntry() {
        List<String> parsed = AutoEaterConfig.parseBlacklist(blacklistInput.getValue());
        if (parsed.isEmpty()) {
            return;
        }

        String value = parsed.getFirst();
        if (!blacklistValues.contains(value)) {
            blacklistValues.add(value);
            refreshBlacklistEntries();
        }

        blacklistInput.setValue("");
    }

    private void removeSelectedBlacklistEntry() {
        int selectedIndex = getSelectedBlacklistIndex();
        if (selectedIndex < 0) {
            return;
        }

        blacklistValues.remove(selectedIndex);
        refreshBlacklistEntries();
    }

    private void resetBlacklistToDefaults() {
        blacklistValues.clear();
        blacklistValues.addAll(AutoEaterConfig.DEFAULT_BLACKLIST);
        refreshBlacklistEntries();
    }

    private void refreshBlacklistEntries() {
        selectedBlacklistIndex = -1;
        for (Button button : blacklistEntryButtons) {
            scrollableWidgets.removeIf(entry -> entry.widget == button);
            removeWidget(button);
        }
        blacklistEntryButtons.clear();

        int rowY = layoutBlacklistTop;
        for (int i = 0; i < blacklistValues.size(); i++) {
            final int index = i;
            Button entryButton = addRenderableWidget(Button.builder(blacklistLabel(index), button -> selectBlacklistEntry(index))
                    .bounds(layoutFieldX, rowY, layoutFieldWidth, layoutControlHeight)
                    .build());
            addScrollableWidget(entryButton, rowY);
            blacklistEntryButtons.add(entryButton);
            rowY += layoutControlHeight + 2;
        }

        relayoutBlacklistSection();
        if (removeBlacklistButton != null) {
            removeBlacklistButton.active = false;
        }
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(maxScroll, value));
    }

    private void applyScroll() {
        for (ScrollableWidget widget : scrollableWidgets) {
            int y = widget.baseY - scrollOffset;
            boolean visible = y + widget.widget.getHeight() > scrollAreaTop && y < scrollAreaBottom;
            widget.widget.visible = visible;
            widget.widget.setY(y);
        }
        if (mainScrollBar != null) {
            mainScrollBar.visible = maxScroll > 0;
        }
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T addScrollableWidget(T widget, int baseY) {
        scrollableWidgets.add(new ScrollableWidget(widget, baseY));
        return widget;
    }

    private void setScrollableBaseY(net.minecraft.client.gui.components.AbstractWidget widget, int baseY) {
        for (ScrollableWidget entry : scrollableWidgets) {
            if (entry.widget == widget) {
                entry.baseY = baseY;
                return;
            }
        }
    }

    private void relayoutBlacklistSection() {
        int listHeight = Math.max(24, blacklistEntryButtons.size() * (layoutControlHeight + 2));
        int actionButtonsY = layoutBlacklistTop + listHeight + 6;
        int buttonWidth = (layoutFieldWidth - 5) / 2;

        removeBlacklistButton.setWidth(buttonWidth);
        removeBlacklistButton.setX(layoutFieldX);
        setScrollableBaseY(removeBlacklistButton, actionButtonsY);

        resetBlacklistButton.setWidth(buttonWidth);
        resetBlacklistButton.setX(layoutFieldX + buttonWidth + 5);
        setScrollableBaseY(resetBlacklistButton, actionButtonsY);

        int contentBottom = actionButtonsY + layoutControlHeight;
        maxScroll = Math.max(0, contentBottom - scrollAreaBottom);
        scrollOffset = clampScroll(scrollOffset);
        applyScroll();
    }

    private int getSelectedBlacklistIndex() {
        return selectedBlacklistIndex;
    }

    private Component blacklistLabel(int index) {
        boolean selected = index == selectedBlacklistIndex;
        String value = blacklistValues.get(index);
        return selected
                ? Component.literal("> " + value + " <").withStyle(ChatFormatting.YELLOW)
                : Component.literal(value);
    }

    private void selectBlacklistEntry(int index) {
        selectedBlacklistIndex = index;
        for (int i = 0; i < blacklistEntryButtons.size(); i++) {
            blacklistEntryButtons.get(i).setMessage(blacklistLabel(i));
        }
        removeBlacklistButton.active = index >= 0;
    }

    private final class ThresholdSlider extends AbstractSliderButton {
        private ThresholdSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), thresholdValue / 20.0D);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(thresholdLabel());
        }

        @Override
        protected void applyValue() {
            thresholdValue = (int) Math.round(value * 20.0D);
            updateMessage();
        }
    }

    private final class CooldownSlider extends AbstractSliderButton {
        private CooldownSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), cancelCooldownValue / 30.0D);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(cancelCooldownLabel());
        }

        @Override
        protected void applyValue() {
            cancelCooldownValue = (int) Math.round(value * 30.0D);
            updateMessage();
        }
    }

    private static final class ScrollableWidget {
        private final net.minecraft.client.gui.components.AbstractWidget widget;
        private int baseY;

        private ScrollableWidget(net.minecraft.client.gui.components.AbstractWidget widget, int baseY) {
            this.widget = widget;
            this.baseY = baseY;
        }
    }

    private final class MainScrollBar extends net.minecraft.client.gui.components.AbstractWidget {
        private MainScrollBar(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (maxScroll <= 0) {
                return;
            }

            graphics.fill(getX(), getY(), getRight(), getBottom(), 0x80303030);

            int thumbHeight = Math.max(18, (getHeight() * getHeight()) / (getHeight() + maxScroll));
            int thumbTravel = Math.max(0, getHeight() - thumbHeight);
            int thumbY = getY() + (thumbTravel * scrollOffset / maxScroll);
            graphics.fill(getX() + 1, thumbY, getRight() - 1, thumbY + thumbHeight, 0xC0FFFFFF);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
        }
    }
}
