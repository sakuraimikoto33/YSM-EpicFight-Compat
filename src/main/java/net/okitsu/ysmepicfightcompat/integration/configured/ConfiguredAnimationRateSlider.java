package net.okitsu.ysmepicfightcompat.integration.configured;

import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.client.screen.ConfigScreen;
import com.mrcrayfish.configured.impl.neoforge.NeoForgeValue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.lwjgl.glfw.GLFW;

/** Optional, single-setting slider that retains Configured's original Forge value and save flow. */
public final class ConfiguredAnimationRateSlider {
    private static final String TRANSLATION =
            "config.ysm_epicfight_compat.animation_evaluation_rate_limit_hz";
    private static final int MINIMUM = ClientPreferences.MIN_ANIMATION_EVALUATION_RATE_HZ;
    private static final int MAXIMUM = ClientPreferences.MAX_ANIMATION_EVALUATION_RATE_HZ;
    private static final int LAST_STEP = MAXIMUM - MINIMUM + 1;

    private ConfiguredAnimationRateSlider() {
    }

    /** Object-only boundary: no Configured class is linked by the optional mixin handler. */
    public static Object createEntry(Object screen, Object entry, Runnable changed) {
        if (!(screen instanceof ConfigScreen configScreen)
                || !(entry instanceof IConfigEntry configEntry) || !configEntry.isLeaf()
                || !isRateValue(configEntry.getValue())) {
            return null;
        }
        @SuppressWarnings("unchecked")
        IConfigValue<Integer> holder = (IConfigValue<Integer>) configEntry.getValue();
        return new RateItem(configScreen, holder, changed);
    }

    static boolean isRateValue(Object value) {
        return value instanceof NeoForgeValue<?> forge
                && forge.configValue == ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ;
    }

    static double sliderPosition(int rate) {
        // Stored zero remains unlimited; only its visual position is the maximum.
        return rate <= 0 ? 1.0D
                : (Math.max(MINIMUM, Math.min(MAXIMUM, rate)) - MINIMUM) / (double) LAST_STEP;
    }

    static int rateAtPosition(double position) {
        double bounded = Double.isNaN(position) ? 1.0D : Math.max(0.0D, Math.min(1.0D, position));
        return rateAtStep((int) Math.round(bounded * LAST_STEP));
    }

    static int adjacentRate(int rate, int direction) {
        int step = (int) Math.round(sliderPosition(rate) * LAST_STEP);
        return rateAtStep(step + Integer.signum(direction));
    }

    private static int rateAtStep(int step) {
        int bounded = Math.max(0, Math.min(LAST_STEP, step));
        return bounded == LAST_STEP ? 0 : MINIMUM + bounded;
    }

    static void applySliderValue(IConfigValue<Integer> holder, double position, Runnable changed) {
        int rate = rateAtPosition(position);
        if (holder.isValid(rate) && holder.get() != rate) {
            holder.set(rate);
            changed.run();
        }
    }

    private static final class RateItem extends ConfigScreen.ConfigItem<Integer> {
        private final ConfigScreen screen;
        private final RateSlider slider;

        private RateItem(ConfigScreen screen, IConfigValue<Integer> holder, Runnable changed) {
            screen.super(holder);
            this.screen = screen;
            slider = new RateSlider(holder, changed);
            slider.active = !screen.getActiveConfig().isReadOnly();
            eventListeners.add(slider);
        }

        @Override
        protected void onResetValue() {
            slider.refreshFromHolder();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            // Configured's stock number row reserves only 80 pixels. Reserve a wider
            // control area here so neither the translated label nor reset overlaps it.
            int sliderWidth = Math.min(136, Math.max(80, width / 2 - 24));
            int sliderLeft = left + width - sliderWidth - 25;
            Font font = Minecraft.getInstance().font;
            int labelWidth = Math.max(0, sliderLeft - left - 8);
            MutableComponent displayLabel = label.copy();
            if (font.width(displayLabel) > labelWidth) {
                int ellipsisWidth = font.width("...");
                displayLabel = labelWidth < ellipsisWidth ? Component.empty()
                        : Component.literal(font.plainSubstrByWidth(label.getString(),
                        labelWidth - ellipsisWidth) + "...");
            }
            displayLabel.withStyle(holder.isChanged()
                    ? com.mrcrayfish.configured.Config.getChangedFormatting() : ChatFormatting.RESET);
            graphics.drawString(font, displayLabel, left, top + 6, 0xFFFFFF);
            boolean readOnly = screen.getActiveConfig().isReadOnly();
            slider.active = !readOnly;
            slider.refreshFromHolder();
            slider.setX(sliderLeft);
            slider.setY(top);
            slider.setWidth(sliderWidth);
            slider.render(graphics, mouseX, mouseY, partialTick);
            resetButton.active = !readOnly && !holder.isDefault();
            resetButton.setX(left + width - 21);
            resetButton.setY(top);
            resetButton.render(graphics, mouseX, mouseY, partialTick);
            if (tooltip != null && mouseX >= left && mouseX < sliderLeft
                    && mouseY >= top && mouseY < top + height) {
                screen.setActiveTooltip(tooltip);
            }
        }
    }

    private static final class RateSlider extends AbstractSliderButton {
        private final IConfigValue<Integer> holder;
        private final Runnable changed;

        private RateSlider(IConfigValue<Integer> holder, Runnable changed) {
            super(0, 0, 136, 20, Component.empty(), sliderPosition(holder.get()));
            this.holder = holder;
            this.changed = changed;
            updateMessage();
        }

        private void refreshFromHolder() {
            value = sliderPosition(holder.get());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int rate = rateAtPosition(value);
            setMessage(rate == 0 ? Component.translatable(TRANSLATION + ".unlimited")
                    : Component.translatable(TRANSLATION + ".value", rate));
        }

        @Override
        protected void applyValue() {
            // An already-started vanilla drag can still reach here after disabling.
            if (active && visible) {
                applySliderValue(holder, value, changed);
            }
            refreshFromHolder();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!active || !visible) {
                return false;
            }
            if (isFocused() && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
                // One legal setting per key press, independent of the widget's width.
                value = sliderPosition(adjacentRate(holder.get(),
                        keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1));
                applyValue();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
