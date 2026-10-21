package dev.markai.companion;

import java.util.List;

public final class PrivateChatCommandTest {
    private static int checks;

    public static void main(String[] args) {
        var parsed = PrivateChatCommand.parse("Пёс 10 привет, бро!", List.of("Пёс", "Пёс 10"));
        check(parsed != null && parsed.name().equals("Пёс 10") && parsed.message().equals("привет, бро!"),
            "Longest companion name is matched and only the message is routed");
        parsed = PrivateChatCommand.parse("wAlK   привет", List.of("Walk"));
        check(parsed != null && parsed.name().equals("Walk") && parsed.message().equals("привет"),
            "Name matching ignores case and leading message whitespace");
        check(PrivateChatCommand.parse("Walkman привет", List.of("Walk")) == null,
            "A name embedded in another word does not address that companion");
        check(PrivateChatCommand.parse("Маркиза привет", List.of("Марка")) == null,
            "Similar names do not address the wrong companion");
        check(PrivateChatCommand.parse("Walk", List.of("Walk")) == null,
            "Name without a message is not parsed as a private address");
        check(PrivateChatCommand.parse("привет", List.of("Walk")) == null,
            "A message without an explicit name remains available for the selected companion");
        System.out.println("Private chat command parsing: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
