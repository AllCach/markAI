package dev.markai.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.markai.network.NetworkRules;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One owner roster, shared conversation and bulk preferences; personal screens remain separate. */
@Environment(EnvType.CLIENT)
public final class CompanionHubScreen extends Screen {
    public enum Tab { COMPANIONS, GROUP_COMMANDS, CHAT, SETTINGS }
    private static final int WHITE=0xFFE8EFEA, MUTED=0xFF9DB4A8, ACCENT=0xFF9CDBB5;
    private static final List<String> SETTINGS = List.of("all:defense", "all:autochat", "all:hud", "all:follow_distance", "group_enabled", "mutual_replies");
    private static final List<String> GROUP_COMMANDS = List.of("follow", "come", "stay", "stop", "attack", "collect", "rest", "wander", "home", "investigate");
    private Tab tab;
    private int left, top, panelWidth, panelHeight, contentTop, contentBottom;
    private int rosterOffset, settingsOffset, chatScroll, groupOffset;
    private int rosterSize = -1;
    private String draft = "", requestedId = "", confirmRemoveId = "", pendingRemoveId = "";
    private CompanionScreen.Tab requestedTab;
    private long requestedAt, confirmRemoveUntil, pendingRemoveUntil;
    private EditBox input;
    private Button send, cancel;
    private final List<Button> settingsButtons = new ArrayList<>();
    private final List<Button> groupButtons = new ArrayList<>();
    private final java.util.Map<String, Button> removeButtons = new java.util.LinkedHashMap<>();

