package ru.refontstudio.refontcrafts.gui;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostCursorPolicyTest {
    @Test
    void permitsNormalClicksBetweenEditorSlots() {
        assertTrue(GhostCursorPolicy.canMoveInsideEditor(false, false, false, false));
    }

    @Test
    void rejectsPendingAndInventoryEscapeActions() {
        assertFalse(GhostCursorPolicy.canMoveInsideEditor(true, false, false, false));
        assertFalse(GhostCursorPolicy.canMoveInsideEditor(false, true, false, false));
        assertFalse(GhostCursorPolicy.canMoveInsideEditor(false, false, true, false));
        assertFalse(GhostCursorPolicy.canMoveInsideEditor(false, false, false, true));
    }

    @Test
    void recipeEditorReturnsRealItemAndDiscardsPreviewOnClose() throws Exception {
        assertSwapAndClose(RecipeEditorMenu.class, 11, "RecipeEditorMenu$EditorSession",
                new Class<?>[]{String.class, Inventory.class}, new Object[]{"recipe", null},
                new Class<?>[]{Player.class, Inventory.class, int.class,
                        Class.forName("ru.refontstudio.refontcrafts.gui.RecipeEditorMenu$EditorSession"),
                        ItemStack.class, boolean.class},
                new Class<?>[]{HumanEntity.class, Inventory.class,
                        Class.forName("ru.refontstudio.refontcrafts.gui.RecipeEditorMenu$EditorSession")});
    }

    @Test
    void anvilEditorReturnsRealItemAndDiscardsPreviewOnClose() throws Exception {
        Class<?> sessionType = Class.forName("ru.refontstudio.refontcrafts.gui.AnvilEditorMenu$EditorSession");
        assertSwapAndClose(AnvilEditorMenu.class, 12, "AnvilEditorMenu$EditorSession",
                new Class<?>[]{String.class, int.class, Inventory.class}, new Object[]{"recipe", 0, null},
                new Class<?>[]{Player.class, Inventory.class, int.class, sessionType,
                        ItemStack.class, boolean.class},
                new Class<?>[]{HumanEntity.class, Inventory.class, sessionType});
    }

    @SuppressWarnings("unchecked")
    private void assertSwapAndClose(Class<?> menuType, int targetSlot, String sessionName,
                                    Class<?>[] sessionConstructorTypes, Object[] sessionArguments,
                                    Class<?>[] moveTypes, Class<?>[] settleTypes) throws Exception {
        final Map<Integer, ItemStack> contents = new HashMap<Integer, ItemStack>();
        final List<ItemStack> returnedItems = new ArrayList<ItemStack>();
        final ItemStack[] cursor = {new ItemStack(Material.DIAMOND, 1)};
        Inventory top = proxy(Inventory.class, (proxy, method, args) -> {
            if ("getItem".equals(method.getName())) return contents.get(args[0]);
            if ("setItem".equals(method.getName())) {
                if (args[1] == null) contents.remove(args[0]);
                else contents.put((Integer) args[0], (ItemStack) args[1]);
                return null;
            }
            return defaultValue(method.getReturnType());
        });
        PlayerInventory playerInventory = proxy(PlayerInventory.class, (proxy, method, args) -> {
            if ("addItem".equals(method.getName())) {
                for (ItemStack item : (ItemStack[]) args[0]) returnedItems.add(item.clone());
                return new HashMap<Integer, ItemStack>();
            }
            return defaultValue(method.getReturnType());
        });
        Player player = proxy(Player.class, (proxy, method, args) -> {
            if ("getItemOnCursor".equals(method.getName())) return cursor[0];
            if ("setItemOnCursor".equals(method.getName())) {
                cursor[0] = args[0] == null ? null : ((ItemStack) args[0]).clone();
                return null;
            }
            if ("getInventory".equals(method.getName())) return playerInventory;
            return defaultValue(method.getReturnType());
        });

        contents.put(targetSlot, new ItemStack(Material.DIRT, 1));
        Object menu = menuType.getConstructor(ru.refontstudio.refontcrafts.RefontCrafts.class,
                ru.refontstudio.refontcrafts.storage.RecipeStorage.class).newInstance(null, null);
        Class<?> sessionType = Class.forName("ru.refontstudio.refontcrafts.gui." + sessionName);
        Constructor<?> sessionConstructor = sessionType.getDeclaredConstructor(sessionConstructorTypes);
        sessionConstructor.setAccessible(true);
        Object session = sessionConstructor.newInstance(sessionArgumentsWithInventory(sessionArguments, top));

        Field ghostSlots = sessionType.getDeclaredField("ghostSlots");
        ghostSlots.setAccessible(true);
        ((Set<Integer>) ghostSlots.get(session)).add(10);
        Field ghostCursor = sessionType.getDeclaredField("ghostCursor");
        ghostCursor.setAccessible(true);
        ghostCursor.setBoolean(session, true);

        Method move = menuType.getDeclaredMethod("moveEditableItem", moveTypes);
        move.setAccessible(true);
        move.invoke(menu, player, top, targetSlot, session, cursor[0], false);

        assertEquals(Material.DIAMOND, contents.get(targetSlot).getType());
        assertEquals(Material.DIRT, cursor[0].getType());

        Method settle = menuType.getDeclaredMethod("settleItems", settleTypes);
        settle.setAccessible(true);
        settle.invoke(menu, player, top, session);

        assertEquals(1, returnedItems.size());
        assertEquals(Material.DIRT, returnedItems.get(0).getType());
        assertTrue(cursor[0] == null);
        assertFalse(contents.containsKey(targetSlot));
    }

    private Object[] sessionArgumentsWithInventory(Object[] arguments, Inventory inventory) {
        Object[] actual = arguments.clone();
        actual[actual.length - 1] = inventory;
        return actual;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
