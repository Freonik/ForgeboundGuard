# Forgebound Guard

Chat moderation and a server watchdog for **Paper 1.21.11** (Java 21).
A companion to [Forgebound](https://github.com/Freonik/Forgebound), but it works on any Paper server on its own.

- [Installation](#installation)
- [Chat filter](#chat-filter)
- [AI moderator](#ai-moderator)
- [Strikes and mutes](#strikes-and-mutes)
- [Watchdog](#watchdog)
- [X-ray hints](#x-ray-hints)
- [Log bridge](#log-bridge)
- [Commands and permissions](#commands-and-permissions)
- [License](#license)

---

## Installation

1. Download `ForgeboundGuard.jar` from the [**latest release**](../../releases/latest).
2. Put it into `plugins/` and restart the server.
3. Settings are in `plugins/ForgeboundGuard/config.yml`. After a change: `/guard reload`.

**Auto-update:** every 6 hours Guard checks for a newer release, downloads it into `plugins/update/` and tells the
admins; it's used after the next restart. Turn it off with **`/guard update off`** (`/guard update now` checks right away).

> **Coming from Forgebound 2.5?** The moderation used to be part of Forgebound. On its first start, Guard takes over
> your settings (including the API key), active mutes and `moderation.log` from `plugins/Forgebound/`.
> Update Forgebound to 2.6+, which no longer has the moderation, so the chat isn't checked twice.

---

## Chat filter

Free and always on (`enabled: false` turns everything off). It blocks:

- **spam**: more than 5 messages in 8 seconds,
- **repeats**: the same message again within 30 seconds,
- **links**, except allowed sites (YouTube, Twitch, Imgur, the Minecraft Wiki by default),
- **ads**: IP addresses and `play.something.cz`-style server addresses,
- **blocked words** from your list (`filter.blocked-words`).

CAPS messages are turned into lowercase. The filter sees through tricks like `d.e.b.1.l`, `d e b i l` or `dÉÉbiiil`.
Every rule and number can be changed in the config.

## AI moderator

Optional. Messages that contain a **suspicious** word (`filter.suspicious-words`, a list of insults and worse in Czech,
Slovak and English) are sent to **Claude** (Anthropic API), together with the last few chat lines as context.
They're collected and sent in small batches every 20 seconds, so it stays cheap: everyday chat never leaves the server.

The AI decides whether it's banter between friends or a real insult, and can:

| Action | What happens |
|---|---|
| none | nothing |
| warn | the message disappears from chat, the player gets a warning and a strike |
| mute | a mute right away (for hate speech, threats, harassment) |
| suggest a ban | **never automatic**: admins get a message with a clickable `/ban` |

`ai.max-action` limits what it may do on its own (`mute`, `warn` or just `notify` the admins).
`ai.rules` adds your own server rules in plain words.

**Turning it on**
1. Create an API key at [console.anthropic.com](https://console.anthropic.com) (prepaid credit, no subscription).
2. Put it into `ai.api-key` (or set the `ANTHROPIC_API_KEY` environment variable on the server) and run `/guard ai on`.
3. Never share the config with the key in it. Set a monthly spending limit in the Anthropic console.

Players get a short notice on join that the chat is checked by an AI (`ai.join-notice`). Tell them in your rules too.
`/guard ai off` switches the AI off again; the free filter keeps running.

## Strikes and mutes

- Each warning is a **strike**; **3 strikes** within an hour = a mute.
- Mutes get longer: **5 → 15 → 60 minutes**.
- Muted players can't use `/msg`, `/tell`, `/r`, `/me` and similar either.
- Everything is written to `plugins/ForgeboundGuard/moderation.log`.

## Watchdog

Admins with `guard.notify` are told in chat when:

- **TPS** drops below 15, with the chunk that has the most entities and a click-to-teleport,
- **memory** is more than 90 % full,
- a **plugin throws an error** (with its name and the error).

Each kind of alert comes at most once every 5 minutes.

Every hour (5 minutes after start the first time) the console also gets a `[Perf]` line: TPS, MSPT, the worst
tick, RAM, players, loaded chunks and entities per world. The TPS, RAM and player numbers include the worst
values since the previous line. Low TPS and full memory are written to the console as well.
Set `watchdog.perf-log-minutes` (0 = off).

## X-ray hints

Guard counts the **diamond and ancient debris veins** a player finds against the plain stone, deepslate and
netherrack they mine. With x-ray you dig straight to the ores, so you find many veins for very little stone.
When it's clearly too many (6 diamond veins with less than 80 stone each, 5 debris veins with less than 100,
within 45 minutes; all in the config), admins with `guard.notify` get a message: hover shows the finds with
coordinates, click teleports to the player. Every find is written to `mining.log`.

Nothing is punished automatically: mining in caves or blowing up debris with TNT/beds can look the same, so it's
a hint to have a look, not a verdict. Ores placed by players don't count.

**`/guard check <player>`** shows where the player is (click to teleport), health, game mode, ping, how many
diamonds and netherite they carry (and in their ender chest), what they mined since the restart with the last finds,
and whether they're muted. For offline players: when they were last online.

**Recommended together with:**
- Paper's built-in anti-xray: `config/paper-world-defaults.yml` → `anticheat.anti-xray.enabled: true`
  (hides ores the player can't see),
- [Grim Anticheat](https://modrinth.com/plugin/grimac) for movement and combat cheats. Its alerts go to the
  console, so the log bridge sends them to GitHub as well.

## Log bridge

Sends the server console log and `moderation.log` to a **private GitHub repository** every 5 minutes, so you
(or Claude, when you ask it) can read what happened without access to the server. It's free: only the GitHub API
is used. Player IP addresses are hidden (`185.12.x.x`).

In the repository: `console/2026-10-20.log` (one file per day), `moderation.log` and `mining.log`. Daily files are kept for
7 days on the server and 30 days on GitHub (`keep-days-server`, `keep-days-github`).

**Setup**
1. Create a **private** repository, e.g. `ForgeboundGuard-logs`.
2. GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token:
   *Repository access* → **Only select repositories** → that repository; *Permissions* → **Contents: Read and write**.
3. In `config.yml`: `log-bridge.enabled: true`, the `token` and `repo: "YourName/ForgeboundGuard-logs"`.
4. `/guard reload`, then `/guard logs now` to test it. `/guard` shows when it last worked.

Never share the config with the token in it.

---

## Commands and permissions

| Command | Description |
|---|---|
| `/guard` | status: filter, AI, watchdog, how many players are muted |
| `/guard ai on\|off` | switch the AI moderator on or off |
| `/guard ai test <message>` | ask the AI about a message right now (nobody is punished): checks the key and how strict it is |
| `/guard check <player>` | where the player is, what they carry and mined, mutes |
| `/guard mute <player> [minutes]` | mute a player (10 minutes by default) |
| `/guard unmute <player>` | unmute a player and clear their strikes |
| `/guard modlog [player]` | the last 10 moderation actions |
| `/guard update on\|off\|now` | auto-update on/off, or check for a new version now |
| `/guard logs now` | send the logs to GitHub right now |
| `/guard reload` | reload the config |

| Permission | Default | |
|---|---|---|
| `guard.admin` | op | all `/guard` commands |
| `guard.notify` | op | see moderation actions and watchdog alerts |
| `guard.bypass` | nobody | chat isn't checked (give it to staff if you want) |

---

## License

**All rights reserved.** You may run the released plugin on your own server and change its config;
anything else needs permission. See [LICENSE](LICENSE).

The AI moderator sends suspicious chat messages to Anthropic with your own API key; let your players know.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
