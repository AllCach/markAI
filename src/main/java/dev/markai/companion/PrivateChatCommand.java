package dev.markai.companion;

import java.util.Collection;
import java.util.Comparator;

/** Parses an optional leading companion name from `/mo <name> <message>`. */
public final class PrivateChatCommand {
    public record Address(String name, String message) {}

    private PrivateChatCommand() {}

    public static Address parse(String input, Collection<String> companionNames) {
        if (input == null || input.isBlank() || companionNames == null) return null;
        String message = input.strip();
        for (String rawName : companionNames.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            String name = rawName.strip();
            if (name.isEmpty() || message.length() <= name.length()
                    || !message.regionMatches(true, 0, name, 0, name.length())
                    || !Character.isWhitespace(message.charAt(name.length()))) continue;
            String addressed = message.substring(name.length()).stripLeading();
            if (!addressed.isBlank()) return new Address(rawName, addressed);
        }
        return null;
    }
}
