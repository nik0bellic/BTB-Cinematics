# Architektur

## Versions- und Kompatibilitätsprüfung (18.11.2026)

Direkt gegen `meta.fabricmc.net` und `maven.fabricmc.net` geprüft:

- Minecraft **26.3** ist ein stabiles Release (15.09.2026); Fabric listet es als unterstützt.
- Fabric Loader **0.20.1** (stable), Fabric API **0.166.2+26.3** (neueste für 26.3).
- **Java 25** ist für 26.3 Pflicht.
- **Keine Yarn-Mappings, Intermediary = 0.0.0**: Seit 26.1 liefert Mojang Minecraft unobfuskiert aus.
  Fabric arbeitet direkt mit Mojangs Klassennamen; Loom läuft ohne Remapping
  (Plugin-ID `net.fabricmc.fabric-loom`, Version 1.19.4, Gradle 9.8.2 – wie in der offiziellen
  `fabric-example-mod`-Vorlage für 26.3).
- Jede verwendete Minecraft- und Fabric-API wurde per `javap` gegen die 26.3-JARs geprüft
  (u. a. `Identifier` statt des alten `ResourceLocation`, das neue Berechtigungssystem
  `Permissions`/`PermissionSet` statt `hasPermission(int)`).

## Grundprinzipien

1. **Server ist die einzige Zeitquelle.** Szenen laufen auf dem Server-Tick. Damit ist jede
   Aufnahme reproduzierbar und alle Spieler/Kameraleute sehen dasselbe zum selben Tick.
2. **Clients präsentieren nur.** Weltänderungen (Blöcke, Teleport, Portale) laufen auf dem Server.
   Clients erhalten *Cues* (Effekte, HUD, Kamera) und ändern nie die Welt.
3. **Strikte Client/Server-Trennung** über Looms `splitEnvironmentSourceSets()`: Client-Code in
   `src/client` kann aus `src/main` nicht referenziert werden – der Dedicated Server crasht nicht.
4. **Fehler früh finden:** Szenen werden beim Laden validiert, nicht erst beim Dreh.
5. **Fehler isolieren:** Ein fehlerhaftes Event, Modul oder Cue wird geloggt und gemeldet, aber
   reißt weder die Szene (bei `on_error: continue`) noch den Server mit.

## Komponenten (Kern)

```
BtbCinematic (Entrypoint)
└── CinematicEngine ── tickt Module in fester Reihenfolge, verdrahtet Fabric-Events
    ├── CinematicBus      interner Publish/Subscribe-Bus zwischen Modulen
    ├── EventManager      Registry der Event-Typen, abgesicherte Ausführung
    ├── SceneManager      lädt JSON, verwaltet laufende ScenePlaybacks
    │   ├── SceneLoader   Parser + Validierung (ohne MC-Abhängigkeit, unit-getestet)
    │   └── ScenePlayback deterministischer Tick-Cursor (unit-getestet)
    ├── [weitere Module ab Phase 2: Camera, Effects, Sound, Showcase, Portal, Destruction]
    └── CueBroadcaster    sammelt Client-Cues pro Szene+Tick, sendet sie am Tick-Ende gebündelt

Client
├── ClientCueDispatcher   empfängt CueBatchPayload, verteilt an registrierte Handler
└── ClientSceneState      Spiegel der Szenenzustände (Pause → Client-Animationen frieren ein)
```

Tick-Reihenfolge pro Server-Tick: `SceneManager` → übrige Module → `CueBroadcaster`.

### Kommunikation zwischen Modulen

- **Timeline → Modul:** Module registrieren Event-Typen beim `EventManager`.
- **Modul ↔ Modul:** über `CinematicBus` (z. B. `SceneMessages.Reset` → Zerstörungsmodul stellt
  Blöcke wieder her) oder direkt über `engine.module(Klasse)`.
- **Server → Client:** `engine.cues().queue(scene, tick, typ, json)`.
- **Commands:** Module implementieren `CommandContributor` und hängen Zweige unter `/btb` ein.

