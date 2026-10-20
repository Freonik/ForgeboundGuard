package cz.forgebound.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The free chat filter (no server needed). */
class ChatFilterTest {

    private static ChatFilter filter() {
        return new ChatFilter(new ChatFilter.Settings(true, 8, 70, 5, 8, 30, true,
                List.of("youtube.com", "youtu.be"), true,
                List.of("zakazane"), List.of("debil", "kys$", "zabij se")));
    }

    @Test
    void normalizeUndoesTricks() {
        assertEquals("debil", ChatFilter.normalize("DÉBIIIL"));
        assertEquals("debil", ChatFilter.normalize("d.e.b.1.l"));
        assertEquals("debil", ChatFilter.normalize("d e b i l"));
        assertEquals("ty debil", ChatFilter.normalize("ty d3bil"));
    }

    @Test
    void suspiciousWordsMatchWordStarts() {
        List<String> words = List.of("debil", "kys$");
        assertEquals("debil", ChatFilter.firstMatch(ChatFilter.normalize("ty jsi debilni"), words));
        assertEquals("kys$", ChatFilter.firstMatch(ChatFilter.normalize("just KYS"), words));
        assertEquals(null, ChatFilter.firstMatch(ChatFilter.normalize("kyslik a kyselina"), words));
        assertEquals(null, ChatFilter.firstMatch(ChatFilter.normalize("nadebilovat"), words));
    }

    @Test
    void verdicts() {
        ChatFilter f = filter();
        UUID a = UUID.randomUUID();
        long t = 1_000_000;
        assertEquals(ChatFilter.Verdict.OK, f.check(a, "ahoj, jdeme do endu?", t).verdict());
        assertEquals(ChatFilter.Verdict.SUSPICIOUS, f.check(a, "ty jsi ale debil", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.BLOCKED_WORD, f.check(a, "to je z.a.k.a.z.a.n.e", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.LINK, f.check(a, "koukni na example.com/x", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.OK, f.check(a, "video https://youtube.com/watch?v=1", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.ADVERTISING, f.check(a, "pojdte na play.mujserver.cz", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.ADVERTISING, f.check(a, "ip 123.45.67.89:25565", t += 2000).verdict());
        assertEquals(ChatFilter.Verdict.CAPS, f.check(a, "PROC TO NEFUNGUJE", t += 2000).verdict());
    }

    @Test
    void spamAndRepeats() {
        ChatFilter f = filter();
        UUID a = UUID.randomUUID();
        long t = 5_000_000;
        assertEquals(ChatFilter.Verdict.OK, f.check(a, "kdo jde na nether", t).verdict());
        assertEquals(ChatFilter.Verdict.REPEAT, f.check(a, "kdo jde na nether", t + 1000).verdict());
        UUID b = UUID.randomUUID();
        ChatFilter.Verdict last = null;
        for (int i = 0; i < 7; i++) {
            last = f.check(b, "zprava " + i, t + i * 300L).verdict();
        }
        assertEquals(ChatFilter.Verdict.SPAM, last);
        assertTrue(f.check(UUID.randomUUID(), "x", t).verdict() == ChatFilter.Verdict.OK);
    }

    @Test
    void blockedVsNotBlocked() {
        assertTrue(new ChatFilter.Result(ChatFilter.Verdict.SPAM, "").blocked());
        assertFalse(new ChatFilter.Result(ChatFilter.Verdict.SUSPICIOUS, "").blocked());
        assertFalse(new ChatFilter.Result(ChatFilter.Verdict.CAPS, "").blocked());
    }
}
