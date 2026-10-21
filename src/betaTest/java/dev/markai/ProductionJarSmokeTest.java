package dev.markai;

import com.google.gson.GsonBuilder;
import dev.markai.companion.CompanionStore;
import dev.markai.companion.GroupChatState;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Launches the packaged JAR with Fabric in production mode, never against the user's game directory. */
public final class ProductionJarSmokeTest {
    public static void main(String[] args) throws Exception {
        Path build=Path.of(args[0]), jar=Path.of(args[2]);String expectedVersion=args[3],fabricMods=args[4];
        Path profile=Files.createTempDirectory(build,"production-smoke-");
        var json=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(build.resolve("production-smoke-result.json"),"{\"completed\":false}");
        Files.writeString(profile.resolve("eula.txt"),"eula=true\n");
        Files.writeString(profile.resolve("server.properties"),"server-ip=127.0.0.1\nserver-port="+port()
            +"\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nenable-rcon=false\nenable-query=false\nmax-players=2\n");
        boot(profile,args[1],jar,expectedVersion,fabricMods,"01-fresh");
        check(Files.exists(profile.resolve("config/markai.json")),"Fresh production boot must generate its default config");
        Path state=profile.resolve("world/markai.json");
        check(new CompanionStore<>(MarkAIMod.Companion.class).read(state).companions().isEmpty(),"Fresh profile has no fabricated companion");

        List<MarkAIMod.Companion> legacyRecords=new ArrayList<>();
        for(int i=0;i<2;i++) {
            var c=new MarkAIMod.Companion();c.owner=UUID.randomUUID();c.entity=UUID.randomUUID();c.dimension="minecraft:overworld";
            c.species=null;c.living.name=i==0?"Walk":"Марк";
            c.memory.rememberStatement("Запомни migration_fixture_"+i,0);c.memory.flushPending();legacyRecords.add(c);
        }
        byte[] legacy=(json.toJson(legacyRecords)+"\r\n").getBytes(StandardCharsets.UTF_8);Files.write(state,legacy);
        boot(profile,args[1],jar,expectedVersion,fabricMods,"02-legacy-upgrade");
        var store=new CompanionStore<>(MarkAIMod.Companion.class);var migrated=store.read(state);
        check(!migrated.legacy() && migrated.companions().size()==2,"Real server boot/stop migrates the legacy array");
        for(int i=0;i<2;i++) {
            var expected=legacyRecords.get(i);var actual=migrated.companions().get(i);
            check(actual.id.equals(expected.entity) && actual.owner.equals(expected.owner) && actual.entity.equals(expected.entity),"Migration preserves owner/body and derives stable logical identity");
            check(actual.living.name.equals(expected.living.name) && json.toJson(actual.memory).contains("migration_fixture_"+i),"Migration preserves Unicode name and memory");
        }
        try(var files=Files.list(state.getParent())) {
            var backups=files.filter(p->p.getFileName().toString().startsWith("markai.json.pre-0.9-")).toList();
            check(backups.size()==1 && Arrays.equals(legacy,Files.readAllBytes(backups.getFirst())),"Legacy migration keeps an exact original backup");
        }
        Map<UUID,GroupChatState> groups=new LinkedHashMap<>();
        for(var c:migrated.companions()) {
            UUID round=UUID.randomUUID();
            groups.put(c.owner,GroupChatState.defaults().append(new GroupChatState.Message(round,c.owner,"user","Игрок","Общая тема: река"))
                .append(new GroupChatState.Message(round,c.id,"assistant",c.living.name,"Помню нашу прогулку.")));
        }
        store.write(state,migrated.companions(),migrated.selected(),groups);
        boot(profile,args[1],jar,expectedVersion,fabricMods,"03-current-reopen");
        var reopened=store.read(state);
        check(reopened.groups().equals(groups) && reopened.selected().equals(migrated.selected()),"Current server restart retains shared transcripts, preferences and per-owner selection");
        check(reopened.companions().stream().map(c->c.id).toList().equals(migrated.companions().stream().map(c->c.id).toList()),"Restart cannot duplicate identities");
        byte[] corrupt="{broken synthetic state".getBytes(StandardCharsets.UTF_8);Files.write(state,corrupt);
        boot(profile,args[1],jar,expectedVersion,fabricMods,"04-corrupt-preserved");
        check(Arrays.equals(Files.readAllBytes(state),corrupt),"A corrupt save remains byte-for-byte unchanged after boot and shutdown");
        check(Files.readString(profile.resolve("04-corrupt-preserved.log")).contains("spawning disabled to prevent duplicates"),"Corrupt state enters the explicit fail-closed path");
        var result=new LinkedHashMap<String,Object>();result.put("completed",true);result.put("production_mode",true);
        result.put("boots",4);result.put("fresh_config",true);result.put("legacy_upgrade",true);result.put("exact_migration_backup",true);
        result.put("shared_history_restart",true);result.put("corrupt_save_preserved",true);result.put("profile",profile.toString());
        result.put("jar_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        result.put("finished_at",Instant.now().toString());
        Files.writeString(build.resolve("production-smoke-result.json"),json.toJson(result));
        System.out.println("Production JAR smoke: four boots passed (fresh, legacy migration, shared restart, corrupt preservation)");
    }
    private static int port() throws Exception { try(var socket=new ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void boot(Path profile,String classpath,Path jar,String expectedVersion,String fabricMods,String label) throws Exception {
        Path log=profile.resolve(label+".log");
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(executable,"-Xmx1G","-Dfabric.development=false","-Dfabric.addMods="+fabricMods,"-cp",classpath,
            "net.fabricmc.loader.impl.launch.knot.KnotServer","--nogui")
            .directory(profile.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
            boolean ready=false;
            while(process.isAlive() && System.nanoTime()<deadline) {
                if(Files.exists(log) && Files.readString(log).contains("Done (")) { ready=true;break; }
                Thread.sleep(100);
            }
            if(!ready)throw new AssertionError(label+" did not reach normal server readiness; inspect "+log);
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();
            if(!process.waitFor(45,TimeUnit.SECONDS) || process.exitValue()!=0)throw new AssertionError(label+" did not stop cleanly; inspect "+log);
            String output=Files.readString(log);
            check(output.contains("markai "+expectedVersion) && !output.contains("markai-beta-test") && !output.contains("markai-test "),"Production run must load the release JAR without test mods");
            check(!output.contains("Cannot initialize local AI"),"Default production provider must initialize");
            System.out.println("Production JAR smoke: "+label+" passed");
        } finally {
            if(process.isAlive()) { process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly(); }
        }
    }
}
