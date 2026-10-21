package dev.markai.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Owner-scoped shared history and preferences; no in-flight requests are persisted. */
public record GroupChatState(boolean enabled, boolean mutualReplies, List<Message> messages) {
    public static final int HISTORY_LIMIT = 24;
    public record Message(UUID roundId, UUID speakerId, String role, String name, String text) {
        public Message {
            if (roundId == null || speakerId == null || !("user".equals(role) || "assistant".equals(role))
                    || !valid(name, 32) || !valid(text, 512)) throw new IllegalArgumentException("Invalid group message");
        }
    }
    public GroupChatState {
        if (messages == null || messages.size() > HISTORY_LIMIT) throw new IllegalArgumentException("Invalid group history");
        messages = List.copyOf(messages);
    }
    public static GroupChatState defaults() { return new GroupChatState(true, true, List.of()); }
    public GroupChatState preferences(boolean enabled, boolean mutualReplies) { return new GroupChatState(enabled, mutualReplies, messages); }
    public GroupChatState append(Message message) {
        var next = new ArrayList<>(messages);
        next.add(java.util.Objects.requireNonNull(message));
        while (next.size() > HISTORY_LIMIT) next.removeFirst();
        return new GroupChatState(enabled, mutualReplies, next);
    }
    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.codePoints().noneMatch(Character::isISOControl);
    }
}
