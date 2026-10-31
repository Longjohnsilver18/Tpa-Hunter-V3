package de.tpahunter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class TpaHunterClient implements ClientModInitializer {
    public static final String MOD_ID = "tpahunter";
    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        // Auf deutscher Tastatur sitzt "ä" auf der Taste, die GLFW als APOSTROPHE meldet.
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tpahunter.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, category));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tpahunter.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new TpaScreen());
                }
            }
            while (toggleKey.wasPressed()) {
                ScanManager.startStop();
            }
            ScanManager.tick(client);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) ScanManager.onChat(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                ScanManager.onChat(message.getString()));
    }
}
