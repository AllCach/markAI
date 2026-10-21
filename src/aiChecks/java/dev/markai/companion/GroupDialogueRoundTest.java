package dev.markai.companion;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GroupDialogueRoundTest {
    private static int checks;
    private static void check(boolean value,String description){checks++;if(!value)throw new AssertionError(description);}
    private static void rejects(Runnable action,String description){try{action.run();}catch(IllegalArgumentException|NullPointerException expected){checks++;return;}throw new AssertionError(description);}
    private record Saved(UUID id,String name){}
    public static void main(String[] args)throws Exception{
        UUID walk=UUID.randomUUID(),mark=UUID.randomUUID(),other=UUID.randomUUID(),owner=UUID.randomUUID();
        var members=List.of(new GroupDialogueRound.Member(walk,"Walk"),new GroupDialogueRound.Member(mark,"Марк"));
        check(GroupDialogueRound.addressed("wAlK, привет",members).equals(List.of(walk)),"Case-insensitive actual name");
        check(GroupDialogueRound.addressed("МАРК! Что думаешь?",members).equals(List.of(mark)),"Cyrillic names");
        check(GroupDialogueRound.addressed("Walk и Марк, привет!",members).equals(List.of(walk,mark)),"Several named companions");
        check(GroupDialogueRound.addressed("Маркиз Walkman Walk_2",members).isEmpty(),"Partial names and usernames cannot address companions");
        check(GroupDialogueRound.addressed("Волк, привет",members).isEmpty(),"No legacy alias redirects to selection");
        var overlap=List.of(new GroupDialogueRound.Member(mark,"Марк"),new GroupDialogueRound.Member(other,"Марк 2"));
        check(GroupDialogueRound.addressed("Марк 2, привет",overlap).equals(List.of(other)),"The longest actual name wins overlapping mentions");
        check(GroupDialogueRound.addressed("Марк 2 и Марк",overlap).equals(List.of(mark,other)),"An independent short-name mention still works");
        check(GroupDialogueRound.withoutAddress("Марк 2, жди здесь",overlap).equals("жди здесь"),"Strip longest leading address first");
        check(GroupDialogueRound.withoutAddress("Walk и Марк, ко мне",members).equals("ко мне"),"Strip multiple leading addresses");
        check(GroupDialogueRound.withoutAddress("Я помню Walk, фас",members).equals("Я помню Walk, фас"),"A past mention cannot become an imperative");
        check(GroupDialogueRound.addressed("Рекс [1], привет",List.of(new GroupDialogueRound.Member(walk,"Рекс [1]"))).equals(List.of(walk)),"Names are literal, not regular expressions");
        check(GroupDialogueRound.addressed("Walk",List.of(new GroupDialogueRound.Member(walk,"Walk"),new GroupDialogueRound.Member(mark,"Walk"))).size()==2,"Identical names retain distinct identities");
        check(GroupDialogueRound.replyTargets("Марк, я рядом.",members).isEmpty(),"A statement ends naturally without inviting another response");
        check(GroupDialogueRound.replyTargets("Как думаешь, Марк?",members).equals(List.of(mark)),"A named question can address the companion at its end");
        check(GroupDialogueRound.replyTargets("Марк, как дела?",members).equals(List.of(mark)),"A leading named question invites a reply");
        check(GroupDialogueRound.replyTargets("Я упомянул Марк в рассказе. Что скажешь?",members).isEmpty(),"An incidental name is not an invitation");
        check(GroupDialogueRound.directedReply("В лес!",members.getFirst(),members,240).equals("Walk, в лес!"),"Missing peer names are filled from the real routed recipient");
        check(GroupDialogueRound.directedReply("Walk, я рядом.",members.getFirst(),members,240).equals("Walk, я рядом."),"A correct addressee is not duplicated");
        check(GroupDialogueRound.directedReply("Марк, как дела?",members.getFirst(),members,240).equals("Марк, как дела?"),"An explicit new named question keeps its chosen recipient");
        check(GroupDialogueRound.directedReply("x".repeat(240),members.getFirst(),members,120).length()==120,"Directed replies respect the dialogue size limit");
        var quiet=new GroupDialogueRound("Привет всем",List.of(walk,mark),List.of(walk,mark),false);
        for(UUID id:List.of(walk,mark)){var turn=quiet.next();check(turn.companionId().equals(id)&&!turn.peerReply(),"Quiet mode gives one direct answer");quiet.replied(turn,"Марк, как дела?",members);}
        check(quiet.next()==null&&quiet.attempts()==2,"Disabling conversation blocks peer continuations");
        var chain=new GroupDialogueRound("Walk, привет",List.of(walk),List.of(walk,mark),true);
        UUID prior=null;
        for(int i=0;i<10;i++){
            var turn=chain.next();check(turn!=null,"An invited peer can answer within the budget");
            if(i>0)check(turn.peerReply()&&turn.replyTo().equals(prior),"Each peer turn retains exactly who asked it");
            String recipient=turn.companionId().equals(walk)?"Марк":"Walk";
            chain.replied(turn,recipient+", вопрос "+i+": как дела?",members);prior=turn.companionId();
        }
        check(chain.attempts()==10&&chain.next()==null,"A chain of named questions stops after ten total companion turns");
        for(int i=0;i<20;i++)check(chain.next()==null,"Model outputs cannot reset the exhausted budget");
        var natural=new GroupDialogueRound("Привет",List.of(walk),List.of(walk,mark),true);
        var first=natural.next();natural.replied(first,"Марк, я просто рад прогулке.",members);
        check(natural.next()==null,"Companions are allowed to end well before ten messages");
        var self=new GroupDialogueRound("Привет",List.of(walk),List.of(walk,mark),true);
        self.replied(self.next(),"Walk, как дела?",members);check(self.next()==null,"A companion cannot talk to itself");
        var outsiders=new GroupDialogueRound("Привет",List.of(walk),List.of(walk,mark),true);
        outsiders.replied(outsiders.next(),"Чужой, как дела?",List.of(new GroupDialogueRound.Member(other,"Чужой")));
        check(outsiders.next()==null,"A model cannot summon an unowned or ineligible recipient");
        rejects(()->new GroupDialogueRound("x",List.of(other),List.of(walk),true),"Unknown recipient rejected");
        rejects(()->new GroupDialogueRound("x",List.of(),List.of(walk),true),"Empty recipients rejected");
        rejects(()->new GroupDialogueRound("x",List.of(walk),List.of(walk,walk),true),"Duplicate roster rejected");
        List<UUID> eight=new ArrayList<>();for(int i=0;i<8;i++)eight.add(UUID.randomUUID());
        List<GroupDialogueRound.Member> largeMembers=new ArrayList<>();for(int i=0;i<8;i++)largeMembers.add(new GroupDialogueRound.Member(eight.get(i),"Пёс"+i));
        var full=new GroupDialogueRound("Привет",eight,eight,true);int count=0;
        for(var turn=full.next();turn!=null;turn=full.next()){count++;String target=turn.companionId().equals(eight.getFirst())?"Пёс7":"Пёс0";full.replied(turn,target+", как дела?",largeMembers);}
        check(count==10,"Eight companions share ten turns, never ten each");
        var ninth=new ArrayList<>(eight);ninth.add(UUID.randomUUID());rejects(()->new GroupDialogueRound("x",List.of(ninth.getFirst()),ninth,true),"Roster bound enforced");
        GroupChatState state=GroupChatState.defaults();UUID round=UUID.randomUUID();
        for(int i=0;i<40;i++)state=state.append(new GroupChatState.Message(round,owner,"user","Игрок","Сообщение "+i));
        check(state.messages().size()==24&&state.messages().getFirst().text().equals("Сообщение 16"),"Shared history is bounded and keeps recent context");
        check(GroupChatState.defaults().mutualReplies()&&GroupChatState.defaults().enabled(),"Companions may converse by default");
        rejects(()->new GroupChatState.Message(round,owner,"system","Игрок","text"),"History cannot inject a system role");
        rejects(()->new GroupChatState.Message(round,owner,"user","Игрок","x".repeat(513)),"Message length bounded");
        var dir=Files.createTempDirectory("markai-group-state-");
        try{var path=dir.resolve("markai.json");var store=new CompanionStore<>(Saved.class);
            store.write(path,List.of(new Saved(walk,"Walk")),Map.of(owner,walk),Map.of(owner,state.preferences(true,false)));
            var loaded=store.read(path);check(loaded.groups().get(owner).equals(state.preferences(true,false)),"Preferences and Cyrillic history survive real save/load");
            check(loaded.selected().get(owner).equals(walk)&&loaded.companions().getFirst().id().equals(walk),"Old identity and selection preserved");
            Files.writeString(path,"{\"schemaVersion\":1,\"companions\":[],\"selected\":{}}");
            check(store.read(path).groups().isEmpty(),"Old schema-v1 files need no invented group history");
        }finally{try(var paths=Files.walk(dir)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Group dialogue/state: "+checks+" checks passed");
    }
}