    public CompanionHubScreen() { this(Tab.COMPANIONS); }
    public CompanionHubScreen(Tab tab) { super(tr("markai.hub.title")); this.tab = tab; }
    private static Component tr(String key) { return Component.translatable(key); }
    private static String label(String key) { return tr(key).getString(); }
    private static ClientCompanionState state() { return MarkAIClient.STATE; }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        if (input != null) draft = input.getValue();
        input = null; send = null; cancel = null; settingsButtons.clear(); groupButtons.clear(); removeButtons.clear();
        panelWidth = Math.min(820, width - 16); panelHeight = Math.min(500, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        contentTop = top + (panelWidth < 440 ? 82 : 64); contentBottom = top + panelHeight - 30;
        button("‹", left + 10, top + 8, 24, () -> MarkAIClient.show(CompanionScreen.Tab.PROFILE, false));
        button("×", left + panelWidth - 30, top + 8, 20, () -> minecraft.gui.setScreen(null));
        int columns = panelWidth < 440 ? 2 : Tab.values().length;
        for (Tab destination : Tab.values()) {
            int row = destination.ordinal() / columns;
            int column = destination.ordinal() % columns;
            int tabWidth = (panelWidth - 20 - (columns - 1) * 3) / columns;
            Button button = button(label("markai.hub.tab." + destination.name().toLowerCase(Locale.ROOT)),
                left + 10 + column * (tabWidth + 3), top + 35 + row * 23, tabWidth, () -> changeTab(destination));
            button.active = tab != destination;
        }
        switch (tab) {
            case COMPANIONS -> initRoster();
            case GROUP_COMMANDS -> initGroupCommands();
            case CHAT -> initChat();
            case SETTINGS -> initSettings();
        }
        updateButtons();
    }
    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(x,y,w,20).build());
    }
    private void changeTab(Tab next) { if (input != null) draft = input.getValue(); tab = next; clearWidgets(); init(); MarkAIClient.request(); }
    private int columns() { return panelWidth >= 560 ? 2 : 1; }
    private int cardWidth() { return (panelWidth - 28 - (columns()-1)*8) / columns(); }
    private int visibleCards() { return Math.max(1, (contentBottom-contentTop-14)/72) * columns(); }
    private int settingSlots() { return Math.max(1, Math.min(SETTINGS.size(), (contentBottom-contentTop-14)/24)); }
    private void initRoster() {
        rosterSize = state().companions().size();
        rosterOffset = Math.min(rosterOffset, Math.max(0, rosterSize-visibleCards()));
        for (int i=0; i<visibleCards() && i+rosterOffset<rosterSize; i++) {
            JsonObject member = state().companions().get(i+rosterOffset).getAsJsonObject();
            String id = ClientCompanionState.string(member,"id","");
            int x=left+14+(i%columns())*(cardWidth()+8), y=contentTop+14+(i/columns())*72;
            int w=(cardWidth()-16)/3;
            removeButtons.put(id, button(label("markai.hub.remove"),x+cardWidth()-76,y+3,56,()->removeMember(id)));
            button(label("markai.tab.profile"),x+6,y+43,w,()->openMember(id,CompanionScreen.Tab.PROFILE));
            button(label("markai.tab.dialogue"),x+9+w,y+43,w,()->openMember(id,CompanionScreen.Tab.DIALOGUE));
            button(label("markai.tab.settings"),x+12+2*w,y+43,w,()->openMember(id,CompanionScreen.Tab.SETTINGS));
        }
    }
    private void openMember(String id, CompanionScreen.Tab destination) {
        if (!requestedId.isBlank()) return;
        if (id.equals(state().companionId())) { MarkAIClient.show(destination,false); return; }
        if (MarkAIClient.select(id)) { requestedId=id; requestedTab=destination; requestedAt=System.nanoTime(); }
    }
    private void removeMember(String id) {
        if (!id.equals(confirmRemoveId) || System.nanoTime() > confirmRemoveUntil) {
            confirmRemoveId = id;
            confirmRemoveUntil = System.nanoTime() + 3_000_000_000L;
            return;
        }
        if (MarkAIClient.removeCompanion(id)) {
            confirmRemoveId = "";
            pendingRemoveId = id;
            pendingRemoveUntil = System.nanoTime() + 3_000_000_000L;
            state().notice("status", label("markai.hub.removing"));
        }
    }
    private int groupColumns() { return panelWidth >= 680 ? 4 : panelWidth >= 450 ? 3 : 2; }
    private int groupRows() { return (GROUP_COMMANDS.size() + groupColumns() - 1) / groupColumns(); }
    private int visibleGroupRows() { return Math.max(1, (contentBottom - contentTop - 28) / 25); }
    private void initGroupCommands() {
        groupOffset = Math.min(groupOffset, Math.max(0, groupRows() - visibleGroupRows()));
        int columns = groupColumns();
        int w = (panelWidth - 28 - (columns - 1) * 6) / columns;
        int start = groupOffset * columns;
        int end = Math.min(GROUP_COMMANDS.size(), start + visibleGroupRows() * columns);
        for (int i = start; i < end; i++) {
            String action = GROUP_COMMANDS.get(i);
            int local = i - start;
            Button command = button(label("markai.command." + action),
                left + 14 + (local % columns) * (w + 6),
                contentTop + 25 + (local / columns) * 25, w, () -> MarkAIClient.groupAction(action));
            command.setTooltip(Tooltip.create(tr("markai.hub.group_command_hint")));
            groupButtons.add(command);
        }
    }
    private void initChat() {
        int y=contentBottom-21, x=left+12;
        int inputWidth=panelWidth-186;
        input=addRenderableWidget(new EditBox(font,x,y,inputWidth,20,tr("markai.hub.message")));
        input.setMaxLength(512); input.setHint(tr("markai.hub.message")); input.setValue(draft); input.setResponder(ignored -> updateButtons());
        send=button(label("markai.ui.send"),x+inputWidth+6,y,82,this::sendMessage);
        cancel=button(label("markai.ui.cancel"),x+inputWidth+94,y,68,()->MarkAIClient.action("group_cancel"));
        setInitialFocus(input);
    }
    private void initSettings() {
        settingsOffset=Math.min(settingsOffset,Math.max(0,SETTINGS.size()-settingSlots()));
        for (int i=0;i<settingSlots();i++) {
            final String key=SETTINGS.get(settingsOffset+i);
            Button setting=button("",left+14,contentTop+14+i*24,panelWidth-28,()->toggleSetting(key));
            setting.setTooltip(Tooltip.create(tr("markai.hub.hint."+key.replace(":","_"))));
            settingsButtons.add(setting);
        }
    }
    private void toggleSetting(String key) {
        if (key.equals("all:follow_distance")) {
            double current=ClientCompanionState.number(state().commonSettings(),"follow_distance",4);
            MarkAIClient.setting(key,Double.toString(current>=16?2:Math.floor(current)+1));
        } else {
            JsonObject source=key.startsWith("all:")?state().commonSettings():state().group();
            String field=settingField(key);
            // A mixed value becomes enabled on the first click, then can be disabled for everyone.
            boolean enabled=ClientCompanionState.string(source,field,"").equals("true");
            MarkAIClient.setting(key,Boolean.toString(!enabled));
        }
    }
    private void sendMessage() {
        if (input==null || !ClientCompanionState.bool(state().group(),"can_chat",false)) return;
        String text=input.getValue().strip();
        if (NetworkRules.chat(text) && MarkAIClient.groupChat(text)) { input.setValue(""); draft=""; chatScroll=0; }
    }
    private void updateButtons() {
        long now = System.nanoTime();
        if (now > confirmRemoveUntil) confirmRemoveId = "";
        if (now > pendingRemoveUntil || !containsCompanion(pendingRemoveId)) pendingRemoveId = "";
        for (var entry : removeButtons.entrySet()) {
            boolean confirming = entry.getKey().equals(confirmRemoveId);
            boolean pending = entry.getKey().equals(pendingRemoveId);
            entry.getValue().setMessage(tr(pending ? "markai.hub.removing" : confirming ? "markai.hub.remove_confirm" : "markai.hub.remove"));
            entry.getValue().active = MarkAIClient.available() && !pending && requestedId.isBlank();
        }
        if (send!=null) send.active=MarkAIClient.available() && ClientCompanionState.bool(state().group(),"can_chat",false) && NetworkRules.chat(input.getValue());
        if (cancel!=null) cancel.active=MarkAIClient.available() && ClientCompanionState.bool(state().group(),"thinking",false);
        for (int i=0;i<groupButtons.size();i++) {
            groupButtons.get(i).active = MarkAIClient.available() && !state().companions().isEmpty();
            groupButtons.get(i).setTooltip(Tooltip.create(tr("markai.hub.group_command_hint")));
        }
        for (int i=0;i<settingsButtons.size();i++) {
            String key=SETTINGS.get(settingsOffset+i), field=settingField(key);
            JsonObject source=key.startsWith("all:")?state().commonSettings():state().group();
            String raw=ClientCompanionState.string(source,field,"mixed"), value;
            if (raw.equals("mixed")) value=label("markai.hub.mixed");
            else if (field.equals("follow_distance")) value=String.format(Locale.ROOT,"%.0f",ClientCompanionState.number(source,field,4));
            else value=label(raw.equals("true")?"markai.ui.on":"markai.ui.off");
            settingsButtons.get(i).setMessage(Component.literal(label("markai.hub.setting."+key.replace(":","_"))+": "+value));
            settingsButtons.get(i).active=MarkAIClient.available() && !state().companions().isEmpty();
        }
        if (!requestedId.isBlank()) for (var child:children()) if (child instanceof Button button && !button.getMessage().getString().equals("×")) button.active=false;
    }
    private boolean containsCompanion(String id) {
        if (id == null || id.isBlank()) return false;
        for (var companion : state().companions())
            if (companion.isJsonObject() && ClientCompanionState.string(companion.getAsJsonObject(),"id","").equals(id)) return true;
        return false;
    }
    private static String settingField(String key) { return key.startsWith("all:")?key.substring(4):key.equals("group_enabled")?"enabled":key; }
    @Override public void tick() {
        if (!requestedId.isBlank()) {
            if (requestedId.equals(state().companionId())) { MarkAIClient.show(requestedTab,false); return; }
            if (System.nanoTime()-requestedAt>2_000_000_000L) { requestedId=""; clearWidgets(); init(); }
        }
        if ((tab==Tab.COMPANIONS || tab==Tab.GROUP_COMMANDS) && rosterSize!=state().companions().size()) { clearWidgets(); init(); }
        updateButtons();
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (input!=null && input.isFocused() && (event.key()==InputConstants.KEY_RETURN || event.key()==InputConstants.KEY_NUMPADENTER)) { sendMessage(); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if (vertical!=0 && x>=left && x<=left+panelWidth && y>=contentTop && y<contentBottom) {
            int delta=vertical>0?-1:1;
            if (tab==Tab.CHAT) chatScroll=Math.max(0,chatScroll+delta*3);
            else if (tab==Tab.GROUP_COMMANDS) { groupOffset=Math.max(0,Math.min(Math.max(0,groupRows()-visibleGroupRows()),groupOffset+delta)); clearWidgets(); init(); }
            else if (tab==Tab.COMPANIONS) { rosterOffset=Math.max(0,Math.min(Math.max(0,state().companions().size()-visibleCards()),rosterOffset+delta*columns())); clearWidgets(); init(); }
            else { settingsOffset=Math.max(0,Math.min(SETTINGS.size()-settingSlots(),settingsOffset+delta)); clearWidgets(); init(); }
            return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(0,0,width,height,0x9910181B);
        graphics.fill(left,top,left+panelWidth,top+panelHeight,0xF0192528);
        graphics.outline(left,top,panelWidth,panelHeight,0xFF426152);
        graphics.text(font,font.plainSubstrByWidth(title.getString(),panelWidth-100),left+44,top+14,WHITE);
        switch(tab) {
            case COMPANIONS -> drawRoster(graphics);
            case GROUP_COMMANDS -> {
                graphics.text(font,font.plainSubstrByWidth(label("markai.hub.group_commands_hint"),panelWidth-28),left+14,contentTop+2,MUTED);
                if (groupRows()>visibleGroupRows()) graphics.text(font,label("markai.hub.group_commands_scroll"),left+14,contentBottom-12,MUTED);
            }
            case CHAT -> drawChat(graphics);
            case SETTINGS -> graphics.text(font,font.plainSubstrByWidth(label("markai.hub.settings_hint"),panelWidth-28),left+14,contentTop+1,MUTED);
        }
        String status;
        if (tab==Tab.CHAT) status=ClientCompanionState.bool(state().group(),"thinking",false)
            ? ClientCompanionState.string(state().group(),"speaker","")+label("markai.hub.thinking")+" · "+(int)ClientCompanionState.number(state().group(),"reply_count",0)+"/10"
            : ClientCompanionState.string(state().group(),"status",label("markai.ui.loading"));
        else status=tab==Tab.SETTINGS && settingSlots()<SETTINGS.size()?label("markai.hub.scroll_settings")
            : state().companions().size()+" / 8 · "+label("markai.hub.only_yours");
        if (state().errorNotice()) status=state().notice();
        graphics.text(font,font.plainSubstrByWidth(status,panelWidth-24),left+12,top+panelHeight-18,state().errorNotice()?0xFFFFA1A1:MUTED);
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
    }
    private void drawRoster(GuiGraphicsExtractor graphics) {
        graphics.text(font,font.plainSubstrByWidth(label("markai.hub.roster_hint"),panelWidth-28),left+14,contentTop+1,MUTED);
        if (state().companions().isEmpty()) { graphics.text(font,tr("markai.hub.empty"),left+14,contentTop+25,MUTED); return; }
        for (int i=0;i<visibleCards() && i+rosterOffset<state().companions().size();i++) {
            JsonObject member=state().companions().get(i+rosterOffset).getAsJsonObject();
            int x=left+14+(i%columns())*(cardWidth()+8),y=contentTop+14+(i/columns())*72,w=cardWidth();
            boolean selected=ClientCompanionState.string(member,"id","").equals(state().companionId());
            graphics.fill(x,y,x+w,y+66,selected?0xFF263F38:0xFF202E31); graphics.outline(x,y,w,66,selected?0xFF7BB395:0xFF3C5350);
            String name=ClientCompanionState.string(member,"name","Mark");
            graphics.text(font,font.plainSubstrByWidth((selected?"● ":"")+name,w-82),x+7,y+6,ACCENT);
            String health=String.format(Locale.ROOT,"%.0f / %.0f",ClientCompanionState.number(member,"health",0),ClientCompanionState.number(member,"max_health",40));
            String activity=CompanionScreen.localizedState("activity",ClientCompanionState.string(member,"activity","IDLE"));
            graphics.text(font,font.plainSubstrByWidth(health+" · "+activity,w-14),x+7,y+19,MUTED);
            int bar=w-14; double max=ClientCompanionState.number(member,"max_health",40);
            double healthValue=ClientCompanionState.number(member,"health",0)/Math.max(1,max);
            graphics.fill(x+7,y+32,x+7+bar,y+35,0xFF152322);
            graphics.fill(x+7,y+32,x+7+(int)(bar*Math.clamp(healthValue,0,1)),y+35,0xFF8AC6A0);
        }
    }
    private record ChatLine(FormattedCharSequence text,int color) {}
    private void drawChat(GuiGraphicsExtractor graphics) {
        int x=left+12,w=panelWidth-24,bottom=contentBottom-29;
        List<ChatLine> lines=new ArrayList<>();
        for (var element:state().groupMessages()) if (element.isJsonObject()) {
            JsonObject entry=element.getAsJsonObject(); boolean user=ClientCompanionState.string(entry,"role","").equals("user");
            String name=user?label("markai.ui.you"):ClientCompanionState.string(entry,"name","Mark");
            String text=ClientCompanionState.string(entry,"text","");
            for (var line:font.split(Component.literal(name+": "+text),w)) lines.add(new ChatLine(line,user?WHITE:ACCENT));
            lines.add(new ChatLine(FormattedCharSequence.EMPTY,MUTED));
        }
        int visible=Math.max(1,(bottom-contentTop)/11);
        chatScroll=Math.min(chatScroll,Math.max(0,lines.size()-visible));
        int end=lines.size()-chatScroll,start=Math.max(0,end-visible);
        graphics.enableScissor(x,contentTop,x+w,bottom);
        if (lines.isEmpty()) for (var line:font.split(tr("markai.hub.chat_hint"),w)) {
            graphics.text(font,line,x,contentTop+4+lines.size()*11,MUTED); lines.add(new ChatLine(line,MUTED));
        }
        else for (int i=start;i<end;i++) graphics.text(font,lines.get(i).text(),x,contentTop+(i-start)*11,lines.get(i).color());
        graphics.disableScissor();
    }
}