### Netzwerk und Sicherheit

- Ein Payload-Typ `btb_cinematic:cue_batch` (Server → Client) mit harten Größenlimits
  (max. 256 Cues, 16 KB JSON pro Cue, 64 Zeichen Typ).
- Clients ohne Mod werden übersprungen (`ServerPlayNetworking.canSend`).
- Der Director schickt keine eigenen Pakete, sondern normale `/btb`-Befehle. Damit gilt die Rechteprüfung von Vanilla (Level 2).
- Es gibt nur Server → Client-Pakete (`CueBatchPayload`); ein manipulierter Client kann nichts auslösen.
- Das `command`-Event läuft mit Level 2, nicht mit Server-Owner-Rechten.

## Module (Stand 1.0)

Jedes Modul implementiert `CinematicModule` (Lifecycle, Tick, Reset) und hängt seine Befehle über
`CommandContributor` unter `/btb` ein. Kommunikation untereinander nur über den `CinematicBus`.

| Modul | Server | Client |
|---|---|---|
| Timeline / Szenen | Uhr, Events, Beat-Sync | `ClientTimeline` (friert bei Pause ein) |
| Kamera | Pfade, Orbit, Follow, Aufnahme, Presets | `CinematicCameraController` + `CameraMixin` |
| Effekte | `EffectSpec` | `ScreenEffectRenderer` (Fade, Blur via `GameRendererMixin`, Flare mit Verdeckungstest) |
| HUD | `HudTextSpec` | `CinematicHudRenderer`, `FilmOverlays`, `HudMixin` (sichtbar trotz F1) |
| Sound | Sounds, Musik, Hall-Presets | OpenAL-EFX-Hall (`ChannelMixin`), Musik-Crossfade, `MusicManagerMixin` |
| Showcase | Display-Entities mit interpolierten Transformationen | GeckoLib-Modell des Herzens |
| Portale | beliebige Formen (Flood-Fill), Stile, Links, nahtloser Transfer | Blenden |
| Zerstörung | Snapshots (NBT-Backup), Debris als BlockDisplays, Proximity | – |
| Ambiente | Zonen: Sound mit Echo, Partikel, Beben, kreisende Blöcke | – |
| Parkour | Checkpoints, Respawn, Kamera-Umschaltung | – |
| Trigger / Spawn | Zonen (beim Betreten), Interaktion, Gegnerwellen | – |
| Director | Zustand, Demo-Set, `resetall` | `DirectorScreen`, `DirectorHotkeys` (Cue-Liste, Clean Feed), `ObsBridge` |

### Performance
- Pro Tick höchstens ein Netzwerkpaket je Spieler (alle Cues gebündelt).
- Kamera/HUD/Effekte rechnen nur, solange etwas aktiv ist; sonst früher Ausstieg im Mixin.
- Glyphen-Breiten im HUD werden gecacht; Display-Entities statt Partikel-Spam für Trümmer;
  Portal-Ausbreitung und Chunk-Tickets skalieren mit der Größe; Ambiente nur in der Nähe von Spielern.

## Projektstruktur

```
src/main/java/de/behindtheblocks/cinematic/
  BtbCinematic.java  api/ ambience/ camera/ command/ content/ core/ destruction/ director/
  effects/ event/ hud/ json/ network/ parkour/ portal/ scene/ showcase/ sound/ story/ util/ world/
src/client/java/de/behindtheblocks/cinematic/client/
  api/ camera/ director/ effects/ hud/ mixin/ model/ network/ obs/ sound/ timeline/
src/main/resources/
  assets/btb_cinematic/ (Schriften, Texturen, Modelle, Sounds, GeckoLib)  btb_cinematic/default_*/
src/test/java/…   JUnit-Tests (Szenen, Timing, Beat-Sync, Kameramathe, Spec-Validierung)
tools/            Asset- und Sound-Generatoren (Python)
```
