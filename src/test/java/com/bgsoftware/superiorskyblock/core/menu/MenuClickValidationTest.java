package com.bgsoftware.superiorskyblock.core.menu;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.handlers.MenusManager;
import com.bgsoftware.superiorskyblock.api.menu.BaseMenu;
import com.bgsoftware.superiorskyblock.api.menu.Menu;
import com.bgsoftware.superiorskyblock.api.menu.MenuCommands;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuTemplateButton;
import com.bgsoftware.superiorskyblock.api.menu.button.MenuViewButton;
import com.bgsoftware.superiorskyblock.api.menu.button.click.ButtonClickContext;
import com.bgsoftware.superiorskyblock.api.menu.layout.MenuLayout;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import com.bgsoftware.superiorskyblock.api.menu.view.ViewArgs;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.inventory.Inventory;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class MenuClickValidationTest {
    @Test public void staleMenuCannotRunConfiguredHooksBeforeItIsRejected() throws Exception {
        exercise(false, false, false, Arrays.asList("guard"));
    }

    @Test public void validMenuPreservesConfiguredHookAndBuiltInOrder() throws Exception {
        exercise(true, false, false, Arrays.asList("guard", "hook:first", "hook:second", "post-guard", "builtin"));
    }

    @Test public void aConfiguredHookChangingTheEpochCannotRunTheBuiltInAction() throws Exception {
        exercise(true, false, true, Arrays.asList("guard", "hook:first", "hook:second", "post-guard"));
    }

    @Test public void deniedButtonDoesNotReachHooksOrSubjectValidation() throws Exception {
        exercise(true, true, false, Arrays.asList("permission-denied"));
    }

    @SuppressWarnings("unchecked")
    private static void exercise(boolean valid, boolean denied, boolean invalidateDuringHook, List<String> expected) throws Exception {
        List<String> effects = new ArrayList<>();
        AtomicLong epoch = new AtomicLong(valid ? 0L : 1L);
        MenuCommands commands = proxy(MenuCommands.class, (method, arguments) -> {
            if (method.equals("runCommand")) {
                effects.add("hook:" + arguments[1]);
                if (invalidateDuringHook) epoch.incrementAndGet();
            }
            return null;
        });
        MenusManager menus = proxy(MenusManager.class, (method, arguments) -> method.equals("getMenuCommands") ? commands : null);
        SuperiorSkyblock plugin = proxy(SuperiorSkyblock.class, (method, arguments) -> method.equals("getMenus") ? menus : null);
        Field pluginField = SuperiorSkyblockAPI.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        Object previous = pluginField.get(null);
        try {
            pluginField.set(null, plugin);
            MenuLayout<TestView> layout = proxy(MenuLayout.class, (method, arguments) -> null);
            TestMenu menu = new TestMenu(layout, effects, epoch);
            SuperiorPlayer viewer = proxy(SuperiorPlayer.class, (method, arguments) -> method.equals("hasPermission") ? !denied : null);
            TestView view = new TestView(menu, viewer);
            final MenuTemplateButton<TestView>[] template = new MenuTemplateButton[1];
            MenuViewButton<TestView> button = proxy(MenuViewButton.class, (method, arguments) -> {
                if (method.equals("getTemplate")) return template[0];
                if (method.equals("onButtonClick")) effects.add("builtin");
                return null;
            });
            template[0] = proxy(MenuTemplateButton.class, (method, arguments) -> {
                if (method.equals("createViewButton")) return button;
                if (method.equals("getRequiredPermission")) return denied ? "test.menu" : null;
                if (method.equals("getClickCommands")) return Arrays.asList("first", "second");
                return null;
            });
            ButtonClickContext<TestView> context = proxy(ButtonClickContext.class, (method, arguments) -> {
                if (method.equals("getMenuView")) return view;
                if (method.equals("getClickedButton")) return template[0];
                return null;
            });
            menu.onClick(context);
            assertEquals(expected, effects);
        } finally { pluginField.set(null, previous); }
    }

    private interface Call { Object invoke(String name, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args));
    }

    private static final class TestMenu extends BaseMenu<TestView, ViewArgs> {
        private final List<String> effects;
        private final AtomicLong epoch;
        TestMenu(MenuLayout<TestView> layout, List<String> effects, AtomicLong epoch) {
            super("fixture", layout, null, false, false); this.effects = effects; this.epoch = epoch;
        }
        @Override public CompletableFuture<TestView> createView(SuperiorPlayer viewer, ViewArgs args, MenuView<?, ?> previous) {
            return CompletableFuture.completedFuture(new TestView(this, viewer));
        }
        @Override protected boolean onPreButtonClick(MenuViewButton<TestView> button, ButtonClickContext<TestView> context) {
            effects.add("guard"); return epoch.get() == 0L;
        }
        @Override protected boolean onPostConfiguredButtonCommands(MenuViewButton<TestView> button, ButtonClickContext<TestView> context) {
            effects.add("post-guard"); return epoch.get() == 0L;
        }
        @Override protected void onButtonClickLackPermission(MenuViewButton<TestView> button, ButtonClickContext<TestView> context) {
            effects.add("permission-denied");
        }
        @Override protected void onCloseInternal(TestView view) { }
    }
    private static final class TestView implements MenuView<TestView, ViewArgs> {
        private final TestMenu menu;
        private final SuperiorPlayer viewer;
        TestView(TestMenu menu, SuperiorPlayer viewer) { this.menu = menu; this.viewer = viewer; }
        @Override public SuperiorPlayer getInventoryViewer() { return viewer; }
        @Override public Menu<TestView, ViewArgs> getMenu() { return menu; }
        @Override public MenuView<?, ?> getPreviousMenuView() { return null; }
        @Override public void setPreviousMenuView(MenuView<?, ?> view, boolean keep) { }
        @Override public void setPreviousMove(boolean previous) { }
        @Override public boolean isPreviousMenu() { return false; }
        @Override public void refreshView() { }
        @Override public void closeView() { }
        @Override public Inventory getInventory() { return null; }
    }
}
