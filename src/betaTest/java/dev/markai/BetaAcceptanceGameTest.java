package dev.markai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.ai.AIConfig;
import dev.markai.ai.LlmProvider;
import dev.markai.ai.OllamaClient;
import dev.markai.companion.CompanionRegistry;
import dev.markai.companion.CompanionStore;
import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.network.CompanionNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in real server acceptance; synthetic players/provider, no user world or model required. */
public final class BetaAcceptanceGameTest {
    @GameTest(structure = "markai-beta-test:arena", maxTicks = 1_000_000)
    public void twoOwnersSixteenCompanionsAndBoundedConversations(GameTestHelper helper) throws Exception {
        Session session = new Session(helper);
        var info = GameTestHelper.class.getDeclaredField("testInfo");
        info.setAccessible(true);
        ((GameTestInfo) info.get(helper)).addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo test) {}
            @Override public void testPassed(GameTestInfo test, GameTestRunner runner) { session.close(); }
            @Override public void testFailed(GameTestInfo test, GameTestRunner runner) { session.close(); }
            @Override public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) { session.close(); }
        });
        helper.startSequence().thenWaitUntil(() -> {
            session.tick();
            helper.assertTrue(session.done, "Beta acceptance/soak is still running: phase " + session.phase);
        }).thenExecute(session::report).thenSucceed();
    }

    private static final class Session {
        final GameTestHelper helper;
        final MarkAIMod mod;
        final CompanionNetwork.Backend backend;
        final CompanionRegistry<MarkAIMod.Companion> registry;
        final ServerPlayer[] owners = new ServerPlayer[2];
        final List<List<MarkAIMod.Companion>> rosters = new ArrayList<>();
        final List<UUID> selected = new ArrayList<>();
        final ControlledProvider provider = new ControlledProvider();
        final LlmProvider previous;
        final AIConfig config;
        final boolean originalMemory;
        final int seconds = Integer.getInteger("markai.beta.soakSeconds", 120);
        int phase, activeOwner, rounds, checks, samples, maxHistory, maxSnapshot;
        long soakStarted, nextSample, tickCount, maxHeap;
        UUID roundId;
        Call late;
        boolean done, closed;

        @SuppressWarnings("unchecked") Session(GameTestHelper helper) throws Exception {
            this.helper = helper;
            if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("betaSoakSeconds must be 5..3600");
            mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
            registry = (CompanionRegistry<MarkAIMod.Companion>) field(mod, "companions");
            backend = (CompanionNetwork.Backend) field(null, CompanionNetwork.class, "backend");
            config = (AIConfig) field(mod, "aiConfig"); originalMemory = config.memoryEnabled;
            previous = (LlmProvider) field(mod, "ollama"); set(mod, "ollama", provider);
            for (int x=0;x<32;x++) for (int z=0;z<32;z++) helper.setBlock(new BlockPos(x,1,z), Blocks.STONE);
            for (int owner=0;owner<2;owner++) {
                ServerPlayer player = helper.makeMockServerPlayerInLevel();
                player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                Vec3 pos = helper.absoluteVec(new Vec3(5 + owner*18,2,8)); player.setPos(pos.x,pos.y,pos.z);
                // Mock players have real level bodies; explicitly register their online UUID for delivery checks.
                var players = helper.getLevel().getServer().getPlayerList();
                players.getPlayersByUUID().put(player.getUUID(), player);
                if (!players.getPlayers().contains(player)) players.getPlayers().add(player);
                owners[owner] = player;
                for (int pet=0;pet<8;pet++) {
                    command(player,"mark spawn");
                    var c = registry.selected(player.getUUID());
                    c.living.name = prefix(owner)+pet;
                    c.living.settings.autochat = false;
                    c.living.settings.defense = false;
                    c.memory.rememberStatement("Запомни private_fixture_"+owner, helper.getLevel().getGameTime());
                    Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
                    ((CompanionInventoryHolder)wolf).markai$inventory().setItem(0,new ItemStack(Items.BEEF,pet+1));
                }
                command(player,"mark stay"); backend.command(player,"all:stay");
                rosters.add(registry.owned(player.getUUID())); selected.add(registry.selected(player.getUUID()).id);
            }
            check(registry.values().size()==16,"Both owners reach the independent eight-companion limit: total="+registry.values().size()
                +", roster="+registry.owned(owners[0].getUUID()).size()+"/"+registry.owned(owners[1].getUUID()).size()
                +", owners="+owners[0].getUUID()+"/"+owners[1].getUUID());
        }
        static String prefix(int owner) { return owner==0 ? "Альфа" : "Бета"; }
        void check(boolean condition, String text) { checks++; if (!condition) throw new IllegalStateException(text); }
        JsonObject view(int owner) { return JsonParser.parseString(backend.snapshot(owners[owner])).getAsJsonObject(); }
        boolean busy(int owner) { return view(owner).getAsJsonObject("group").get("thinking").getAsBoolean(); }
        @SuppressWarnings("unchecked") void resetCooldown(int owner) {
            ((Map<UUID,Long>)field(mod,"groupNextRequest")).put(owners[owner].getUUID(),0L);
        }
        void start(int owner, boolean reset) {
            activeOwner=owner; if(reset) resetCooldown(owner);
            backend.groupChat(owners[owner],prefix(owner)+"0, group_fixture_"+owner+": как настроение?");
            check(busy(owner),"Accepted round must enter pending state");
            var messages=view(owner).getAsJsonArray("group_messages");
            roundId=UUID.fromString(messages.get(messages.size()-1).getAsJsonObject().get("roundId").getAsString());
        }
        void finishRound() {
            var messages=view(activeOwner).getAsJsonArray("group_messages");
            long replies=messages.asList().stream().map(e->e.getAsJsonObject()).filter(e->e.get("roundId").getAsString().equals(roundId.toString())
                && e.get("role").getAsString().equals("assistant")).count();
            check(replies==10,"The whole round must stop at exactly ten replies, even if every model reply asks a question");
            rounds++;
        }
        void pump() {
            Call call=provider.pending.pollFirst(); if(call==null) return;
            JsonObject context=JsonParser.parseString(call.context).getAsJsonObject();
            String name=context.getAsJsonObject("companion").get("name").getAsString();
            int owner=name.startsWith(prefix(0))?0:1;
            check(!context.has("memory") && !call.context.contains("private_fixture_"),"Private memories never enter group context");
            check(call.history.stream().noneMatch(m->m.content().contains("private_fixture_") || m.content().contains("group_fixture_"+(1-owner))),"Owner histories remain isolated");
            check(context.getAsJsonArray("group_members").asList().stream().allMatch(m->m.getAsJsonObject().get("name").getAsString().startsWith(prefix(owner))),"Group member list contains only the caller's pets");
            String next=prefix(owner)+(name.equals(prefix(owner)+"0")?"1":"0");
            call.future.complete(new OllamaClient.Reply("attack",next+", что думаешь о нашей прогулке?"));
        }
        void tick() {
            tickCount++;
            switch(phase) {
                case 0 -> {
                    start(0,true);
                    int foreignSize=view(1).getAsJsonArray("group_messages").size();
                    backend.groupChat(owners[1],prefix(1)+"0, group_fixture_waiting");
                    backend.command(owners[1],"group_cancel");
                    check(busy(0) && view(1).getAsJsonArray("group_messages").size()==foreignSize,"A second owner cannot cancel another round or append to a globally busy conversation");
                    backend.select(owners[1],rosters.get(0).getFirst().id.toString());
                    check(registry.selected(owners[1].getUUID()).id.equals(selected.get(1)),"Foreign selection is rejected");
                    phase=1;
                }
                case 1,2 -> {
                    pump(); if(busy(activeOwner)) return; finishRound();
                    if(phase==1) { start(1,true); phase=2; }
                    else { start(0,true); late=provider.pending.removeFirst(); backend.command(owners[0],"group_cancel");
                        late.future.complete(new OllamaClient.Reply("say","late_fixture_must_never_arrive")); phase=3; }
                }
                case 3 -> {
                    check(!busy(0) && !view(0).toString().contains("late_fixture_must_never_arrive"),"Late uncooperative completion cannot revive a cancelled round");
                    start(1,true); provider.pending.removeFirst().future.completeExceptionally(new java.io.IOException("Synthetic provider outage")); phase=4;
                }
                case 4 -> {
                    if(busy(1)) return;
                    config.memoryEnabled=false; start(0,true); phase=5;
                }
                case 5 -> {
                    pump(); if(busy(0)) return; finishRound();
                    var saved=read();
                    check(saved.groups().values().stream().allMatch(g->g.messages().isEmpty()),"Memory disabled excludes all shared histories from the actual world save");
                    config.memoryEnabled=originalMemory;
                    backend.setting(owners[0],"all:follow_distance","7");
                    check(rosters.get(0).stream().allMatch(c->c.living.settings.followDistance==7)
                        && rosters.get(1).stream().allMatch(c->c.living.settings.followDistance!=7),"Bulk settings remain isolated at the maximum roster size");
                    provider.roundRequests=0;
                    soakStarted=System.nanoTime(); nextSample=soakStarted; phase=6;
                }
                case 6 -> {
                    pump();
                    long now=System.nanoTime();
                    if(now>=nextSample) { sample(); nextSample=now+TimeUnit.SECONDS.toNanos(1); }
                    if(!busy(activeOwner)) {
                        if(provider.roundRequests>0) { finishRound(); provider.roundRequests=0; }
                        if(now-soakStarted>=TimeUnit.SECONDS.toNanos(seconds)) { sample(); done=true; return; }
                        int owner=1-activeOwner;
                        if(view(owner).getAsJsonObject("group").get("can_chat").getAsBoolean()) start(owner,false);
                    }
                }
                default -> throw new IllegalStateException("Unknown beta acceptance phase");
            }
        }
        void sample() {
            samples++;
            maxHeap=Math.max(maxHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            check(registry.values().size()==16,"Companions must not duplicate or disappear during the soak");
            for(int owner=0;owner<2;owner++) {
                String snapshot=backend.snapshot(owners[owner]); var parsed=JsonParser.parseString(snapshot).getAsJsonObject();
                int size=parsed.getAsJsonArray("group_messages").size(); maxHistory=Math.max(maxHistory,size);maxSnapshot=Math.max(maxSnapshot,snapshot.length());
                check(size<=24 && snapshot.length()<=CompanionNetwork.MAX_SNAPSHOT,"History and network snapshots remain bounded");
                check(!snapshot.contains("group_fixture_"+(1-owner)),"Shared transcript cannot leak across owners");
                check(registry.selected(owners[owner].getUUID()).id.equals(selected.get(owner)),"Dialogue never switches a player's selection");
                for(int pet=0;pet<8;pet++) {
                    var c=rosters.get(owner).get(pet); var wolf=(Wolf)helper.getLevel().getEntity(c.entity);
                    check(wolf!=null && wolf.isAlive() && wolf.isOwnedBy(owners[owner]) && !c.workActive,"AI attack proposals cannot start world actions");
                    check(((CompanionInventoryHolder)wolf).markai$inventory().getItem(0).getCount()==pet+1,"Inventories retain their original item counts");
                }
            }
            if(samples%10==0) {
                var restored=new CompanionRegistry<MarkAIMod.Companion>(c->c.id,c->c.owner,c->c.entity);
                var saved=read(); saved.companions().forEach(restored::add); restored.restoreSelections(saved.selected());
                check(restored.values().size()==16 && saved.groups().size()==2,"The actual saved roster and owner group data load into a fresh registry");
                for(int i=0;i<2;i++) check(restored.selected(owners[i].getUUID()).id.equals(selected.get(i)),"Fresh registry restores each owner's selected identity");
            }
        }
        CompanionStore.Snapshot<MarkAIMod.Companion> read() {
            try { return new CompanionStore<>(MarkAIMod.Companion.class).read(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("markai.json")); }
            catch(Exception error) { throw new RuntimeException(error); }
        }
        void report() {
            try {
                var result=new LinkedHashMap<String,Object>();
                result.put("completed",true);result.put("owners",2);result.put("companions",16);result.put("checks",checks);
                result.put("soak_seconds",TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-soakStarted));result.put("server_ticks",tickCount);
                result.put("rounds",rounds);result.put("provider_calls",provider.requests);result.put("max_history",maxHistory);
                result.put("max_snapshot_chars",maxSnapshot);result.put("max_heap_bytes",maxHeap);
                result.put("real_players",false);result.put("provider","controlled synthetic");
                Files.writeString(FabricLoader.getInstance().getGameDir().resolve("beta-acceptance-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
            } catch(Exception error) { throw new RuntimeException(error); }
        }
        void close() {
            if(closed)return;closed=true;config.memoryEnabled=originalMemory;
            for(var owner:owners) if(owner!=null) {
                backend.command(owner,"group_cancel");
                var players=helper.getLevel().getServer().getPlayerList();
                players.getPlayersByUUID().remove(owner.getUUID(),owner);players.getPlayers().remove(owner);
            }
            provider.close();set(mod,"ollama",previous);
        }
    }
    private record Call(String context,List<OllamaClient.ChatMessage> history,CompletableFuture<OllamaClient.Reply> future) {}
    private static final class ControlledProvider implements LlmProvider {
        final ArrayDeque<Call> pending=new ArrayDeque<>();int requests,roundRequests;
        @Override public CompletableFuture<OllamaClient.Reply> ask(String context,String question,List<OllamaClient.ChatMessage> history) {
            requests++;roundRequests++;
            var future=new CompletableFuture<OllamaClient.Reply>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            pending.addLast(new Call(context,List.copyOf(history),future));return future;
        }
        @Override public void close() { pending.forEach(c->c.future.completeExceptionally(new IllegalStateException("Test closed")));pending.clear(); }
    }
    private static void command(ServerPlayer owner,String text) { owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(),text); }
    private static Object field(Object target,String name) { return field(target,target.getClass(),name); }
    private static Object field(Object target,Class<?> type,String name) {
        try { var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target); }
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
    private static void set(Object target,String name,Object value) {
        try { var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
}
