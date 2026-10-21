package dev.markai.companion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** A finite queue created only by a player's message, never by a model's output. */
public final class GroupDialogueRound {
    public record Member(UUID id, String name) {}
    public static final int MAX_REPLIES = 10;
    public record Turn(UUID companionId, UUID replyTo) { public boolean peerReply() { return replyTo != null; } }
    private record Mention(UUID id, int start, int end) {}
    private final UUID id = UUID.randomUUID();
    private final String question;
    private final ArrayDeque<Turn> queue = new ArrayDeque<>();
    private final Set<UUID> eligible;
    private final boolean conversationEnabled;
    private int attempts;

    public GroupDialogueRound(String question, List<UUID> addressed, List<UUID> eligible, boolean mutualReplies) {
        if (question == null || question.isBlank() || question.length() > 512 || eligible.size() > 8)
            throw new IllegalArgumentException("Invalid group round");
        this.question = question;
        var members = new LinkedHashSet<>(eligible);
        if (members.size() != eligible.size() || members.contains(null)) throw new IllegalArgumentException("Invalid group members");
        var direct = new LinkedHashSet<>(addressed);
        if (direct.isEmpty() || !members.containsAll(direct)) throw new IllegalArgumentException("Invalid addressed companions");
        this.eligible = Set.copyOf(members);
        this.conversationEnabled = mutualReplies;
        for (UUID companion : direct) queue.addLast(new Turn(companion, null));
    }
    public UUID id() { return id; }
    public String question() { return question; }
    public int attempts() { return attempts; }
    public Turn next() {
        if (attempts >= MAX_REPLIES) { queue.clear(); return null; }
        Turn next = queue.pollFirst();
        if (next != null) attempts++;
        return next;
    }
    public void replied(Turn turn, String text, List<Member> members) {
        if (!conversationEnabled || attempts >= MAX_REPLIES) return;
        for (UUID recipient : replyTargets(text, members)) {
            if (!eligible.contains(recipient) || recipient.equals(turn.companionId())) continue;
            var planned = new ArrayList<>(queue);
            int index = -1;
            for (int i=0;i<planned.size();i++) if (planned.get(i).companionId().equals(recipient)) { index=i; break; }
            Turn reply = new Turn(recipient, turn.companionId());
            if (index >= 0) planned.set(index, reply); else planned.add(reply);
            queue.clear(); queue.addAll(planned);
        }
    }
    /** A named question invites a response; mentioning someone in a statement does not. */
    public static List<UUID> replyTargets(String text, List<Member> members) {
        if (text == null || text.indexOf('?') < 0 && text.indexOf('？') < 0) return List.of();
        List<UUID> mentioned = addressed(text, members);
        List<UUID> result = new ArrayList<>();
        for (Member member : members) if (mentioned.contains(member.id()) && member.name() != null) {
            Pattern address = Pattern.compile("(?:^|[,.!?;]\\s*)\\s*" + Pattern.quote(member.name().strip()) + "(?=\\s*[,!:?？])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (address.matcher(text).find()) result.add(member.id());
        }
        return List.copyOf(result);
    }

    /** Keep the addressee visible even when a model drops the name from its natural reply. */
    public static String directedReply(String text, Member recipient, List<Member> members, int limit) {
        if (text == null || text.isBlank() || limit < 1) throw new IllegalArgumentException("Invalid directed reply");
        String result = text.strip();
        if (recipient != null && replyTargets(result,members).isEmpty()
                && addressed(result,List.of(recipient)).isEmpty()) {
            int first=result.codePointAt(0), next=Character.charCount(first);
            if (Character.isUpperCase(first) && next<result.length()
                    && (Character.isLowerCase(result.codePointAt(next)) || Character.isWhitespace(result.codePointAt(next))))
                result=new String(Character.toChars(Character.toLowerCase(first)))+result.substring(next);
            result=recipient.name()+", "+result;
        }
        return result.substring(0,Math.min(limit,result.length()));
    }

    /** Actual names only, with Unicode word boundaries; selection and legacy aliases play no role. */
    public static List<UUID> addressed(String text, List<Member> members) {
        if (text == null || text.length() > 512) return List.of();
        List<Mention> mentions = new ArrayList<>();
        for (Member member : members) {
            if (member.id() == null || member.name() == null || member.name().isBlank()) continue;
            Pattern name = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(member.name().strip()) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            var match = name.matcher(text);
            while (match.find()) mentions.add(new Mention(member.id(), match.start(), match.end()));
        }
        List<UUID> result = new ArrayList<>();
        for (Mention mention : mentions) if (mentions.stream().noneMatch(other -> other.start() <= mention.start()
                && other.end() >= mention.end() && other.end()-other.start() > mention.end()-mention.start())) result.add(mention.id());
        return List.copyOf(new LinkedHashSet<>(result));
    }

    /** Only a leading explicit address is removed before deterministic command parsing. */
    public static String withoutAddress(String text, List<Member> members) {
        String result = text.strip();
        for (int i = 0; i < 8; i++) {
            String before = result;
            for (Member member : members.stream().sorted(java.util.Comparator.comparingInt((Member m) -> m.name() == null ? 0 : m.name().length()).reversed()).toList()) {
                if (member.name() == null || member.name().isBlank()) continue;
                String name = member.name().strip();
                if (result.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))
                        && (result.length() == name.length() || !Character.isLetterOrDigit(result.charAt(name.length())) && result.charAt(name.length()) != '_')) {
                    result = result.substring(name.length()).replaceFirst("^[\\s,:!]+", "");
                    result = result.replaceFirst("(?iu)^(?:и|and)\\s+", "");
                    break;
                }
            }
            if (result.equals(before)) break;
        }
        return result;
    }
}
