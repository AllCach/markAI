package dev.markai;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.markai.ai.LlmProvider;
import dev.markai.ai.OllamaClient;
import dev.markai.client.ClientCompanionState;
import dev.markai.client.CompanionScreen;
import dev.markai.client.MarkAIClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.impl.client.gametest.util.WindowHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.sdl.SDLVideo;

/** Actual native UI, real owner packets and screenshots, with a deterministic AI provider. */
@Environment(EnvType.CLIENT)
public final class ClientUiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        WindowState originalWindow = context.computeOnClient(client -> {
            var window = client.getWindow(); var hooks = (WindowHooks) (Object) window;
            return new WindowState(client.options.fullscreen().get(), client.options.guiScale().get(),
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                hooks.fabric_getRealWidth(), hooks.fabric_getRealHeight());
        });
        var windowChecks = new ArrayList<Map<String, Object>>();
        var pending = new CompletableFuture<OllamaClient.Reply>();
        var calls = new AtomicInteger();
        LlmProvider fake = new LlmProvider() {
            @Override public CompletableFuture<OllamaClient.Reply> ask(String observation, String question, List<OllamaClient.ChatMessage> history) {
                return calls.incrementAndGet() == 1 ? CompletableFuture.completedFuture(new OllamaClient.Reply("say", "Запомнил: тебе нравятся берёзовые дома.")) : pending;
            }
            @Override public void close() { pending.cancel(true); }
        };
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = world.getConnection();
            UUID ownerId = context.computeOnClient(client -> client.player.getUUID());
            MarkAIMod mod = mod(); Field provider = field(MarkAIMod.class, "ollama");
            var original = new AtomicReference<LlmProvider>();
            var restorePreferences = new AtomicReference<Runnable>(() -> { });
            server.runOnServer(instance -> {
                try { original.set((LlmProvider) provider.get(mod)); provider.set(mod, fake); }
                catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                ServerPlayer owner = instance.getPlayerList().getPlayer(ownerId);
                instance.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
                try {
                    var companion = (MarkAIMod.Companion) ((Map<?, ?>) field(MarkAIMod.class, "companions").get(mod)).get(ownerId);
                    String name = companion.living.name;
                    boolean hud = companion.living.settings.hud;
                    double loyalty = companion.living.personality.loyalty;
                    Wolf testWolf = wolf(owner);
                    restorePreferences.set(() -> {
                        companion.living.name = name; companion.living.settings.hud = hud; companion.living.personality.loyalty = loyalty;
                        if (testWolf.isAlive()) testWolf.setCustomName(net.minecraft.network.chat.Component.literal(name));
                    });
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            });
            try {
                connection.waitForClientboundEntityUpdates(net.minecraft.world.entity.EntityTypes.WOLF);
                pace(context); context.getInput().pressKey(KeyMapping.get("key.markai.stay"));
                connection.waitForServerboundPackets();
                server.waitFor(instance -> wolf(instance.getPlayerList().getPlayer(ownerId)).isOrderedToSit());
                pace(context); context.getInput().pressKey(KeyMapping.get("key.markai.follow"));
                connection.waitForServerboundPackets();
                server.waitFor(instance -> !wolf(instance.getPlayerList().getPlayer(ownerId)).isOrderedToSit());
                context.getInput().pressKey(KeyMapping.get("key.markai.open"));
                context.waitForScreen(CompanionScreen.class);
                context.waitFor(client -> MarkAIClient.STATE.received() && MarkAIClient.preview() != null);
                pace(context); context.getInput().pressKey(KeyMapping.get("key.markai.stay"));
                connection.waitForServerboundPackets();
                context.waitTicks(5);
                server.runOnServer(instance -> check(!wolf(instance.getPlayerList().getPlayer(ownerId)).isOrderedToSit(),
                    "A command hotkey pressed inside a GUI must not issue an order"));
                context.takeScreenshot("markai-profile");
                context.clickScreenButton("markai.tab.commands");
                pace(context); context.clickScreenButton("markai.command.stay"); connection.waitForServerboundPackets();
                server.waitFor(instance -> wolf(instance.getPlayerList().getPlayer(ownerId)).isOrderedToSit());
                context.waitFor(client -> MarkAIClient.STATE.string("activity", "").equals("STAY"));
                pace(context); context.clickScreenButton("markai.command.follow"); connection.waitForServerboundPackets();
                server.waitFor(instance -> !wolf(instance.getPlayerList().getPlayer(ownerId)).isOrderedToSit());
                context.waitFor(client -> !MarkAIClient.STATE.string("activity", "STAY").equals("STAY"));
                context.takeScreenshot("markai-commands");

                context.clickScreenButton("markai.tab.dialogue");
                context.getInput().typeChars("Запомни: я люблю берёзовые дома.");
                assertDraftSurvivesResize(context, "Запомни: я люблю берёзовые дома.");
                enterFullscreen(context, "Запомни: я люблю берёзовые дома.");
                exerciseFullscreenTabs(context, windowChecks, "scale2");
                context.runOnClient(client -> check(editBox(client.gui.screen()).getValue().equals("Запомни: я люблю берёзовые дома."),
                    "Switching all tabs in fullscreen must preserve the unsent dialogue draft"));
                pace(context); context.clickScreenButton("markai.ui.send"); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.messages().size() >= 2);
                assertLayout(context, windowChecks, "fullscreen-dialogue");
                check(calls.get() == 1, "GUI Send must call the server provider once");
                context.takeScreenshot("markai-dialogue");
                server.runOnServer(instance -> resetCooldown(mod, ownerId));
                long chatReady = System.nanoTime() + 1_150_000_000L;
                while (System.nanoTime() < chatReady) context.waitTick();
                context.getInput().typeChars("Что скажешь?");
                pace(context); context.clickScreenButton("markai.ui.send"); connection.waitForServerboundPackets();
                context.waitFor(client -> calls.get() == 2);
                context.waitFor(client -> !MarkAIClient.STATE.bool("can_chat", true));
                context.getInput().typeChars("Следующий вопрос");
                context.runOnClient(client -> {
                    client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                    check(editBox(client.gui.screen()).getValue().equals("Следующий вопрос"), "Enter while Mark is thinking must preserve the next draft");
                });
                pace(context); context.clickScreenButton("markai.ui.cancel"); connection.waitForServerboundPackets();
                context.waitFor(client -> pending.isCancelled());
                context.runOnClient(client -> { MarkAIClient.cancel(); MarkAIClient.cancel(); });
                connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.notice().contains("Повторите действие через секунду"));
                context.runOnClient(client -> MarkAIClient.STATE.notice("status", ""));

                context.clickScreenButton("markai.tab.memories");
                context.waitFor(client -> MarkAIClient.STATE.memories().toString().contains("берёзовые"));
                clickMatching(context, "люблю", false);
                assertLayout(context, windowChecks, "fullscreen-memories");
                context.takeScreenshot("markai-memories");
                pace(context); context.clickScreenButton("markai.ui.delete"); connection.waitForServerboundPackets();
                context.waitFor(client -> !MarkAIClient.STATE.memories().toString().contains("берёзовые"));

                context.clickScreenButton("markai.tab.settings");
                boolean hud = context.computeOnClient(client -> ClientCompanionState.bool(MarkAIClient.STATE.settings(), "hud", true));
                pace(context); clickMatching(context, text("markai.setting.hud"), true); connection.waitForServerboundPackets();
                context.waitFor(client -> ClientCompanionState.bool(MarkAIClient.STATE.settings(), "hud", true) != hud);
                context.runOnClient(client -> editBox(client.gui.screen()).setValue(""));
                assertDraftSurvivesResize(context, "");
                context.runOnClient(client -> {
                    var screen = client.gui.screen();
                    screen.tick();
                    Button rename = screen.children().stream().filter(widget -> widget instanceof Button).map(widget -> (Button) widget)
                        .filter(button -> button.getMessage().getString().equals(text("markai.ui.rename"))).findFirst().orElseThrow();
                    check(!rename.active, "A blank companion name must disable Rename");
                    editBox(screen).setValue("   ");
                    screen.tick();
                    check(!rename.active, "A whitespace companion name must disable Rename");
                    editBox(screen).setValue("Марк Тест");
                    screen.tick();
                    check(rename.active, "A valid companion name must enable Rename");
                });
                assertDraftSurvivesResize(context, "Марк Тест");
                pace(context); context.clickScreenButton("markai.ui.rename"); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.string("name", "").equals("Марк Тест"));
                assertLayout(context, windowChecks, "fullscreen-settings");
                context.takeScreenshot("markai-settings");
                context.clickScreenButton("markai.ui.personality");
                double loyalty = context.computeOnClient(client -> ClientCompanionState.number(MarkAIClient.STATE.traits(), "loyalty", 50));
                pace(context); clickMatching(context, text("markai.trait.loyalty"), true); connection.waitForServerboundPackets();
                context.waitFor(client -> ClientCompanionState.number(MarkAIClient.STATE.traits(), "loyalty", 50) != loyalty);
                assertLayout(context, windowChecks, "fullscreen-personality");
                context.takeScreenshot("markai-personality");

                // Real server history produces many wrapped rows; no artificial scroll-only fixture.
                server.runOnServer(instance -> {
                    try {
                        var companion = (MarkAIMod.Companion) ((Map<?, ?>) field(MarkAIMod.class, "companions").get(mod)).get(ownerId);
                        for (int i = 0; i < 12; i++) companion.memory.rememberExchange(
                            "Проверка истории " + i + ": " + "Мы идём через берёзовый лес и обсуждаем предстоящее путешествие. ".repeat(6),
                            new OllamaClient.Reply("say", "Тестовый ответ " + i + ": "
                                + "Я замечаю деревья, прислушиваюсь к лесу и спокойно держусь рядом. ".repeat(3)),
                            instance.getPlayerList().getPlayer(ownerId).level().getGameTime());
                    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                pace(context); context.runOnClient(client -> MarkAIClient.request()); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.messages().toString().contains("Проверка истории 11"));
                context.clickScreenButton("markai.tab.dialogue");
                assertDialogueScroll(context, windowChecks, "scale2");
                setGuiScale(context, 4);
                exerciseFullscreenTabs(context, windowChecks, "scale4");
                assertDialogueScroll(context, windowChecks, "scale4");

                context.clickScreenButton("markai.tab.commands");
                pace(context); context.clickScreenButton("markai.command.inventory"); connection.waitForServerboundPackets();
                context.waitFor(client -> client.gui.screen() instanceof AbstractContainerScreen<?>);
                context.takeScreenshot("markai-inventory");
                context.runOnClient(client -> client.player.closeContainer()); context.waitForScreen(null);
                pace(context); context.getInput().pressKey(KeyMapping.get("key.markai.inventory"));
                connection.waitForServerboundPackets();
                context.waitFor(client -> client.gui.screen() instanceof AbstractContainerScreen<?>);
                context.runOnClient(client -> client.player.closeContainer()); context.waitForScreen(null);
                context.getInput().pressKey(KeyMapping.get("key.markai.dialogue")); context.waitForScreen(CompanionScreen.class);
                context.runOnClient(client -> check(client.gui.screen().children().stream().anyMatch(widget -> widget instanceof EditBox), "H must open dialogue input"));
                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.markai.quick")); context.waitForScreen(CompanionScreen.class);
                assertLayout(context, windowChecks, "fullscreen-quick-commands");
                context.takeScreenshot("markai-quick-commands");
                context.clickScreenButton("×"); context.waitForScreen(null);
                context.getInput().pressKey(KeyMapping.get("key.markai.dialogue")); context.waitForScreen(CompanionScreen.class);
                context.getInput().typeChars("Черновик после полного экрана");
                restoreWindow(context, originalWindow);
                context.runOnClient(client -> check(editBox(client.gui.screen()).getValue().equals("Черновик после полного экрана"),
                    "Restoring the native window must preserve the unsent dialogue draft"));
                assertLayout(context, windowChecks, "restored-window-dialogue");
                context.takeScreenshot("markai-window-restored");
                closeWithEscape(context);
                pace(context); context.runOnClient(client -> MarkAIClient.setting("hud", "true")); connection.waitForServerboundPackets();
                context.waitFor(client -> ClientCompanionState.bool(MarkAIClient.STATE.settings(), "hud", false));
                context.waitTicks(5); context.takeScreenshot("markai-hud");
            } finally {
                try {
                    restoreWindow(context, originalWindow);
                    writeWindowChecks(windowChecks);
                } finally {
                    try {
                        server.runOnServer(instance -> {
                            restorePreferences.get().run();
                            try { provider.set(mod, original.get()); } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                        });
                    } finally { fake.close(); }
                }
            }
        }
        context.waitFor(client -> client.getConnection() == null);
        context.runOnClient(client -> {
            check(!MarkAIClient.chat("Привет"), "Disconnected chat must report that nothing was sent");
            check(MarkAIClient.STATE.errorNotice() && MarkAIClient.STATE.notice().equals(text("markai.ui.no_server")),
                "The no-server error must not be replaced by a sent notice");
            MarkAIClient.STATE.clear();
        });
    }

    private record WindowState(boolean fullscreen, int guiScale, int logicalWidth, int logicalHeight,
                               int framebufferWidth, int framebufferHeight, int nativeWidth, int nativeHeight) {}

    private static boolean nativeFullscreen(Window window) {
        try {
            // Windows borderless fullscreen intentionally uses SDL_SetWindowFullscreen(false).
            return field(Window.class, "borderlessFullscreen").getBoolean(window)
                || (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static void enterFullscreen(ClientGameTestContext context, String draft) {
        context.runOnClient(client -> {
            client.options.guiScale().set(2);
            client.getWindow().setFullscreen(true);
            client.getWindow().updateFullscreenIfChanged();
        });
        context.waitFor(client -> client.options.fullscreen().get() && nativeFullscreen(client.getWindow())
            && client.getWindow().queryFramebufferSize().width() > 0);
        context.runOnClient(client -> {
            var window = client.getWindow(); var nativeSize = window.queryFramebufferSize();
            // Fabric GameTest otherwise freezes the render target at 854x480 despite a native fullscreen window.
            ((WindowHooks) (Object) window).fabric_resize(nativeSize.width(), nativeSize.height());
            client.resizeGui(); client.gameRenderer.resize(nativeSize.width(), nativeSize.height());
        });
        context.waitFor(client -> client.gui.screen().width == client.getWindow().getGuiScaledWidth()
            && client.gui.screen().height == client.getWindow().getGuiScaledHeight());
        context.waitTicks(2);
        context.runOnClient(client -> {
            var window = client.getWindow(); var nativeSize = window.queryFramebufferSize();
            check(nativeFullscreen(window), "The actual native window must be fullscreen");
            check(window.getWidth() == nativeSize.width() && window.getHeight() == nativeSize.height(),
                "The fullscreen UI must render at the actual native framebuffer resolution");
            check(editBox(client.gui.screen()).getValue().equals(draft), "Native fullscreen must preserve the unsent draft");
        });
    }

    private static void setGuiScale(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); });
        context.waitFor(client -> client.gui.screen().width == client.getWindow().getGuiScaledWidth()
            && client.gui.screen().height == client.getWindow().getGuiScaledHeight());
        context.waitTicks(2);
        context.runOnClient(client -> {
            check(nativeFullscreen(client.getWindow()), "Changing GUI scale must retain native fullscreen");
            if (client.getWindow().getScreenWidth() / scale >= 320 && client.getWindow().getScreenHeight() / scale >= 240)
                check(client.getWindow().getGuiScale() == scale, "A supported requested GUI scale must actually be applied");
        });
    }

    private static void exerciseFullscreenTabs(ClientGameTestContext context, List<Map<String, Object>> checks, String scale) {
        for (String tab : List.of("profile", "commands", "memories", "settings", "dialogue")) {
            context.clickScreenButton("markai.tab." + tab);
            context.waitTicks(1);
            assertLayout(context, checks, "fullscreen-" + scale + "-" + tab);
            if (tab.equals("profile") || tab.equals("commands"))
                context.takeScreenshot("markai-fullscreen-" + tab + "-" + scale);
        }
    }

    private static void assertLayout(ClientGameTestContext context, List<Map<String, Object>> checks, String stage) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            check(screen instanceof CompanionScreen, "Companion screen expected at " + stage);
            var widgets = screen.children().stream().filter(widget -> widget instanceof Button || widget instanceof EditBox)
                .map(widget -> (AbstractWidget) widget).filter(widget -> widget.visible).toList();
            for (int i = 0; i < widgets.size(); i++) {
                var widget = widgets.get(i);
                String name = widget.getMessage().getString();
                check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= screen.width
                    && widget.getBottom() <= screen.height, stage + ": widget outside visible screen: " + name);
                for (int j = i + 1; j < widgets.size(); j++) {
                    var other = widgets.get(j);
                    boolean overlap = widget.getX() < other.getRight() && widget.getRight() > other.getX()
                        && widget.getY() < other.getBottom() && widget.getBottom() > other.getY();
                    check(!overlap, stage + ": interactive widgets overlap: " + name + " / " + other.getMessage().getString());
                }
            }
            var window = client.getWindow(); var hooks = (WindowHooks) (Object) window;
            var size = window.queryFramebufferSize();
            var record = new LinkedHashMap<String, Object>();
            record.put("stage", stage); record.put("native_fullscreen", nativeFullscreen(window));
            try { record.put("native_borderless", field(Window.class, "borderlessFullscreen").getBoolean(window)); }
            catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            record.put("sdl_fullscreen_flag", (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0);
            record.put("native_window_width", hooks.fabric_getRealWidth()); record.put("native_window_height", hooks.fabric_getRealHeight());
            record.put("native_framebuffer_width", size.width()); record.put("native_framebuffer_height", size.height());
            record.put("render_width", window.getWidth()); record.put("render_height", window.getHeight());
            record.put("gui_width", screen.width); record.put("gui_height", screen.height);
            record.put("gui_scale_option", client.options.guiScale().get()); record.put("actual_gui_scale", window.getGuiScale());
            record.put("visible_widgets", widgets.size()); record.put("all_widgets_inside", true); record.put("no_widget_overlap", true);
            checks.add(record);
        });
    }

    private static void assertDialogueScroll(ClientGameTestContext context, List<Map<String, Object>> checks, String stage) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            try {
                int top = field(CompanionScreen.class, "contentTop").getInt(screen);
                int bottom = field(CompanionScreen.class, "contentBottom").getInt(screen);
                int panelWidth = field(CompanionScreen.class, "panelWidth").getInt(screen);
                int rows = 0;
                for (var message : MarkAIClient.STATE.messages()) {
                    var value = message.getAsJsonObject();
                    String prefix = value.get("role").getAsString().equals("user") ? text("markai.ui.you") : MarkAIClient.STATE.string("name", "Mark");
                    rows += client.font.split(net.minecraft.network.chat.Component.literal(prefix + ": " + value.get("text").getAsString()), panelWidth - 24).size() + 1;
                }
                int visibleRows = Math.max(1, (bottom - top) / 11);
                check(rows > visibleRows, "Fullscreen scroll fixture must actually overflow after text wrapping");
                double x = screen.width / 2.0, y = top + 10;
                screen.mouseScrolled(x, y, 0, -1000);
                check(field(CompanionScreen.class, "conversationScroll").getInt(screen) == 0, "Scrolling to the latest message must reset offset");
                check(screen.mouseScrolled(x, y, 0, 5), "Dialogue must consume scrolling inside its content area");
                int offset = field(CompanionScreen.class, "conversationScroll").getInt(screen);
                check(offset > 0 && offset <= rows - visibleRows, "Mouse wheel must move through genuinely overflowing dialogue");
                checks.add(Map.of("stage", "fullscreen-scroll-" + stage, "wrapped_rows", rows,
                    "visible_rows", visibleRows, "scroll_offset", offset, "real_overflow", true));
            } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        });
        assertLayout(context, checks, "fullscreen-scrolled-dialogue-" + stage);
        context.takeScreenshot("markai-fullscreen-dialogue-scrolled-" + stage);
    }

    private static void closeWithEscape(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
        context.waitForScreen(null);
    }

    private static void restoreWindow(ClientGameTestContext context, WindowState saved) {
        context.runOnClient(client -> {
            var window = client.getWindow();
            window.setFullscreen(saved.fullscreen()); window.updateFullscreenIfChanged();
            var hooks = (WindowHooks) (Object) window;
            hooks.fabric_resize(saved.nativeWidth(), saved.nativeHeight());
            // Preserve the harness's original logical/render dimensions separately from native HiDPI sizes.
            if (saved.nativeWidth() != saved.logicalWidth() || saved.nativeHeight() != saved.logicalHeight())
                hooks.fabric_resize(saved.logicalWidth(), saved.logicalHeight());
            window.setWidth(saved.framebufferWidth()); window.setHeight(saved.framebufferHeight());
            client.options.guiScale().set(saved.guiScale()); client.resizeGui();
            client.gameRenderer.resize(saved.framebufferWidth(), saved.framebufferHeight());
        });
        context.waitFor(client -> client.options.fullscreen().get() == saved.fullscreen()
            && client.getWindow().getWidth() == saved.framebufferWidth() && client.getWindow().getHeight() == saved.framebufferHeight());
        context.waitTicks(2);
    }

    private static void writeWindowChecks(List<Map<String, Object>> checks) {
        try {
            Path report = FabricLoader.getInstance().getGameDir().resolve("fullscreen-ui-validation.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(Map.of("checks", checks)), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new RuntimeException(error); }
    }

    private static EditBox editBox(net.minecraft.client.gui.screens.Screen screen) {
        return screen.children().stream().filter(widget -> widget instanceof EditBox).map(widget -> (EditBox) widget).findFirst().orElseThrow();
    }
    private static void assertDraftSurvivesResize(ClientGameTestContext context, String draft) {
        context.runOnClient(client -> {
            var screen = client.gui.screen();
            int width = screen.width, height = screen.height;
            screen.resize(width - 20, height - 20);
            check(editBox(screen).getValue().equals(draft), "Resizing must preserve unsent text");
            screen.resize(width, height);
            check(editBox(screen).getValue().equals(draft), "Restoring the size must preserve unsent text");
        });
    }
    private static void pace(ClientGameTestContext context) {
        // Test ticks may run faster than the wall-clock packet rate limit.
        long until = System.nanoTime() + 350_000_000L;
        while (System.nanoTime() < until) context.waitTick();
    }
    private static void clickMatching(ClientGameTestContext context, String value, boolean prefix) {
        String label = context.computeOnClient(client -> client.gui.screen().children().stream().filter(widget -> widget instanceof Button)
            .map(widget -> (Button) widget).filter(button -> button.active && (prefix ? button.getMessage().getString().startsWith(value) : button.getMessage().getString().contains(value)))
            .findFirst().orElseThrow().getMessage().getString());
        context.clickScreenButton(label);
    }
    private static String text(String key) { return net.minecraft.network.chat.Component.translatable(key).getString(); }
    private static Wolf wolf(ServerPlayer owner) {
        return owner.level().getEntitiesOfClass(Wolf.class, owner.getBoundingBox().inflate(16)).stream()
            .filter(wolf -> wolf.isAlive() && wolf.isOwnedBy(owner)).findFirst().orElseThrow();
    }
    private static MarkAIMod mod() {
        return (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(value -> value instanceof MarkAIMod).findFirst().orElseThrow();
    }
    private static Field field(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void resetCooldown(MarkAIMod mod, UUID owner) {
        try {
            Object session = ((Map<?, ?>) field(MarkAIMod.class, "aiSessions").get(mod)).get(owner);
            field(session.getClass(), "nextRequest").setLong(session, 0);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
