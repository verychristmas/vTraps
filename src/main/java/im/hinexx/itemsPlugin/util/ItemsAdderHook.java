package im.hinexx.itemsPlugin.util;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.inventory.ItemStack;

/**
 * Isolated ItemsAdder access so missing IA does not break class loading of item logic.
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static ItemStack getCustomItem(String namespacedId) {
        CustomStack stack = CustomStack.getInstance(namespacedId);
        if (stack == null) {
            return null;
        }
        return stack.getItemStack();
    }
}
