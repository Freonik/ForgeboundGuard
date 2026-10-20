package cz.forgebound.guard;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The free first layer of chat moderation: plain rules, no AI, no server API (so it can be tested on its own).
 * It decides whether a message is clearly bad (blocked), fine, or worth a look by the AI (suspicious).
 * Thread-safe enough for the async chat event: per-player state is synchronized.
 */
final class ChatFilter {

    enum Verdict { OK, CAPS, SPAM, REPEAT, LINK, ADVERTISING, BLOCKED_WORD, SUSPICIOUS }

    record Result(Verdict verdict, String detail) {
        boolean blocked() {
            return verdict != Verdict.OK && verdict != Verdict.SUSPICIOUS && verdict != Verdict.CAPS;
        }
    }

    /** Settings, read from the config by the moderation manager. */
    record Settings(boolean caps, int capsMinLength, int capsMaxPercent,
                    int floodMessages, int floodSeconds, int repeatSeconds,
                    boolean links, List<String> allowedLinks, boolean advertising,
                    List<String> blockedWords, List<String> suspiciousWords) {
    }

    private static final Pattern URL = Pattern.compile(
            "(?i)\\b((https?://)?([a-z0-9-]+\\.)+(com|net|org|cz|sk|eu|io|gg|me|xyz|de|uk|ru|info|co|tv|be|ly|link|club|fun|online|pro|top|store|shop|app|dev|site)(/\\S*)?)\\b");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(\\s*[.,]\\s*\\d{1,3}){3}(:\\d{2,5})?\\b");
    private static final Pattern SERVER_AD = Pattern.compile("(?i)\\b(mc|play|hub|server)\\s*[.,]\\s*[a-z0-9-]{3,}\\s*[.,]\\s*[a-z]{2,4}\\b");

    private final Map<UUID, Deque<Long>> times = new HashMap<>();
    private final Map<UUID, String> lastText = new HashMap<>();
    private final Map<UUID, Long> lastAt = new HashMap<>();
    private volatile Settings settings;

    ChatFilter(Settings settings) {
        this.settings = settings;
    }

    void settings(Settings settings) {
        this.settings = settings;
    }

    /** Checks a message sent at "now" (ms). Records it for the spam checks. */
    Result check(UUID player, String text, long now) {
        Settings s = settings;
        String clean = normalize(text);
        synchronized (this) {
            Deque<Long> q = times.computeIfAbsent(player, k -> new ArrayDeque<>());
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > s.floodSeconds() * 1000L) {
                q.pollFirst();
            }
            if (s.floodMessages() > 0 && q.size() > s.floodMessages()) {
                return new Result(Verdict.SPAM, q.size() + " messages in " + s.floodSeconds() + "s");
            }
            String last = lastText.put(player, clean);
            Long lastTime = lastAt.put(player, now);
            if (s.repeatSeconds() > 0 && last != null && lastTime != null && clean.length() > 3
                    && last.equals(clean) && now - lastTime < s.repeatSeconds() * 1000L) {
                return new Result(Verdict.REPEAT, "same message again");
            }
        }
        String word = firstMatch(clean, s.blockedWords());
        if (word != null) {
            return new Result(Verdict.BLOCKED_WORD, word);
        }
        if (s.advertising()) {
            Matcher ip = IP.matcher(text);
            if (ip.find()) {
                return new Result(Verdict.ADVERTISING, ip.group());
            }
            Matcher ad = SERVER_AD.matcher(text);
            if (ad.find() && !allowed(ad.group(), s.allowedLinks())) {
                return new Result(Verdict.ADVERTISING, ad.group());
            }
        }
        if (s.links()) {
            Matcher url = URL.matcher(text);
            while (url.find()) {
                if (!allowed(url.group(), s.allowedLinks())) {
                    return new Result(Verdict.LINK, url.group());
                }
            }
        }
        String suspicious = firstMatch(clean, s.suspiciousWords());
        if (suspicious != null) {
            return new Result(Verdict.SUSPICIOUS, suspicious);
        }
        if (s.caps() && tooLoud(text, s.capsMinLength(), s.capsMaxPercent())) {
            return new Result(Verdict.CAPS, "caps");
        }
        return new Result(Verdict.OK, "");
    }

    void forget(UUID player) {
        synchronized (this) {
            times.remove(player);
            lastText.remove(player);
            lastAt.remove(player);
        }
    }

    // ---------------------------------------------------------------- helpers (static, tested)

    /**
     * Lowercase, no accents, leetspeak undone, repeated letters squashed, separators between letters removed:
     * "D.e.b.1.l" / "dÉÉbiiil" -> "debil".
     */
    static String normalize(String text) {
        String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            char m = switch (c) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            };
            out.append(m);
        }
        // "d.e.b.i.l" / "d e b i l": single letters separated by dots, dashes, stars, underscores or spaces
        String joined = Pattern.compile("\\b(\\p{L})([ ._*\\-]+(?=\\p{L}\\b))").matcher(out).replaceAll("$1");
        // squash runs of the same letter: "debiiiil" -> "debil"
        return joined.replaceAll("(\\p{L})\\1+", "$1");
    }

    /** First entry of the list found in the normalized text (entries are normalized too, whole-word start). */
    static String firstMatch(String normalized, List<String> words) {
        for (String raw : words) {
            String entry = raw.trim();
            // match at the start of a word, so "kys" doesn't fire inside "kyslík" but "debilove" still hits "debil"
            boolean exact = entry.endsWith("$");
            String core = normalize(exact ? entry.substring(0, entry.length() - 1) : entry).trim();
            if (core.isEmpty()) {
                continue;
            }
            Pattern p = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(core) + (exact ? "(?![\\p{L}\\d])" : ""));
            if (p.matcher(normalized).find()) {
                return raw;
            }
        }
        return null;
    }

    static boolean tooLoud(String text, int minLength, int maxPercent) {
        int letters = 0;
        int upper = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters >= minLength && upper * 100 > letters * maxPercent;
    }

    static boolean allowed(String link, List<String> allowedDomains) {
        String l = link.toLowerCase(Locale.ROOT).replace(',', '.').replace(" ", "");
        for (String d : allowedDomains) {
            String domain = d.toLowerCase(Locale.ROOT).trim();
            if (!domain.isEmpty() && (l.contains("//" + domain) || l.startsWith(domain) || l.contains("." + domain)
                    || l.contains("www." + domain))) {
                return true;
            }
        }
        return false;
    }
}
