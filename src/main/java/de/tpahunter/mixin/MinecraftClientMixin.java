package de.tpahunter.mixin;

import de.tpahunter.ScanManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    // Verhindert, dass das /invsee-Fenster waehrend eines Hintergrund-Scans angezeigt wird.
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void tpahunter$hideScanScreen(Screen screen, CallbackInfo ci) {
        if (ScanManager.shouldIntercept(screen)) {
            ScanManager.onIntercepted();
            ci.cancel();
        }
    }
}
