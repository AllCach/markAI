package dev.markai.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.markai.companion.GroupDialogueRound;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Opt-in live evaluation using only synthetic shared-chat context, never a player's data. */
public final class GroupDialogueQualityTest {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final UUID REX=UUID.fromString("9cfa7300-2da6-4d83-b275-c775cd1d3001"),LUNA=UUID.fromString("9cfa7300-2da6-4d83-b275-c775cd1d3002");
    private static final List<GroupDialogueRound.Member> MEMBERS=List.of(new GroupDialogueRound.Member(REX,"Рекс"),new GroupDialogueRound.Member(LUNA,"Луна"));
    private static final String TOPIC="Рекс, спроси Луну, куда она хочет пойти после прогулки: к реке или в берёзовый лес.";
    private record Result(String scenario,JsonObject context,String question,List<OllamaClient.ChatMessage> history,OllamaClient.Reply proposed,OllamaClient.Reply reply,boolean passed){}
    private static JsonObject context(String name,String peer,int remaining){
        JsonObject context=JSON.toJsonTree(Map.of("companion",Map.of("name",name,"type","minecraft:wolf","health",40,"max_health",40,"tamed",true),
            "player",Map.of("name","Игрок","health",20),"environment",Map.of("biome","minecraft:forest","raining",false,"time_of_day",9000),
            "personality",Map.of("loyalty",0.8,"curiosity",0.8,"sociability",0.8,"caution",0.4,"playfulness",0.6),
            "emotions",Map.of("trust",75,"energy",80),"conversation_mode","group","group_members",MEMBERS,
            "group_turn",peer==null?"player_reply":"peer_reply","replies_remaining",remaining)).getAsJsonObject();
        if(peer!=null){context.addProperty("reply_to",peer);context.addProperty("group_topic",TOPIC);}return context;
    }
    public static void main(String[] args)throws Exception{
        AIConfig config=new AIConfig();List<Result> results=new ArrayList<>();
        try(LlmProvider provider=config.createProvider()){
            JsonObject firstContext=context("Рекс",null,9);
            OllamaClient.Reply first=provider.ask(firstContext.toString(),TOPIC,List.of()).get(config.timeoutSeconds+5L,TimeUnit.SECONDS);
            results.add(new Result("named invitation",firstContext,TOPIC,List.of(),first,first,
                first.action().equals("say")&&GroupDialogueRound.replyTargets(first.say(),MEMBERS).contains(LUNA)));
            var root=List.of(new OllamaClient.ChatMessage("user","Игрок: "+TOPIC));
            JsonObject secondContext=context("Луна","Рекс",8);String secondQuestion="Рекс: "+first.say();
            OllamaClient.Reply secondProposed=provider.ask(secondContext.toString(),secondQuestion,root).get(config.timeoutSeconds+5L,TimeUnit.SECONDS);
            OllamaClient.Reply second=new OllamaClient.Reply("say",GroupDialogueRound.directedReply(secondProposed.say(),MEMBERS.getFirst(),MEMBERS,config.maxDialogueLength));
            String response=second.say().toLowerCase(Locale.ROOT).replace('ё','е');
            results.add(new Result("named reply and topic",secondContext,secondQuestion,root,secondProposed,second,
                second.action().equals("say")&&response.contains("рекс")&&java.util.regex.Pattern.compile("(?<!\\p{L})(?:рек[аеуи]|рекой|речк\\p{L}*|лес\\p{L}*|берез\\p{L}*)(?!\\p{L})").matcher(response).find()));
            List<OllamaClient.ChatMessage> lastHistory=List.of(root.getFirst(),new OllamaClient.ChatMessage("assistant",new Gson().toJson(first)));
            JsonObject lastContext=context("Рекс","Луна",0);String lastQuestion="Луна: "+second.say();
            OllamaClient.Reply lastProposed=provider.ask(lastContext.toString(),lastQuestion,lastHistory).get(config.timeoutSeconds+5L,TimeUnit.SECONDS);
            OllamaClient.Reply last=new OllamaClient.Reply("say",GroupDialogueRound.directedReply(lastProposed.say(),MEMBERS.getLast(),MEMBERS,config.maxDialogueLength));
            // The hard limit belongs to the scheduler, even if the model ignores its closing hint.
            GroupDialogueRound budget=new GroupDialogueRound("Обсудите прогулку.",List.of(LUNA),List.of(REX,LUNA),true);
            GroupDialogueRound.Turn finalTurn=null;
            for(int i=0;i<GroupDialogueRound.MAX_REPLIES;i++) {
                finalTurn=budget.next();
                if(i<GroupDialogueRound.MAX_REPLIES-1) budget.replied(finalTurn,(finalTurn.companionId().equals(REX)?"Луна":"Рекс")+", как настроение?",MEMBERS);
            }
            budget.replied(finalTurn,last.say(),MEMBERS);
            results.add(new Result("budget enforced after live reply",lastContext,lastQuestion,lastHistory,lastProposed,last,
                last.action().equals("say")&&finalTurn.companionId().equals(REX)&&budget.attempts()==10&&budget.next()==null));
        }finally{
            Files.createDirectories(Path.of("build"));
            boolean closingQuestion=!results.isEmpty()&&(results.getLast().reply().say().indexOf('?')>=0||results.getLast().reply().say().indexOf('？')>=0);
            Files.writeString(Path.of("build/group-dialogue-quality.json"),JSON.toJson(Map.of("model",config.ollamaModel,"synthetic_context",true,"closing_reply_contains_question",closingQuestion,"results",results)),StandardCharsets.UTF_8);
        }
        long passed=results.stream().filter(Result::passed).count();
        System.out.println("Live group dialogue: "+passed+"/"+results.size()+"; read build/group-dialogue-quality.json");
        if(passed!=3)throw new AssertionError("Shared dialogue quality check failed; inspect the actual responses.");
    }
}
