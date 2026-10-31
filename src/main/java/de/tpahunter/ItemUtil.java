package de.tpahunter;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public final class ItemUtil {
    private ItemUtil() {}

    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    public static ItemStack stack(String id) {
        return STACKS.computeIfAbsent(id, k -> {
            Identifier ident = Identifier.tryParse(k);
            if (ident == null) return ItemStack.EMPTY;
            Item item = Registries.ITEM.get(ident);
            return item == Items.AIR ? ItemStack.EMPTY : item.getDefaultStack();
        });
    }

    public static String name(String id) {
        ItemStack s = stack(id);
        return s.isEmpty() ? id : s.getName().getString();
    }
}
