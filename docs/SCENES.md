# Szenen-Dateien

> **Szenen müsst ihr nicht von Hand schreiben:** Regiepult → Baukasten bzw. `/btb scene create/add/rec`
> (siehe TUTORIAL.md, Kapitel 5). Diese Seite beschreibt das Dateiformat für Fortgeschrittene.

Ort: `config/btb_cinematic/scenes/<id>.json` (Server bzw. Einzelspieler-Instanz).
Nach Änderungen: `/btb reload`. Laufende Szenen behalten ihre alte Version bis zum Neustart.
`//`-Kommentare sind erlaubt. Mitgeliefert: `core_demo`, `heart_discovery`, `opening`, `ending`.

```json
{
  "id": "portal_activation",
  "duration": 300,
  "loop": false,
  "on_error": "continue",
  "strict": false,
  "bpm": 128, "beat_offset": 0, "beats_per_bar": 4,
  "events": [
    { "time": 0,  "type": "log", "message": "los" },
    { "bar": 8, "beat": 0, "type": "spawn_wave", "entity": "piglin_brute", "count": 8, "pos": [0, 70, 0] }
  ]
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | nein | `[a-z0-9_]`, Standard = Dateiname |
| `duration` | ja | Länge in Ticks (20 Ticks = 1 s), max. 144 000 |
| `loop` | nein | Nach Ende von vorn beginnen |
| `on_error` | nein | `continue` (Standard) oder `stop`, wenn ein Event zur Laufzeit fehlschlägt |
| `strict` | nein | `true`: jedes ungültige Event verwirft die ganze Szene |
| `bpm`, `beat_offset`, `beats_per_bar` | nein | **Beat-Sync**: Events dürfen statt `time` `beat` und/oder `bar` angeben |
| `events` | nein | Liste von Events mit `time` (oder `beat`/`bar`) und `type` |

## Zeitmodell

- `time` in Ticks ab Szenenstart. Mit Beat-Sync: `time = beat_offset + (bar·beats_per_bar + beat) · 1200 / bpm`
  (gerundet). So landet der „Beat Drop" exakt auf dem Takt der Musik: `beat_offset` = Tick, an dem
  Takt 0 der Musik beginnt.
- Events im selben Tick laufen in Dateireihenfolge.
- Alle Client-Effekte eines Ticks werden als **ein** Paket gesendet und im selben Frame angewendet.
- Pause friert die Szenenuhr ein (auch Kamerafahrten und HUD-Animationen); nach Resume geht es exakt weiter.

## Validierung

Events werden beim Laden geprüft. Unbekannte Typen, fehlende Parameter oder Zeiten außerhalb
`0..duration` erzeugen eine Warnung, das Event wird übersprungen (`/btb scene problems`).
`command`-Events werden beim Ausführen geparst: Tippfehler werden als Fehler gemeldet statt still ignoriert.

## Alle Event-Typen

`players` (wo vorhanden): `"all"` (Standard) oder Liste von Spielernamen. Zeiten in Ticks.
Farben: Name oder `"#RRGGBB"` / `"#AARRGGBB"`.

### Ablauf
| Typ | Parameter |
|---|---|
| `command` | `command`, `silent` (Standard `true`), `as?` (Spieler: „für mich"/`@s`), `pos? [x,y,z]`, `rot? [yaw,pitch]` (für `~`/`^`) – Server-Befehl mit Level 2 (kein `/stop`, `/op`). So speichert der Baukasten jeden Schritt; ist der Spieler offline, wird der erste Spieler genommen |
| `message` | `text`, `actionbar?`, `players?` |
| `teleport` | `pos [x,y,z]`, `yaw?`, `pitch?`, `dimension?`, `players?` |
| `scene_start` / `scene_stop` | `scene` (bei stop optional) – Szenen verketten |
| `log` | `message` |
| `client_cue` | `cue` (namespace:name), `data?`, `players?` – für eigene Overlays anderer Mods, z. B. `btb_cinematic:obs` mit `{"action":"start"}` |

### Kamera
| Typ | Parameter |
|---|---|
| `camera_play` | `path`, `look_at?` (Spieler oder `[x,y,z]`), `speed?`, `carry?`, `hide_hud?`, `players?` |
| `camera_orbit` | `center [x,y,z]` \| `target` (Spieler), `radius`, `height`, `seconds`\|`ticks`, `degrees?`, `start_angle?`, `radius_end?`, `height_end?`, `easing?`, `fov?`, `players?` |
| `camera_follow` | `target`, `mode` side\|behind\|fixed\|top, `distance?`, `height?`, `side?` (+x/-x/+z/-z), `pos?` (fixed), `smoothing?`, `ticks?` (-1 = bis stop), `players?` |
| `camera_stop` | `players?` |
| `camera_shake` | `intensity` 0..5, `duration?`, `frequency?`, `players?` |
| `camera_zoom` | `fov` (0 = normal), `duration`, `easing?`, `players?` |
| `camera_perspective` | `mode` first\|third\|front, `players?` |

### Bildschirm
| Typ | Parameter |
|---|---|
| `screen_fade` | `color`, `duration`, `hold?` (-1 = stehen lassen), `out?`, `intensity?` |
| `screen_flash` | `color?`, `duration?`, `intensity?` |
| `vignette` · `blur` · `letterbox` | `intensity`, `in?`, `hold?`, `out?` (vignette zusätzlich `color?`) |
| `screen_tint` | `color`, `color2?`, `intensity`, `in?`, `hold?`, `out?` |
| `transition` | `style` wipe_left\|wipe_right\|wipe_up\|wipe_down\|iris_close\|iris_open\|bars_close\|bars_open, `color?`, `in`, `hold?`, `out?` |
| `lens_flare` | `pos [x,y,z]`, `color?`, `intensity` 0..1, `in?`, `hold?`, `out?` |
| `effects_clear` | `style?`, `duration?` |

### HUD
| Typ | Parameter |
|---|---|
| `hud_show` | `id` (aus `config/btb_cinematic/hud`) **oder** Inline-Felder (siehe CONFIG.md), `players?` |
| `hud_hide` | `id?` (leer = alle), `out?` |
| `subtitle` | `text`, `duration?` |

### Sound
| Typ | Parameter |
|---|---|
| `sound` | `sound`, `pos?` \| `at?` (Spieler), `volume?`, `pitch?`, `fade_in?`, `echo? {count, delay, decay}` |
| `music_play` | `sound`, `volume?`, `fade_in?`, `loop?` (Crossfade vom alten Stück) |
| `music_stop` | `fade_out?` |
| `sound_stop` | `sound?`, `fade?` |
| `reverb` | `preset` off\|room\|hall\|cave\|arena\|cathedral\|nether, `mix?` |
| `sound_volume` | `volume`, `fade?` |

### Welt & Story
| Typ | Parameter |
|---|---|
| `showcase_play` · `showcase_animation` · `showcase_stop` | `id`, `animation?` |
| `portal_activate` | `id`, `instant?` (Schlüssel wird übersprungen) |
| `portal_deactivate` | `id?` |
| `portal_transfer` | `id`, `players?` – Spieler „durchs Portal schneiden", ohne hineinzulaufen |
| `destruction_trigger` | `id`, `effect?` |
| `destruction_restore` | `id?` |
| `ambience` | `id`, `enabled` |
| `spawn_wave` | `entity`, `count`, `pos`, `radius?`, `interval?`, `effect?` soul\|smoke\|portal\|none, `seed?`, `glowing?` |
| `fireball` | `from`, `to` \| `target`, `speed?` – Ghast-Feuerball, der nie Blöcke zerstört |
| `parkour_start` · `parkour_stop` | `id` |

Eigene Event-Typen anderer Mods: siehe [API.md](API.md).
