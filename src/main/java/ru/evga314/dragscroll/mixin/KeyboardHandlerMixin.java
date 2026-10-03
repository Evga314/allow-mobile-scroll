package ru.evga314.dragscroll.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.wheel.MouseWheelEmulator;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Sees the wheel-emulator toggle key before other mods can cancel keyPress
 * (FancyMenu cancels it for keys its overlay windows consume). Low priority
 * value so this HEAD callback runs ahead of theirs.
 */
@Mixin(value = KeyboardHandler.class, priority = 100)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void dragscroll$earlyWheelToggle(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (SafeMode.bypass() || action != InputConstants.PRESS) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null || mc.screen == null || handle != mc.getWindow().handle()) {
                return;
            }
            MouseWheelEmulator.onKeyPressEarly(event);
        } catch (Throwable t) {
            SafeMode.reportFailure("KeyboardHandler.keyPress", t);
        }
    }
}
