package dev.markai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.network.NetworkRules;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** One-time choice after intentional taming. Closing keeps the new profile when available. */
@Environment(EnvType.CLIENT)
public final class AdoptionProfileScreen extends Screen {
    private record Profile(String id, String name, int level) { }
    private final String offerId;
    private final String species;
    private final boolean canNew;
    private final List<Profile> profiles;
    private int page;
    private boolean answered;

    public AdoptionProfileScreen(String json) {
        super(Component.translatable("markai.adoption.title"));
        JsonObject data = JsonParser.parseString(json).getAsJsonObject();
        offerId = ClientCompanionState.string(data, "offer_id", "");
        if (!NetworkRules.companionId(offerId)) throw new IllegalArgumentException("Invalid offer");
        species = ClientCompanionState.string(data, "species", "");
        canNew = ClientCompanionState.bool(data, "can_new", false);
        var candidates = new ArrayList<Profile>();
        for (var element : ClientCompanionState.array(data, "profiles")) {
            if (!element.isJsonObject() || candidates.size() >= 8) break;
            JsonObject profile = element.getAsJsonObject();
            String id = ClientCompanionState.string(profile, "id", "");
            if (NetworkRules.companionId(id)) candidates.add(new Profile(id,
                ClientCompanionState.string(profile, "name", "Питомец"),
                (int) ClientCompanionState.number(profile, "level", 1)));
        }
        profiles = List.copyOf(candidates);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int w = Math.min(350, width - 20), x = (width - w) / 2;
        int top = Math.max(4, (height - Math.min(250, height - 8)) / 2);
        int rows = rows();
        Button fresh = addRenderableWidget(Button.builder(Component.translatable("markai.adoption.new"),
            button -> choose("")).bounds(x, top + 48, w, 20).build());
        fresh.active = canNew;
        for (int i = 0; i < rows && page * rows + i < profiles.size(); i++) {
            Profile profile = profiles.get(page * rows + i);
            String label = font.plainSubstrByWidth(profile.name + " · "
                + Component.translatable("markai.adoption.level", profile.level).getString(), w - 16);
            addRenderableWidget(Button.builder(Component.literal(label), button -> choose(profile.id))
                .bounds(x, top + 76 + i * 24, w, 20).build());
        }
        int bottom = Math.min(height - 25, top + 80 + rows * 24);
        if (profiles.size() > rows) {
            Button prev = addRenderableWidget(Button.builder(Component.literal("‹"), button -> changePage(-1))
                .bounds(x, bottom, 24, 20).build());
            prev.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.literal("›"), button -> changePage(1))
                .bounds(x + w - 24, bottom, 24, 20).build());
            next.active = (page + 1) * rows < profiles.size();
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
            .bounds(x + 30, Math.min(height - 25, bottom + 24), w - 60, 20).build());
    }

    private int rows() { return Math.max(1, Math.min(4, (height - 135) / 24)); }
    private void changePage(int delta) { page = Math.max(0, page + delta); clearWidgets(); init(); }
    private void choose(String profileId) {
        if (answered) return;
        answered = true;
        MarkAIClient.adoptionChoice(offerId, profileId);
        minecraft.gui.setScreen(null);
    }

    @Override public void onClose() {
        if (!answered) choose("");
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xDC10181B);
        int w = Math.min(350, width - 20), x = (width - w) / 2;
        int top = Math.max(4, (height - Math.min(250, height - 8)) / 2);
        graphics.text(font, title, x, top + 8, 0xFFE8EFEA);
        graphics.text(font, Component.translatable("markai.adoption.species",
            Component.translatable("markai.species." + (species.equals("minecraft:chicken") ? "chicken" : "wolf"))),
            x, top + 25, 0xFF9CDBB5);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
