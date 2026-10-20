package cz.forgebound.guard;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config, plugin.yml and the AI answer parser. */
class GuardTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String name) throws Exception {
        return (Map<String, Object>) new Yaml().load(Files.readString(Path.of("src/main/resources", name)));
    }

    @Test
    void configHasEverySection() throws Exception {
        Map<String, Object> config = yaml("config.yml");
        for (String key : List.of("enabled", "filter", "strikes", "ai", "watchdog", "log-bridge", "auto-update", "messages")) {
            assertTrue(config.containsKey(key), "config.yml is missing " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) config.get("ai");
        assertEquals(Boolean.FALSE, ai.get("enabled"), "the AI must be off until someone adds a key");
        assertEquals("", ai.get("api-key"), "never ship a key");
    }

    @Test
    void pluginDescription() throws Exception {
        Map<String, Object> meta = yaml("plugin.yml");
        assertEquals("ForgeboundGuard", meta.get("name"));
        String main = String.valueOf(meta.get("main"));
        assertNotNull(getClass().getClassLoader().getResource(main.replace('.', '/') + ".class"), "main class missing");
        @SuppressWarnings("unchecked")
        Map<String, Object> perms = (Map<String, Object>) meta.get("permissions");
        for (String p : List.of("guard.admin", "guard.notify", "guard.bypass")) {
            assertTrue(perms.containsKey(p), "missing permission " + p);
        }
    }

    @Test
    void aiVerdictsAreRead() {
        String response = "{\"content\":[{\"type\":\"text\",\"text\":\"Here: {\\\"verdicts\\\":["
                + "{\\\"id\\\":3,\\\"action\\\":\\\"warn\\\",\\\"reason\\\":\\\"urazka\\\"},"
                + "{\\\"id\\\":4,\\\"action\\\":\\\"delete_server\\\"}]}\"}]}";
        var verdicts = ModerationManager.parse(response);
        assertEquals(2, verdicts.size());
        assertEquals(3, verdicts.get(0).id());
        assertEquals("warn", verdicts.get(0).action());
        assertEquals("urazka", verdicts.get(0).reason());
        assertEquals("none", verdicts.get(1).action(), "unknown actions are ignored");
        assertTrue(ModerationManager.parse("not json").isEmpty());
        assertFalse(ModerationManager.parse("{\"content\":[]}").size() > 0);
    }

    @Test
    void time() {
        assertEquals("5:00", ForgeboundGuard.time(300_000));
        assertEquals("1:02:03", ForgeboundGuard.time(3_723_000));
    }

    @Test
    void ipAddressesAreHidden() {
        assertEquals("Pepa[/185.12.x.x] logged in", LogBridge.hide("Pepa[/185.12.33.4:51234] logged in"));
        assertEquals("no ip here 1.21.11", LogBridge.hide("no ip here 1.21.11"));
    }

    @Test
    void logBridgeShipsWithoutAToken() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> bridge = (Map<String, Object>) yaml("config.yml").get("log-bridge");
        assertEquals(Boolean.FALSE, bridge.get("enabled"));
        assertEquals("", bridge.get("token"));
    }

    @Test
    void dailyFileNames() {
        assertEquals(java.time.LocalDate.of(2026, 10, 20), LogBridge.day("2026-10-20.log"));
        assertEquals(null, LogBridge.day("moderation.log"));
        assertEquals(null, LogBridge.day("2026-10-20.txt"));
    }

    @Test
    void versions() {
        assertEquals("1.2.0", Updater.version("v1.2.0-build.4"));
        assertTrue(Updater.compare("v1.2.0-build.4", "1.1.1") > 0);
        assertEquals(0, Updater.compare("v1.1.1-build.3", "1.1.1"));
    }
}
