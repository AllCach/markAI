package dev.markai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.markai.network.NetworkRules;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native non-pausing owner screen. All mutations travel through the validated server bridge. */
@Environment(EnvType.CLIENT)
public final class CompanionScreen extends Screen {
    public enum Tab { PROFILE, COMMANDS, DIALOGUE, MEMORIES, SETTINGS }
    private static final int WHITE = 0xFFE8EFEA, MUTED = 0xFF9DB4A8, ACCENT = 0xFF9CDBB5;
    private static final String[] TRAITS = {"loyalty", "curiosity", "caution", "playfulness", "sociability", "independence", "confidence", "trust"};
    private final boolean quick;
    private Tab tab;
    private int left, top, panelWidth, panelHeight, contentTop, contentBottom;
    private int conversationScroll, memoryOffset, detailScroll, profileScroll;
    private String selectedMemory = "", draft = "", renameDraft;
    private String companionId = MarkAIClient.STATE.companionId(), requestedCompanion = "";
    private long selectionRequestedAt;
    private boolean traitPage;
    private EditBox input, rename;
    private Button sendButton, deleteButton, renameButton;
    private Button previousCompanion, nextCompanion, previousCard, nextCard;
    private final List<Button> memoryButtons = new ArrayList<>();
    private final List<Button> settingButtons = new ArrayList<>();
    private final List<Button> traitButtons = new ArrayList<>();

    public CompanionScreen(Tab initialTab, boolean quick) {
        super(Component.translatable(quick ? "markai.ui.quick" : "markai.ui.title"));
        this.tab = initialTab; this.quick = quick;
    }
    private static Component tr(String key) { return Component.translatable(key); }
    private static String label(String key) { return tr(key).getString(); }
    private ClientCompanionState state() { return MarkAIClient.STATE; }
    private boolean isChickenSelected() {
        String species = state().string("species", "minecraft:wolf");
        return species.equals("chicken") || species.equals("minecraft:chicken");
    }
    private boolean isSelectedDead() { return state().bool("dead", false); }

    @Override protected void init() {
        if (!companionId.equals(state().companionId())) resetCompanionDrafts();
        rememberDraft();
        panelWidth = Math.max(260, Math.min(760, width - 16));
        panelHeight = Math.max(190, Math.min(470, height - 16));
        if (quick) { panelWidth = Math.min(panelWidth, 400); panelHeight = Math.min(panelHeight, 250); }
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        contentTop = top + (quick ? 42 : panelWidth < 440 ? 82 : 66); contentBottom = top + panelHeight - 34;
        input = null; rename = null; sendButton = null; deleteButton = null; renameButton = null;
        previousCard = null; nextCard = null;
        memoryButtons.clear(); settingButtons.clear(); traitButtons.clear();
        addButton("×", left + panelWidth - 29, top + 8, 20, () -> minecraft.gui.setScreen(null));
        addButton(label("markai.hub.open_short"), left + 80, top + 8, 32, MarkAIClient::showHub)
            .setTooltip(Tooltip.create(tr("markai.hub.title")));
        previousCompanion = addButton("‹", left + panelWidth - 79, top + 8, 20, () -> selectCompanion(-1));
        previousCompanion.setTooltip(Tooltip.create(tr("markai.ui.previous_companion")));
        nextCompanion = addButton("›", left + panelWidth - 56, top + 8, 20, () -> selectCompanion(1));
        nextCompanion.setTooltip(Tooltip.create(tr("markai.ui.next_companion")));
        if (!quick) {
            int columns = panelWidth < 440 ? 3 : Tab.values().length;
            for (Tab destination : Tab.values()) {
                int row = destination.ordinal() / columns;
                int column = destination.ordinal() % columns;
                int columnsInRow = row == 0 ? columns : Tab.values().length - columns;
                int tabWidth = (panelWidth - 20 - (columnsInRow - 1) * 2) / columnsInRow;
                int x = left + 10 + column * (tabWidth + 2);
                if (row > 0) x = left + (panelWidth - (columnsInRow * tabWidth + (columnsInRow - 1) * 2)) / 2 + column * (tabWidth + 2);
                Button button = addButton(label("markai.tab." + destination.name().toLowerCase(Locale.ROOT)),
                    x, top + 34 + row * 23, tabWidth, () -> changeTab(destination));
                button.active = destination != tab;
            }
        }
        switch (tab) {
            case PROFILE -> initProfile();
            case COMMANDS -> initCommands();
            case DIALOGUE -> initDialogue();
            case MEMORIES -> initMemories();
            case SETTINGS -> initSettings();
        }
        updateLabels();
    }

