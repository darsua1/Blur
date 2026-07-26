<div align="center">

# BLUR

**A CrystalPvP performance tracker for Minecraft.**

Measures your crystal speed, anchor speed and totem trades in real time, and streams
them to a small floating desktop widget.

[blurstats.com](https://blurstats.com)

</div>

---

## What it does

Blur is two pieces that talk to each other over localhost:

- **The mod** (Fabric, client-side) does the measuring. Only in-game code can accurately
  tell that you placed an End Crystal, detonated a respawn anchor, or forced a totem.
- **The app** (a single ~1.8 MB `.exe`, no installer, no extra files) does the display.
  Keeping the UI in a separate process means the graphs and history cost your game nothing.

The mod is **read-only**. It observes events and never automates, injects, or modifies
anything — the same category as a CPS counter.

### Tracked

| | |
|---|---|
| **Crystals** | placements per 1s / 5s, session + all-time bests |
| **Anchors** | detonations per 1s / 5s (charging clicks excluded) |
| **Totems** | forced out of opponents vs. burned by you, biggest combo |
| **Duels** | auto-detected opponent, per-duel summary, searchable history |

---

## Supported versions

| Minecraft | Jar |
|---|---|
| 1.21.11 | `blur-1.0.0+1.21.11.jar` |
| 26.1 | `blur-1.0.0+26.1.jar` |
| 26.1.1 | `blur-1.0.0+26.1.1.jar` |
| 26.1.2 | `blur-1.0.0+26.1.2.jar` |
| 26.2 | `blur-1.0.0+26.2.jar` |

Each jar pins `"minecraft": "=<version>"`, so the wrong jar simply refuses to load
rather than breaking your game.

---

## Repo layout

```
src/                 mod source — shared by both builds
  main/              common entrypoint
  client/            all the real logic (measurement, HTTP server, mixin)
  compat-1211/       1.21.11 API bridge
  compat-26x/        26.x API bridge
mod-1.21.11/         Gradle project: obfuscated build
mod-26x/             Gradle project: unobfuscated build (26.1 – 26.2)
build.ps1            builds the jars and packages them
```

> The desktop app's source is **not published**. The mod in this repo is complete and
> builds on its own; the app ships as a prebuilt binary from
> [blurstats.com](https://blurstats.com).

### Why two Gradle projects

Minecraft **26.x ships unobfuscated**, which changes the build fundamentally:

| | 1.21.11 | 26.x |
|---|---|---|
| Loom plugin | `fabric-loom-remap` | `fabric-loom` |
| Mappings | `officialMojangMappings()` | none — not needed |
| Dependencies | `modImplementation` | `implementation` |
| Java | 21 | 25 |
| Remap step | yes | no |

The **source is identical** across all five versions except two APIs, which are isolated
in a single `Compat` class per family:

| | 1.21.11 | 26.x |
|---|---|---|
| Keybind registration | `KeyBindingHelper.registerKeyBinding` | `KeyMappingHelper.registerKeyMapping` |
| Chat / action bar | `Player.displayClientMessage(c, bool)` | `sendSystemMessage(c)` / `sendOverlayMessage(c)` |

No preprocessor and no reflection — each build just compiles the matching `compat-*`
directory, so both stay fully type-checked.

---

## Building

**Requirements:** JDK 21 and JDK 25 (26.x targets Java 25).

All five versions at once:

```powershell
.\build.ps1
```

That produces every jar and packages them into `Blur-Pack/mods/`.

Individually:

```powershell
# 1.21.11
cd mod-1.21.11; .\gradlew build

# any 26.x version
cd mod-26x; .\gradlew build -Pminecraft_version=26.2 -Pfabric_api_version=0.155.2+26.2
```

Fabric API versions per target live in `build.ps1`; new ones can be found in
[Fabric's maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml).

---

## How it works

```
Minecraft ──► mod ──► localhost HTTP + SSE ──► desktop app
                │                                   │
       records.json                          history.json
   (.minecraft/config/blur)                (Documents/Blur)
```

The mod binds the first free port in `7896–7907` on `127.0.0.1` only and pushes a JSON
snapshot ten times a second. The app probes that range to find the game. Because the
wire format is version-independent, **one app build works with every supported
Minecraft version**.

Detection, in short:

- **Crystals** — a right-click holding an End Crystal is correlated with the
  `EndCrystal` entity that then spawns (matched on position and time), so only *your*
  crystals count, not your opponent's.
- **Anchors** — only genuine detonations: charged anchor, in a dimension where it
  explodes, on a click that isn't adding glowstone.
- **Totems** — read from the entity-event packet (id 35) at the packet listener, which
  survives the class-hierarchy changes between versions.

---

## Support

In game, press `\` to write `.minecraft/logs/BlurLog.txt` — a plain-English report
covering whether the mod loaded, whether the app is connected, whether records are
saving, and any errors, plus your installed mods for conflict hunting.

---

## Licence

**Source-available, not open source.** See [LICENSE.md](LICENSE.md).

You may **use** Blur, **read and learn from** the source, **build** it for yourself,
and **modify it privately**. You may **not** redistribute it (in any form, modified
or not) or sell it. Applying what you learn here in your own independently written
code is expressly fine.

Third-party components and their licences: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Not affiliated with Mojang Studios or Microsoft.
