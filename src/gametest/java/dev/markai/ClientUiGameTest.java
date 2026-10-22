package dev.markai;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.markai.ai.LlmProvider;
import dev.markai.ai.OllamaClient;
import dev.markai.client.ClientCompanionState;
import dev.markai.client.CompanionScreen;
import dev.markai.client.CompanionHubScreen;
import dev.markai.client.MarkAIClient;
import dev.markai.network.CompanionNetwork;
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
        writeHubResult(false,0,0);
        WindowState originalWindow = context.computeOnClient(client -> {
            var window = client.getWindow(); var hooks = (WindowHooks) (Object) window;
            return new WindowState(client.options.fullscreen().get(), client.options.guiScale().get(),
                window.getScreenWidth(), window.getScreenHeight(), window.getWidth(), window.getHeight(),
                hooks.fabric_getRealWidth(), hooks.fabric_getRealHeight());
        });
        var windowChecks = new ArrayList<Map<String, Object>>();
        var pending = new CompletableFuture<OllamaClient.Reply>();
        var calls = new AtomicInteger();
        var groupCalls = new java.util.concurrent.CopyOnWriteArrayList<GroupCall>();
        LlmProvider fake = new LlmProvider() {
            @Override public CompletableFuture<OllamaClient.Reply> ask(String observation, String question, List<OllamaClient.ChatMessage> history) {
                if (question.equals("Что скажешь?")) check(history.size() == 2 && history.getLast().role().equals("assistant"),
                    "The pending question must be sent once as the current request, not duplicated in LLM history");
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
                    var companion = TestCompanions.selected(mod, ownerId);
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
                context.runOnClient(client -> {
                    check(messageCount("user", "Что скажешь?") == 1, "The accepted user message must be visible exactly once while the reply is pending");
                    check(editBox(client.gui.screen()).getValue().isEmpty(), "The accepted question moves from the input into the dialogue immediately");
                });
                assertLayout(context, windowChecks, "fullscreen-dialogue-pending");
                context.takeScreenshot("markai-dialogue-pending");
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
                        var companion = TestCompanions.selected(mod, ownerId);
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
                UUID firstId = context.computeOnClient(client -> UUID.fromString(MarkAIClient.STATE.companionId()));
                var secondId = new AtomicReference<UUID>();
                var lateReply = new CompletableFuture<OllamaClient.Reply>();
                server.runOnServer(instance -> {
                    ServerPlayer owner = instance.getPlayerList().getPlayer(ownerId);
                    instance.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
                    try {
                        secondId.set(TestCompanions.selected(mod, ownerId).id);
                        TestCompanions.registry(mod).select(ownerId, firstId);
                        resetCooldown(mod, ownerId);
                        Object prior = provider.get(mod);
                        provider.set(mod, new LlmProvider() {
                            public CompletableFuture<OllamaClient.Reply> ask(String observation, String question, List<OllamaClient.ChatMessage> history) { return lateReply; }
                            public void close() { lateReply.cancel(true); }
                        });
                        try { instance.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask Проверка ответа после переключения."); }
                        finally { provider.set(mod, prior); }
                    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                pace(context); context.runOnClient(client -> MarkAIClient.request()); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.companions().size() == 2);
                context.getInput().pressKey(KeyMapping.get("key.markai.dialogue")); context.waitForScreen(CompanionScreen.class);
                context.runOnClient(client -> check(messageCount("user", "Проверка ответа после переключения.") == 1,
                    "An in-flight question belongs to the selected companion and is shown before the answer"));
                context.getInput().typeChars("Private unsent first draft");
                pace(context); context.clickScreenButton("›"); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.companionId().equals(secondId.get().toString()));
                context.runOnClient(client -> check(editBox(client.gui.screen()).getValue().isEmpty() && MarkAIClient.STATE.messages().isEmpty(),
                    "Changing companion clears its private draft and shows only its own history"));
                context.getInput().typeChars("Second private draft");
                context.runOnClient(client -> {
                    check(!MarkAIClient.STATE.bool("can_chat", true), "The global generation limit is reflected in the newly selected companion's Send state");
                    client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                    check(editBox(client.gui.screen()).getValue().equals("Second private draft"), "A blocked request cannot clear the next companion's unsent draft");
                });
                server.runOnServer(instance -> lateReply.complete(new OllamaClient.Reply("say", "Late answer for first companion.")));
                context.waitTicks(8);
                context.runOnClient(client -> check(MarkAIClient.STATE.messages().isEmpty() && !MarkAIClient.STATE.notice().contains("Late answer for first"),
                    "An old pending answer must not appear in a different companion's history or status"));
                context.clickScreenButton("markai.tab.profile");
                context.runOnClient(client -> { client.options.guiScale().set(1); client.resizeGui(); }); context.waitTicks(2);
                assertLayout(context, windowChecks, "two-companion-carousel-scale1"); context.takeScreenshot("markai-two-companion-carousel");
                restoreWindow(context, originalWindow);
                context.clickScreenButton("markai.tab.dialogue");
                pace(context); context.clickScreenButton("‹"); connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.companionId().equals(firstId.toString())
                    && MarkAIClient.STATE.messages().toString().contains("Late answer for first"));
                context.runOnClient(client -> {
                    check(editBox(client.gui.screen()).getValue().isEmpty(), "Returning cannot leak the second companion's draft");
                    check(messageCount("user", "Проверка ответа после переключения.") == 1
                            && messageCount("assistant", "Late answer for first companion.") == 1,
                        "The completed exchange replaces the in-flight display without duplicating either message");
                });
                closeWithEscape(context);
                // Send the same typed group commands as the Ctrl+hotkeys. The client test input
                // helper can synthesize key presses, but modifier state varies across SDL builds.
                pace(context); context.runOnClient(client -> MarkAIClient.groupAction("stay"));
                connection.waitForServerboundPackets();
                server.waitFor(instance -> allFollowing(mod, ownerId, false));
                context.waitFor(client -> MarkAIClient.STATE.popup().contains("Всем: ждать"));
                context.runOnClient(client -> check(MarkAIClient.STATE.notice().isBlank(),
                    "A group command popup does not leave a duplicate persistent HUD line"));
                context.takeScreenshot("markai-group-command-popup-stay");
                pace(context); context.runOnClient(client -> MarkAIClient.groupAction("follow"));
                connection.waitForServerboundPackets();
                server.waitFor(instance -> allFollowing(mod, ownerId, true));
                // Exercise selection cycling directly: the client GameTest input helper does not
                // expose modifier state to Minecraft's hasShiftDown() check.
                pace(context); context.runOnClient(client -> MarkAIClient.cycleCompanion(1));
                connection.waitForServerboundPackets();
                context.waitFor(client -> client.gui.screen() == null && MarkAIClient.STATE.companionId().equals(secondId.get().toString()));
                context.getInput().pressKey(KeyMapping.get("key.markai.quick"));
                context.waitForScreen(CompanionScreen.class);
                closeWithEscape(context);

                // Shared hub: real controls, owner packets and a finite sequence of controlled model replies.
                var privateHistory = new AtomicReference<Map<UUID,List<OllamaClient.ChatMessage>>>();
                server.runOnServer(instance -> {
                    try {
                        var owned=TestCompanions.registry(mod).owned(ownerId);
                        owned.get(0).living.name="Рекс";owned.get(1).living.name="Луна";
                        for(var member:owned) { member.living.settings.autochat=false;member.living.settings.defense=true; }
                        ((CompanionNetwork.Backend)field(CompanionNetwork.class,"backend").get(null)).setting(instance.getPlayerList().getPlayer(ownerId),"mutual_replies","false");
                        privateHistory.set(owned.stream().collect(java.util.stream.Collectors.toMap(c->c.id,c->c.memory.history())));
                        provider.set(mod,new LlmProvider() {
                            public CompletableFuture<OllamaClient.Reply> ask(String observation,String question,List<OllamaClient.ChatMessage> history) {
                                var reply=new CompletableFuture<OllamaClient.Reply>();groupCalls.add(new GroupCall(observation,question,List.copyOf(history),reply));return reply;
                            }
                            public void close() { groupCalls.forEach(call->call.reply().cancel(true)); }
                        });
                    } catch(ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                pace(context);context.getInput().pressKey(KeyMapping.get("key.markai.group_chat"));context.waitForScreen(CompanionHubScreen.class);
                context.runOnClient(client->check(editBox(client.gui.screen())!=null,"Y opens the shared chat directly"));
                closeWithEscape(context);
                pace(context);context.getInput().pressKey(KeyMapping.get("key.markai.hub"));context.waitForScreen(CompanionHubScreen.class);
                context.waitFor(client->MarkAIClient.STATE.companions().size()==2 && MarkAIClient.STATE.companions().toString().contains("Луна"));
                assertLayout(context,windowChecks,"hub-roster-window");context.takeScreenshot("markai-hub-roster-window");
                pace(context);context.clickScreenButton("markai.tab.profile");connection.waitForServerboundPackets();context.waitForScreen(CompanionScreen.class);
                context.waitFor(client->MarkAIClient.STATE.companionId().equals(firstId.toString()));
                context.clickScreenButton("markai.hub.open_short");context.waitForScreen(CompanionHubScreen.class);
                pace(context);context.runOnClient(client->MarkAIClient.select(secondId.get().toString()));connection.waitForServerboundPackets();
                context.waitFor(client->MarkAIClient.STATE.companionId().equals(secondId.get().toString()));
                context.clickScreenButton("markai.hub.tab.chat");context.getInput().typeChars("Всем привет, как настроение?");
                enterFullscreen(context,"Всем привет, как настроение?");
                for(int scale:List.of(2,4)) {
                    setGuiScale(context,scale);
                    for(String destination:List.of("companions","group_commands","settings","chat")) {
                        context.clickScreenButton("markai.hub.tab."+destination);context.waitTicks(1);
                        assertLayout(context,windowChecks,"hub-fullscreen-scale"+scale+"-"+destination);
                        context.takeScreenshot("markai-hub-"+destination+"-scale"+scale);
                    }
                }
                context.runOnClient(client->check(editBox(client.gui.screen()).getValue().equals("Всем привет, как настроение?"),"Shared draft survives tabs and both fullscreen scales"));
                context.clickScreenButton("markai.hub.tab.settings");
                pace(context);clickMatching(context,text("markai.hub.setting.all_defense"),true);connection.waitForServerboundPackets();
                server.waitFor(instance->{ try{return TestCompanions.registry(mod).owned(ownerId).stream().allMatch(c->!c.living.settings.defense);}catch(ReflectiveOperationException error){throw new RuntimeException(error);} });
                context.clickScreenButton("markai.hub.tab.chat");
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",false));
                pace(context);context.clickScreenButton("markai.ui.send");connection.waitForServerboundPackets();
                context.waitFor(client->groupCalls.size()==1 && MarkAIClient.STATE.groupMessages().toString().contains("Всем привет, как настроение?"));
                context.takeScreenshot("markai-group-chat-pending");
                context.getInput().typeChars("Следующий общий вопрос");
                context.runOnClient(client->{client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));check(editBox(client.gui.screen()).getValue().equals("Следующий общий вопрос"),"Busy group chat preserves the next draft");});
                check(groupSpeaker(groupCalls.get(0)).equals("Рекс"),"Shared chat starts with the roster, not the selected Luna");
                server.runOnServer(instance->groupCalls.get(0).reply().complete(new OllamaClient.Reply("follow","Луна, я рядом.")));
                context.waitFor(client->groupCalls.size()==2);
                check(groupSpeaker(groupCalls.get(1)).equals("Луна") && groupCalls.get(1).history().toString().contains("Рекс: Луна, я рядом."),"The other companion sees the labelled shared reply");
                server.runOnServer(instance->groupCalls.get(1).reply().complete(new OllamaClient.Reply("stay","Рекс, слышу тебя.")));
                context.waitFor(client->!ClientCompanionState.bool(MarkAIClient.STATE.group(),"thinking",true)&&MarkAIClient.STATE.groupMessages().size()==3);
                context.waitTicks(25);check(groupCalls.size()==2,"Names in model replies cannot trigger a new round");
                server.runOnServer(instance->{try {var owned=TestCompanions.registry(mod).owned(ownerId);
                    check(owned.stream().allMatch(c->c.memory.history().equals(privateHistory.get().get(c.id))),"Shared dialogue does not leak into personal history");
                    check(owned.stream().allMatch(c->c.following),"Group speech cannot apply proposed follow/stay actions");
                    check(TestCompanions.selected(mod,ownerId).id.equals(secondId.get()),"Shared chat never changes selection");
                }catch(ReflectiveOperationException error){throw new RuntimeException(error);}});
                context.takeScreenshot("markai-group-chat-complete");
                context.clickScreenButton("markai.hub.tab.settings");
                pace(context);clickMatching(context,text("markai.hub.setting.mutual_replies"),true);connection.waitForServerboundPackets();
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"mutual_replies",false));
                context.clickScreenButton("markai.hub.tab.chat");
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",false));
                context.runOnClient(client->editBox(client.gui.screen()).setValue("Рекс, как тебе прогулка?"));
                pace(context);context.clickScreenButton("markai.ui.send");
                connection.waitForServerboundPackets();context.waitFor(client->groupCalls.size()==3);
                check(groupSpeaker(groupCalls.get(2)).equals("Рекс"),"Shared chat routes an actual name to Rex while Luna stays selected");
                server.runOnServer(instance->groupCalls.get(2).reply().complete(new OllamaClient.Reply("say","Луна, как тебе дорожка?")));
                context.waitFor(client->groupCalls.size()==4);
                var peerContext=com.google.gson.JsonParser.parseString(groupCalls.get(3).observation()).getAsJsonObject();
                check(peerContext.get("reply_to").getAsString().equals("Рекс")&&peerContext.get("group_topic").getAsString().equals("Рекс, как тебе прогулка?")
                    &&groupCalls.get(3).question().contains("Рекс: Луна, как тебе дорожка?"),"Peer replies preserve the named speaker and the player's original topic");
                server.runOnServer(instance->groupCalls.get(3).reply().complete(new OllamaClient.Reply("say","Рекс, отличная дорога, ты доволен?")));
                context.waitFor(client->groupCalls.size()==5);
                server.runOnServer(instance->groupCalls.get(4).reply().complete(new OllamaClient.Reply("say","Луна, всё хорошо, побуду рядом.")));
                context.waitFor(client->!ClientCompanionState.bool(MarkAIClient.STATE.group(),"thinking",true));context.waitTicks(25);
                check(groupCalls.size()==5,"A conversation ends naturally when no companion asks another named question");
                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.markai.dialogue"));context.waitForScreen(CompanionScreen.class);
                context.waitFor(client->MarkAIClient.STATE.bool("can_chat",false));
                context.getInput().typeChars("Рекс, что думаешь?");pace(context);context.clickScreenButton("markai.ui.send");connection.waitForServerboundPackets();
                context.waitFor(client->groupCalls.size()==6);
                check(groupSpeaker(groupCalls.get(5)).equals("Луна") && !groupCalls.get(5).observation().contains("\"conversation_mode\":\"group\""),"A name in private chat never redirects to a different companion or channel");
                server.runOnServer(instance->groupCalls.get(5).reply().complete(new OllamaClient.Reply("say","Я слушаю, хозяин.")));
                context.waitFor(client->MarkAIClient.STATE.messages().toString().contains("Я слушаю, хозяин."));
                context.runOnClient(client->check(!MarkAIClient.STATE.groupMessages().toString().contains("Рекс, что думаешь?"),"Private text never enters shared history"));
                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.markai.hub"));context.waitForScreen(CompanionHubScreen.class);
                context.clickScreenButton("markai.hub.tab.chat");context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",false));
                context.getInput().typeChars("Обсудим ещё что-нибудь.");pace(context);context.clickScreenButton("markai.ui.send");connection.waitForServerboundPackets();
                context.waitFor(client->groupCalls.size()==7 && ClientCompanionState.bool(MarkAIClient.STATE.group(),"thinking",false));
                pace(context);context.clickScreenButton("markai.ui.cancel");connection.waitForServerboundPackets();
                context.waitFor(client->groupCalls.get(6).reply().isCancelled()&&!ClientCompanionState.bool(MarkAIClient.STATE.group(),"thinking",true));
                context.waitTicks(15);check(groupCalls.size()==7,"Cancel stops the provider and the remaining shared queue");
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",false));
                context.getInput().typeChars("Поговорите о прогулке.");pace(context);context.clickScreenButton("markai.ui.send");connection.waitForServerboundPackets();
                for(int i=0;i<10;i++) {
                    int index=7+i;context.waitFor(client->groupCalls.size()==index+1);
                    String recipient=groupSpeaker(groupCalls.get(index)).equals("Рекс")?"Луна":"Рекс";
                    int sequence=i;
                    server.runOnServer(instance->groupCalls.get(index).reply().complete(new OllamaClient.Reply("say",recipient+", вопрос "+sequence+": как твои дела?")));
                }
                context.waitFor(client->!ClientCompanionState.bool(MarkAIClient.STATE.group(),"thinking",true));context.waitTicks(30);
                check(groupCalls.size()==17,"Even an endless sequence of named questions stops at ten companion messages total");
                context.runOnClient(client->{
                    var messages=MarkAIClient.STATE.groupMessages();String lastRound=messages.get(messages.size()-1).getAsJsonObject().get("roundId").getAsString();
                    int replies=0;for(var entry:messages) if(entry.getAsJsonObject().get("roundId").getAsString().equals(lastRound)
                        &&ClientCompanionState.string(entry.getAsJsonObject(),"role","").equals("assistant")) replies++;
                    check(replies==10&&ClientCompanionState.string(MarkAIClient.STATE.group(),"status","").contains("10"),"The shared transcript contains exactly ten companion replies and reports its limit");
                });
                context.takeScreenshot("markai-group-chat-ten-message-limit");
                context.clickScreenButton("markai.hub.tab.settings");
                pace(context);clickMatching(context,text("markai.hub.setting.group_enabled")+": ",true);connection.waitForServerboundPackets();
                context.waitFor(client->!ClientCompanionState.bool(MarkAIClient.STATE.group(),"enabled",true));
                context.runOnClient(client->check(!ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",true),"The shared chat preference is a real on/off control, not a mixed companion setting"));
                pace(context);clickMatching(context,text("markai.hub.setting.group_enabled")+": ",true);connection.waitForServerboundPackets();
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"enabled",false));
                context.waitFor(client->ClientCompanionState.bool(MarkAIClient.STATE.group(),"can_chat",false));
                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.chat"));context.waitForScreen(net.minecraft.client.gui.screens.ChatScreen.class);
                context.getInput().typeChars("Рекс, ответишь?");context.runOnClient(client->client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0)));
                connection.waitForServerboundPackets();context.waitTicks(25);
                check(groupCalls.size()==17,"Vanilla Minecraft chat cannot activate companion names outside the shared tab");
                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.markai.hub"));context.waitForScreen(CompanionHubScreen.class);
                context.waitFor(client->MarkAIClient.STATE.companions().size()==2);
                context.takeScreenshot("markai-hub-before-remove");
                context.clickScreenButton(text("markai.hub.remove"));context.waitTicks(2);
                context.takeScreenshot("markai-hub-remove-confirm");
                context.clickScreenButton(text("markai.hub.remove_confirm"));connection.waitForServerboundPackets();
                server.waitFor(instance->{try{return TestCompanions.registry(mod).owned(ownerId).size()==1;}catch(ReflectiveOperationException error){throw new RuntimeException(error);}});
                context.waitFor(client->MarkAIClient.STATE.companions().size()==1);
                context.waitTicks(2);
                context.runOnClient(client->check(MarkAIClient.STATE.companionId().equals(secondId.get().toString()),
                    "Removing one roster entry keeps the remaining companion selected"));
                context.runOnClient(client->check(client.gui.screen().children().stream().filter(widget->widget instanceof Button)
                    .map(widget->((Button)widget).getMessage().getString()).noneMatch(label->label.equals(text("markai.hub.removing"))),
                    "A completed removal leaves no stuck pending button in the roster"));
                context.takeScreenshot("markai-hub-after-remove");

                context.runOnClient(client -> check(KeyMapping.get("key.markai.sethome") != null,
                    "Set-home must be registered as a configurable Minecraft key mapping"));
                server.runOnServer(instance -> {
                    ServerPlayer owner = instance.getPlayerList().getPlayer(ownerId);
                    instance.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn chicken");
                    instance.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ai off");
                });
                context.waitFor(client -> MarkAIClient.STATE.companions().size() == 2
                    && MarkAIClient.STATE.string("species", "").equals("minecraft:chicken"));
                context.takeScreenshot("markai-hub-with-chicken");

                closeWithEscape(context);
                context.getInput().pressKey(KeyMapping.get("key.markai.open"));
                context.waitForScreen(CompanionScreen.class);
                context.waitFor(client -> MarkAIClient.STATE.string("species", "").equals("minecraft:chicken"));
                context.runOnClient(client -> {
                    List<String> labels = client.gui.screen().children().stream().filter(widget -> widget instanceof Button)
                        .map(widget -> ((Button) widget).getMessage().getString()).toList();
                    check(!labels.contains(text("markai.command.tame")),
                        "Chicken profile must not offer the wolf tame action");
                    check(!labels.contains(text("markai.command.spawn")),
                        "A living chicken profile must not offer the wolf spawn action");
                    check(labels.contains(text("markai.command.spawn_chicken")),
                        "The profile must offer a species-correct way to create a chicken companion");
                });
                context.takeScreenshot("markai-chicken-profile");
                pace(context);
                context.clickScreenButton(text("markai.command.spawn_chicken"));
                connection.waitForServerboundPackets();
                context.waitFor(client -> MarkAIClient.STATE.companions().size() == 3
                    && MarkAIClient.STATE.string("species", "").equals("minecraft:chicken"));
                context.takeScreenshot("markai-after-create-chicken");
                context.clickScreenButton("markai.tab.commands");
                context.runOnClient(client -> {
                    List<String> labels = client.gui.screen().children().stream().filter(widget -> widget instanceof Button)
                        .map(widget -> ((Button) widget).getMessage().getString()).toList();
                    for (String action : List.of("follow", "come", "stay", "rest", "stop", "summon", "dismiss"))
                        check(labels.contains(text("markai.command." + action)), "Chicken command tab must include " + action);
                    for (String action : List.of("attack", "collect", "inventory", "wander", "home", "sethome", "investigate"))
                        check(!labels.contains(text("markai.command." + action)), "Chicken command tab must exclude wolf-only " + action);
                });
                context.takeScreenshot("markai-chicken-commands");

                context.clickScreenButton("markai.hub.open_short");
                context.waitForScreen(CompanionHubScreen.class);
                context.clickScreenButton("markai.hub.tab.group_commands");
                context.runOnClient(client -> {
                    List<String> labels = client.gui.screen().children().stream().filter(widget -> widget instanceof Button)
                        .map(widget -> ((Button) widget).getMessage().getString()).toList();
                    check(labels.contains(text("markai.command.group_sethome")),
                        "The shared commands tab must expose Set Home for groups containing wolves");
                });
                pace(context);
                context.clickScreenButton(text("markai.command.group_sethome"));
                connection.waitForServerboundPackets();
                server.waitFor(instance -> {
                    try {
                        return TestCompanions.registry(mod).owned(ownerId).stream()
                            .filter(c -> !c.species.equals("minecraft:chicken"))
                            .anyMatch(c -> c.living.home != null);
                    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                });
                context.takeScreenshot("markai-group-sethome-with-chicken");
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
        writeHubResult(true,groupCalls.size(),windowChecks.size());
    }

    private record WindowState(boolean fullscreen, int guiScale, int logicalWidth, int logicalHeight,
                               int framebufferWidth, int framebufferHeight, int nativeWidth, int nativeHeight) {}
    private record GroupCall(String observation,String question,List<OllamaClient.ChatMessage> history,CompletableFuture<OllamaClient.Reply> reply) {}
    private static String groupSpeaker(GroupCall call) { return com.google.gson.JsonParser.parseString(call.observation()).getAsJsonObject().getAsJsonObject("companion").get("name").getAsString(); }
    private static void writeHubResult(boolean completed,int calls,int layouts) {
        try { Files.writeString(FabricLoader.getInstance().getGameDir().resolve("group-hub-client-result.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("completed",completed,"controlled_provider_calls",calls,"layout_checks",layouts,"max_companion_replies",10)),StandardCharsets.UTF_8);
        } catch(java.io.IOException error) { throw new RuntimeException(error); }
    }

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
            check(screen instanceof CompanionScreen || screen instanceof CompanionHubScreen, "Companion screen expected at " + stage);
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
        context.runOnClient(client -> {
            if (client.gui.screen() != null) client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        });
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
    private static long messageCount(String role, String text) {
        long count = 0;
        for (var element : MarkAIClient.STATE.messages()) if (element.isJsonObject()) {
            var message = element.getAsJsonObject();
            if (ClientCompanionState.string(message, "role", "").equals(role)
                    && ClientCompanionState.string(message, "text", "").equals(text)) count++;
        }
        return count;
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
    private static boolean allFollowing(MarkAIMod mod, UUID owner, boolean following) {
        try { return TestCompanions.registry(mod).owned(owner).stream().allMatch(c -> c.following == following); }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static Field field(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void resetCooldown(MarkAIMod mod, UUID owner) {
        try {
            MarkAIMod.Companion selected = TestCompanions.selected(mod, owner);
            if (selected == null) throw new IllegalStateException("Owner has no selected companion");
            Object session = ((Map<?, ?>) field(MarkAIMod.class, "aiSessions").get(mod)).get(selected.id);
            if (session == null) throw new IllegalStateException("Selected companion has no AI session: " + selected.id);
            field(session.getClass(), "nextRequest").setLong(session, 0);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