    private void changeTab(Tab destination) {
        rememberDraft(); tab = destination; clearWidgets(); init(); MarkAIClient.request();
    }
    private void rememberDraft() {
        if (input != null) draft = input.getValue();
        if (rename != null) renameDraft = rename.getValue();
    }
    /** Drafts and memory selection belong to one companion, including after a server-side selection. */
    public void selectionUpdated() {
        if (companionId.equals(state().companionId())) return;
        resetCompanionDrafts();
        clearWidgets(); init();
    }
    private void resetCompanionDrafts() {
        companionId = state().companionId(); requestedCompanion = "";
        draft = ""; renameDraft = null; selectedMemory = "";
        conversationScroll = 0; memoryOffset = 0; detailScroll = 0; profileScroll = 0;
        input = null; rename = null;
    }
    private int companionIndex() {
        var companions = state().companions();
        for (int i = 0; i < companions.size(); i++) {
            if (ClientCompanionState.string(companions.get(i).getAsJsonObject(), "id", "").equals(state().companionId())) return i;
        }
        return -1;
    }
    private void selectCompanion(int direction) {
        var companions = state().companions();
        if (companions.size() < 2 || !requestedCompanion.isBlank()) return;
        int index = companionIndex();
        int target = index < 0 ? (direction > 0 ? 0 : companions.size() - 1) : Math.floorMod(index + direction, companions.size());
        String id = ClientCompanionState.string(companions.get(target).getAsJsonObject(), "id", "");
        if (MarkAIClient.select(id)) { requestedCompanion = id; selectionRequestedAt = System.nanoTime(); updateLabels(); }
    }
    /** On narrow windows retain the original compact profile instead of clipping the controls. */
    private boolean showCarousel() { return panelWidth >= 350 && profilePreviewBottom() - contentTop >= 165; }
    private int profilePreviewBottom() {
        int rows = (4 + profileActionColumns() - 1) / profileActionColumns();
        return contentBottom - rows * 23 - 5;
    }
    private int carouselHeight() {
        int available = profilePreviewBottom() - contentTop;
        return Math.min(178, Math.max(92, Math.min(available - 70, available * 3 / 5)));
    }
    private int sideCardWidth() { return Math.max(72, Math.min(130, (panelWidth - 34) / 4)); }

    private JsonObject neighbor(int direction) {
        var roster = state().companions();
        if (roster.size() < 2) return null;
        int index = companionIndex();
        if (index < 0) return null;
        var entry = roster.get(Math.floorMod(index + direction, roster.size()));
        return entry.isJsonObject() ? entry.getAsJsonObject() : null;
    }

