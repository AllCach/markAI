package dev.markai.actions;

import java.util.ArrayList;
import java.util.List;

/** A task is a fresh explicit request, never a recollection, question, conditional or quoted instruction. */
public final class WolfOrderIntentTest {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        expect("attack", "Walk, фас!", "ВОЛК: атакуй цель", "Марк, пожалуйста, в атаку!", "attack target.", "Mark, please attack");
        expect("collect", "собирай", "собери мясо и ресурсы", "Walk, собирай всё рядом!", "подбери предметы, мясо и добычу поблизости",
            "please collect drops and resources", "pick up drops", "collect all nearby items");
        expect("stop", "Walk, стоп", "не атакуй", "прекрати сбор", "остановись", "stop attacking", "cancel", "пожалуйста, не собирай");
        expect("tame", "приручи волка", "приручить", "приручение", "tame wolf", "Марк, приручи, пожалуйста!");

        expect(null, null, "", " ", "ты умеешь атаковать?", "Walk, атакуй?", "Атакуй — это приказ?", "я атакую зомби", "волк атакует овцу",
            "помнишь, как ты собирал мясо", "Я сказал ему атакуй", "«атакуй»", "\"attack\"", "собери ресурсы когда я скажу",
            "потом атакуй", "если увидишь зомби, атакуй", "не собирайся атаковать", "Хочу, чтобы ты собирал мясо",
            "Расскажи про приручение", "Можно приручить волка?", "Я нашёл ресурсы и мясо", "collect drops if safe", "stop means what?",
            "собери мясо и ресурсы и расскажи историю", "/mark attack", "attack\ncollect", "collect\u0000", "x".repeat(513));

        // The server accepts only a fixed vocabulary even after an owner adds a familiar address or courtesy.
        for (String order : List.of("атакуй", "собери мясо и ресурсы", "tame wolf")) {
            for (String prefix : List.of("Помнишь, ", "Если будет нужно, ", "Он сказал ", "Explain "))
                expect(null, prefix + order);
        }
        if (!failures.isEmpty()) throw new AssertionError("WolfOrderIntent failures:\n" + String.join("\n", failures));
        System.out.println("WolfOrderIntent: " + checks + " checks passed");
    }

    private static void expect(String expected, String... messages) {
        for (String message : messages) {
            checks++;
            String actual = WolfOrderIntent.parse(message);
            if (!java.util.Objects.equals(expected, actual)) failures.add("'" + message + "': expected " + expected + ", got " + actual);
        }
    }
}