    private Button addButton(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), button -> action.run()).bounds(x, y, w, 20).build());
    }
    private void command(String action) {
        if (minecraft.hasControlDown()) {
            MarkAIClient.groupAction(action);
            // Group orders keep the menu open so the player can issue a follow-up.
            return;
        }
        MarkAIClient.action(action);
        if (quick || action.equals("inventory")) minecraft.gui.setScreen(null);
    }
    private void initProfile() {
        if (showCarousel()) {
            int y = contentTop + 2, side = sideCardWidth(), h = carouselHeight();
            previousCard = addButton("‹", left + 15, y + h - 22, side - 10, () -> selectCompanion(-1));
            nextCard = addButton("›", left + panelWidth - 5 - side, y + h - 22, side - 10, () -> selectCompanion(1));
            previousCard.setTooltip(Tooltip.create(tr("markai.ui.previous_companion")));
            nextCard.setTooltip(Tooltip.create(tr("markai.ui.next_companion")));
        }
        String[] actions;
        if (isChickenSelected()) {
            // Chickens have their own runtime; never present wolf adoption or wolf spawning here.
            actions = isSelectedDead() ? new String[] {"spawn"} : new String[] {"summon", "dismiss"};
        } else {
            actions = isSelectedDead() ? new String[] {"spawn"} : new String[] {"spawn", "tame", "summon", "dismiss"};
        }
        int columns = panelWidth >= 420 ? Math.min(4, actions.length) : Math.min(2, actions.length);
        int rows = (actions.length + columns - 1) / columns;
        int w = (panelWidth - 20 - (columns - 1) * 3) / columns;
        int y = contentBottom - (rows * 23 - 3);
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            String key = action.equals("spawn") && isSelectedDead() ? "markai.command.respawn" : "markai.command." + action;
            Button button = addButton(label(key), left + 10 + (i % columns) * (w + 3), y + (i / columns) * 23, w, () -> command(action));
            if (action.equals("summon")) button.setTooltip(Tooltip.create(tr("markai.command.hint.summon")));
            if (action.equals("dismiss")) button.setTooltip(Tooltip.create(tr("markai.command.hint.dismiss")));
            if (action.equals("tame")) button.setTooltip(Tooltip.create(tr("markai.command.hint.tame")));
        }
    }
    private int profileActionColumns() { return panelWidth >= 420 ? 4 : 2; }
    private void initCommands() {
        String[] actions = isChickenSelected()
            ? new String[] {"follow", "come", "stay", "rest", "stop", "summon", "dismiss"}
            : new String[] {"follow", "stay", "come", "rest", "wander", "home", "sethome", "inventory", "attack", "collect", "stop"};
        int availableHeight = contentBottom - contentTop;
        int maxRows = Math.max(1, (availableHeight + 3) / 23);
        int columns = Math.max(panelWidth >= 550 ? 4 : 2, (actions.length + maxRows - 1) / maxRows);
        int rows = (actions.length + columns - 1) / columns;
        int w = (panelWidth - 20 - (columns - 1) * 3) / columns;
        int gapY = rows == 1 ? 0 : Math.min(32, (availableHeight - 20) / (rows - 1));
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            addButton(label("markai.command." + action), left + 10 + (i % columns) * (w + 3), contentTop + (i / columns) * gapY,
                w, () -> command(action));
        }
    }
    private void initDialogue() {
        contentBottom = top + panelHeight - 64;
        int inputY = top + panelHeight - 56;
        input = addRenderableWidget(new EditBox(font, left + 10, inputY, panelWidth - 166, 20, tr("markai.ui.message")));
        input.setMaxLength(512); input.setValue(draft); input.setHint(tr("markai.ui.message"));
        sendButton = addButton(label("markai.ui.send"), left + panelWidth - 150, inputY, 68, this::send);
        addButton(label("markai.ui.cancel"), left + panelWidth - 78, inputY, 68, MarkAIClient::cancel);
        setInitialFocus(input);
    }
    private void initMemories() {
        int listWidth = Math.max(105, (panelWidth - 28) * 2 / 5);
        int visible = Math.max(1, (contentBottom - contentTop - 28) / 23);
        for (int i = 0; i < visible; i++) {
            int row = i;
            memoryButtons.add(addButton("", left + 10, contentTop + i * 23, listWidth, () -> {
                var memories = memories();
                int index = memoryOffset + row;
                if (index < memories.size()) { selectedMemory = ClientCompanionState.string(memories.get(index), "id", ""); detailScroll = 0; }
            }));
        }
        addButton("↑", left + 10, contentBottom - 21, 24, () -> memoryOffset = Math.max(0, memoryOffset - 1));
        addButton("↓", left + 36, contentBottom - 21, 24, () -> memoryOffset = Math.min(Math.max(0, memories().size() - memoryButtons.size()), memoryOffset + 1));
        addButton(label("markai.ui.refresh"), left + 64, contentBottom - 21, Math.max(52, listWidth - 54), MarkAIClient::request);
        addButton("‹", left + listWidth + 20, contentBottom - 21, 24, () -> memoryPage("memory_prev"));
        addButton("›", left + listWidth + 46, contentBottom - 21, 24, () -> memoryPage("memory_next"));
        deleteButton = addButton(label("markai.ui.delete"), left + panelWidth - 94, contentBottom - 21, 84, () -> {
            MarkAIClient.deleteMemory(selectedMemory); selectedMemory = ""; detailScroll = 0;
        });
    }
    private void memoryPage(String action) {
        MarkAIClient.action(action); selectedMemory = ""; memoryOffset = 0; detailScroll = 0;
    }
    private void initSettings() {
        addButton(label(traitPage ? "markai.ui.general" : "markai.ui.personality"), left + panelWidth - 122, contentBottom - 21, 112,
            () -> { rememberDraft(); traitPage = !traitPage; clearWidgets(); init(); });
        if (traitPage) {
            int w = (panelWidth - 26) / 2;
            for (int i = 0; i < TRAITS.length; i++) {
                String trait = TRAITS[i];
                traitButtons.add(addButton("", left + 10 + (i % 2) * (w + 3), contentTop + (i / 2) * 24, w, () -> {
                    double value = ClientCompanionState.number(state().traits(), trait, 50);
                    MarkAIClient.setting("trait_" + trait, Double.toString(value >= 100 ? 0 : Math.min(100, value + 10)));
                }));
            }
            return;
        }
        int w = (panelWidth - 26) / 2;
        for (int i = 0; i < 3; i++) {
            String key = List.of("hud", "defense", "autochat").get(i);
            settingButtons.add(addButton("", left + 10 + (i % 2) * (w + 3), contentTop + (i / 2) * 25, w,
                () -> MarkAIClient.setting(key, Boolean.toString(!ClientCompanionState.bool(state().settings(), key, !key.equals("autochat"))))));
        }
        settingButtons.add(addButton("", left + 13 + w, contentTop + 25, w, () -> {
            double distance = ClientCompanionState.number(state().settings(), "follow_distance", 4);
            MarkAIClient.setting("follow_distance", Double.toString(distance >= 16 ? 2 : Math.floor(distance) + 1));
        }));
        rename = addRenderableWidget(new EditBox(font, left + 10, contentTop + 54, panelWidth - 104, 20, tr("markai.ui.name")));
        rename.setMaxLength(32); rename.setHint(tr("markai.ui.name")); rename.setValue(renameDraft == null ? state().string("name", "Mark") : renameDraft);
        renameButton = addButton(label("markai.ui.rename"), left + panelWidth - 90, contentTop + 54, 80, () -> MarkAIClient.setting("name", rename.getValue()));
    }
    @Override public void tick() { selectionUpdated(); updateLabels(); }
    private void updateLabels() {
        if (!requestedCompanion.isBlank() && !requestedCompanion.equals(state().companionId())
                && System.nanoTime() - selectionRequestedAt > 2_000_000_000L) {
            requestedCompanion = ""; clearWidgets(); init(); return;
        }
        if (requestedCompanion.equals(state().companionId()) || System.nanoTime() - selectionRequestedAt > 2_000_000_000L) requestedCompanion = "";
        boolean canSelect = MarkAIClient.available() && state().companions().size() > 1 && requestedCompanion.isBlank();
        if (previousCompanion != null) previousCompanion.active = canSelect;
        if (nextCompanion != null) nextCompanion.active = canSelect;
        if (previousCard != null) previousCard.active = canSelect;
        if (nextCard != null) nextCard.active = canSelect;
        if (sendButton != null) sendButton.active = MarkAIClient.available() && state().bool("can_chat", true) && NetworkRules.chat(input.getValue());
        if (renameButton != null) renameButton.active = MarkAIClient.available() && NetworkRules.setting("name", rename.getValue());
        if (deleteButton != null) deleteButton.active = NetworkRules.memoryId(selectedMemory);
        var memories = memories();
        memoryOffset = Math.max(0, Math.min(memoryOffset, Math.max(0, memories.size() - memoryButtons.size())));
        for (int i = 0; i < memoryButtons.size(); i++) {
            int index = memoryOffset + i;
            Button button = memoryButtons.get(i);
            button.active = index < memories.size();
            String text = index < memories.size() ? memoryText(memories.get(index)) : "—";
            if (index < memories.size() && ClientCompanionState.string(memories.get(index), "id", "").equals(selectedMemory)) text = "› " + text;
            button.setMessage(Component.literal(font.plainSubstrByWidth(text, button.getWidth() - 10)));
        }
        for (int i = 0; i < settingButtons.size(); i++) {
            String key = List.of("hud", "defense", "autochat", "follow_distance").get(i);
            String value = key.equals("follow_distance") ? String.format(Locale.ROOT, "%.0f", ClientCompanionState.number(state().settings(), key, 4))
                : label(ClientCompanionState.bool(state().settings(), key, !key.equals("autochat")) ? "markai.ui.on" : "markai.ui.off");
            settingButtons.get(i).setMessage(Component.literal(label("markai.setting." + key) + ": " + value));
        }
        for (int i = 0; i < traitButtons.size(); i++) {
            String key = TRAITS[i];
            traitButtons.get(i).setMessage(Component.literal(label("markai.trait." + key) + ": " + String.format(Locale.ROOT, "%.0f", ClientCompanionState.number(state().traits(), key, 50))));
        }
        if (!requestedCompanion.isBlank()) for (var child : children()) {
            if (child instanceof Button button && !button.getMessage().getString().equals("×")) button.active = false;
        }
    }

    private void send() {
        String value = input.getValue().strip();
        if (!requestedCompanion.isBlank() || !state().bool("can_chat", true) || !NetworkRules.chat(value)) return;
        if (MarkAIClient.chat(value)) { input.setValue(""); draft = ""; conversationScroll = 0; }
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.DIALOGUE && input != null && input.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            send(); return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && state().companions().size() > 1) {
            // The selector is the only region that uses scroll to change companions.
            // Do not steal scroll input from chat, memories or other Minecraft controls.
            boolean header = x >= left + 108 && x < left + panelWidth - 84 && y >= top + 7 && y < top + 32;
            boolean preview = tab == Tab.PROFILE && showCarousel() && x >= left + 10
                && x < left + panelWidth - 10 && y >= contentTop && y < contentTop + 2 + carouselHeight();
            if (header || preview) {
                selectCompanion(vertical > 0 ? -1 : 1);
                return true;
            }
        }
        if (y >= contentTop && y <= contentBottom) {
            int delta = (int) Math.signum(vertical) * 3;
            switch (tab) {
                case DIALOGUE -> conversationScroll = Math.max(0, conversationScroll + delta);
                case MEMORIES -> {
                    if (x > left + (panelWidth - 28) * 2 / 5 + 20) detailScroll = Math.max(0, detailScroll - delta);
                    else memoryOffset = Math.max(0, memoryOffset - delta);
                }
                case PROFILE -> profileScroll = Math.max(0, profileScroll - delta);
                default -> { return super.mouseScrolled(x, y, horizontal, vertical); }
            }
            updateLabels(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x9910181B);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0192528);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFF426152);
        graphics.text(font, font.plainSubstrByWidth(title.getString(), 64), left + 12, top + 14, WHITE);
        String name = state().string("name", "Mark");
        int index = companionIndex();
        String selected = (index < 0 ? "" : (index + 1) + "/" + state().companions().size() + " ") + name;
        if (panelWidth >= 390) selected += " · " + label("markai.species." + (isChickenSelected() ? "chicken" : "wolf"));
        if (panelWidth >= 550) selected += " · " + localizedState("activity", state().string("activity", "IDLE"));
        graphics.text(font, font.plainSubstrByWidth(selected, panelWidth - 202), left + 116, top + 14, ACCENT);
        switch (tab) {
            case PROFILE -> drawProfile(graphics, mouseX, mouseY);
            case COMMANDS -> { }
            case DIALOGUE -> drawDialogue(graphics);
            case MEMORIES -> drawMemories(graphics);
            case SETTINGS -> {
                if (traitPage) graphics.text(font, tr("markai.ui.trait_hint"), left + 10, contentBottom - 15, MUTED);
            }
        }
        String status = state().notice();
        if (status.isBlank()) status = state().string("last_action_result", state().string("ai_status", state().received() ? "" : label("markai.ui.loading")));
        if (state().stale()) status = label("markai.ui.stale");
        graphics.text(font, font.plainSubstrByWidth(status, panelWidth - 20), left + 10, top + panelHeight - 19,
            state().errorNotice() ? 0xFFFFA1A1 : MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    private void drawProfile(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int bottom = profilePreviewBottom();
        if (showCarousel()) {
            drawCarousel(graphics, mouseX, mouseY);
            int y = contentTop + carouselHeight() + 10;
            drawProfileDetails(graphics, left + 15, y, panelWidth - 30, Math.max(12, bottom - y - 3));
            return;
        }
        int previewWidth = panelWidth < 400 ? 80 : 130;
        graphics.fill(left + 10, contentTop, left + 10 + previewWidth, bottom, 0x8834493D);
        var entity = MarkAIClient.preview();
        if (entity != null) InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, left + 12, contentTop,
            left + previewWidth + 8, bottom, Math.max(20, Math.min(44, (bottom - contentTop) / 2)), 0.0F, mouseX, mouseY, entity);
        else graphics.text(font, tr("markai.ui.not_loaded"), left + 13, contentTop + 15, MUTED);
        int x = left + previewWidth + 20;
        drawProfileDetails(graphics, x, contentTop + 3, panelWidth - previewWidth - 32, bottom - contentTop - 6);
    }

    /** Larger live 3D model in the middle; compact actionable neighbors on both sides. */
    private void drawCarousel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int side = sideCardWidth(), gap = 6, y = contentTop + 2, h = carouselHeight();
        int prevX = left + 10, centerX = prevX + side + gap;
        int centerWidth = panelWidth - 20 - side * 2 - gap * 2;
        int nextX = centerX + centerWidth + gap;
        drawPreviewCard(graphics, neighbor(-1), prevX, y, side, h, false, mouseX, mouseY);
        var selected = new JsonObject();
        selected.addProperty("name", state().string("name", "Mark"));
        selected.addProperty("entity_uuid", state().string("entity_uuid", ""));
        selected.addProperty("health", state().number("health", 0));
        selected.addProperty("max_health", state().number("max_health", 40));
        selected.addProperty("activity", state().string("activity", "IDLE"));
        drawPreviewCard(graphics, selected, centerX, y, centerWidth, h, true, mouseX, mouseY);
        drawPreviewCard(graphics, neighbor(1), nextX, y, side, h, false, mouseX, mouseY);
    }

    private void drawPreviewCard(GuiGraphicsExtractor graphics, JsonObject companion, int x, int y,
                                 int w, int h, boolean selected, int mouseX, int mouseY) {
        graphics.fill(x, y, x + w, y + h, selected ? 0xCC335447 : 0xAA263B35);
        graphics.outline(x, y, w, h, selected ? ACCENT : 0xFF49685A);
        if (companion == null) return;
        String name = ClientCompanionState.string(companion, "name", "—");
        String activity = ClientCompanionState.string(companion, "activity", "UNLOADED");
        graphics.text(font, font.plainSubstrByWidth(name, w - 12), x + 6, y + 7, selected ? ACCENT : WHITE);
        var entity = MarkAIClient.preview(ClientCompanionState.string(companion, "entity_uuid", ""));
        if (entity != null) {
            int size = selected ? Math.max(24, Math.min(54, (h - 52) / 2))
                : Math.max(16, Math.min(27, (h - 52) / 3));
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 3, y + 20,
                x + w - 3, y + h - 52, size, 0.0F, mouseX, mouseY, entity);
        } else {
            graphics.text(font, font.plainSubstrByWidth(label("markai.ui.not_loaded"), w - 12),
                x + 6, y + Math.max(24, (h - 48) / 2), MUTED);
        }
        String hp = String.format(Locale.ROOT, "%.0f/%.0f",
            ClientCompanionState.number(companion, "health", 0), ClientCompanionState.number(companion, "max_health", 40));
        graphics.text(font, font.plainSubstrByWidth(hp, w - 12), x + 6, y + h - 45, WHITE);
        graphics.text(font, font.plainSubstrByWidth(localizedState("activity", activity), w - 12),
            x + 6, y + h - 34, MUTED);
    }

    private void drawProfileDetails(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        List<String> details = new ArrayList<>();
        details.add(label("markai.ui.health") + ": " + String.format(Locale.ROOT, "%.1f / %.1f", state().number("health", 0), state().number("max_health", 40)));
        details.add(label("markai.ui.activity") + ": " + localizedState("activity", state().string("activity", "IDLE")));
        details.add(label("markai.ui.mood") + ": " + localizedState("mood", state().string("mood", "CALM")));
        details.add(label("markai.ui.trust") + ": " + String.format(Locale.ROOT, "%.0f", state().number("trust", 50)));
        details.add(label("markai.ui.level") + ": " + String.format(Locale.ROOT, "%.0f", state().number("level", 1)));
        details.add("AI: " + state().string("ai_status", "—"));
        details.add(label("markai.ui.home") + ": " + state().string("home", "—"));
        String result = state().string("last_action_result", ""); if (!result.isBlank()) details.add(result);
        for (String key : List.of("fear", "curiosity", "energy", "alertness")) {
            if (state().emotions().has(key)) details.add(label("markai.emotion." + key) + ": " + String.format(Locale.ROOT, "%.0f", ClientCompanionState.number(state().emotions(), key, 0)));
        }
        drawWrapped(graphics, details, x, y, w, h, profileScroll, WHITE);
    }
    private record ChatLine(FormattedCharSequence text, int color) {}
    private void drawDialogue(GuiGraphicsExtractor graphics) {
        int x = left + 12, w = panelWidth - 24;
        List<ChatLine> lines = new ArrayList<>();
        for (var element : state().messages()) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            String role = ClientCompanionState.string(message, "role", "assistant");
            String text = ClientCompanionState.string(message, "text", ClientCompanionState.string(message, "content", ""));
            if (role.equals("assistant") && text.startsWith("{")) {
                try { text = ClientCompanionState.string(JsonParser.parseString(text).getAsJsonObject(), "say", text); } catch (RuntimeException ignored) { }
            }
            String prefix = role.equals("user") ? label("markai.ui.you") : state().string("name", "Mark");
            int color = role.equals("user") ? WHITE : ACCENT;
            for (var line : font.split(Component.literal(prefix + ": " + text), w)) lines.add(new ChatLine(line, color));
            lines.add(new ChatLine(FormattedCharSequence.EMPTY, MUTED));
        }
        int visible = Math.max(1, (contentBottom - contentTop) / 11);
        conversationScroll = Math.min(conversationScroll, Math.max(0, lines.size() - visible));
        int end = Math.max(0, lines.size() - conversationScroll), start = Math.max(0, end - visible);
        graphics.enableScissor(x, contentTop, x + w, contentBottom);
        if (lines.isEmpty()) graphics.text(font, tr("markai.ui.start_chat"), x, contentTop + 4, MUTED);
        for (int i = start; i < end; i++) graphics.text(font, lines.get(i).text(), x, contentTop + (i - start) * 11, lines.get(i).color());
        graphics.disableScissor();
        if (conversationScroll > 0) graphics.text(font, "↓ " + conversationScroll, left + panelWidth - 42, contentBottom - 11, MUTED);
    }
    private List<JsonObject> memories() {
        List<JsonObject> result = new ArrayList<>();
        for (var element : state().memories()) if (element.isJsonObject()) result.add(element.getAsJsonObject());
        return result;
    }
    private static String memoryText(JsonObject memory) {
        return ClientCompanionState.string(memory, "content", ClientCompanionState.string(memory, "text", "—"));
    }
    public static String localizedState(String kind, String value) {
        return Component.translatable("markai." + kind + "." + value.toLowerCase(Locale.ROOT)).getString();
    }
    private void drawMemories(GuiGraphicsExtractor graphics) {
        int x = left + Math.max(105, (panelWidth - 28) * 2 / 5) + 20;
        int w = left + panelWidth - 12 - x;
        JsonObject selected = null;
        for (JsonObject memory : memories()) if (ClientCompanionState.string(memory, "id", "").equals(selectedMemory)) { selected = memory; break; }
        List<String> details = new ArrayList<>();
        if (selected == null) details.add(label("markai.ui.select_memory"));
        else {
            details.add(ClientCompanionState.string(selected, "category", "memory"));
            details.add(memoryText(selected));
            details.add(label("markai.ui.source") + ": " + ClientCompanionState.string(selected, "source", "—"));
            details.add(label("markai.ui.time") + ": " + ClientCompanionState.string(selected, "time", ClientCompanionState.string(selected, "game_time", "—")));
            details.add(label("markai.ui.importance") + ": " + ClientCompanionState.string(selected, "importance", "—"));
        }
        drawWrapped(graphics, details, x, contentTop + 2, w, contentBottom - contentTop - 30, detailScroll, WHITE);
    }
    private void drawWrapped(GuiGraphicsExtractor graphics, List<String> paragraphs, int x, int y, int w, int h, int scroll, int color) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String text : paragraphs) lines.addAll(font.split(Component.literal(text), Math.max(20, w)));
        int maxScroll = Math.max(0, lines.size() - Math.max(1, h / 11));
        int start = Math.min(scroll, maxScroll);
        if (tab == Tab.PROFILE) profileScroll = start; else if (tab == Tab.MEMORIES) detailScroll = start;
        graphics.enableScissor(x, y, x + Math.max(20, w), y + Math.max(10, h));
        for (int i = start; i < lines.size() && (i - start) * 11 < h; i++) graphics.text(font, lines.get(i), x, y + (i - start) * 11, color);
        graphics.disableScissor();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void removed() { rememberDraft(); }
}
