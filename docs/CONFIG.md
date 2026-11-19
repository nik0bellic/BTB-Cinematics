# Konfigurationsdateien

> **Für Fortgeschrittene.** Alle Einstellungen gehen auch ohne Dateien: per Befehl (COMMANDS.md) oder im
> Regiepult (F7). Die Mod schreibt die Dateien dann selbst.

Alles liegt unter `config/btb_cinematic/` (Server bzw. Einzelspieler-Instanz). Beim ersten Start
werden Beispiele kopiert; vorhandene Dateien werden nie überschrieben. `//`-Kommentare sind erlaubt.
Fast alle Dateien entstehen auch per Befehl oder Director-Menü, ihr müsst sie nicht von Hand schreiben.

| Ordner/Datei | Inhalt | Neu laden |
|---|---|---|
| `scenes/<id>.json` | Szenen-Timelines → [SCENES.md](SCENES.md) | `/btb reload` |
| `cameras/<id>.json` | Kamerapfade | `/btb camera reload` |
| `hud/<id>.json` | HUD-Texte, Credits, Buch, Boss-Intro … | `/btb hud reload` |
| `showcases/<id>.json` | Showcases | `/btb showcase reload` |
| `portals/<id>.json` | Portale | `/btb portal reload` |
| `destruction/<id>.json` | Zerstörungsbereiche (Backups: `<welt>/btb_cinematic/destruction/<id>.nbt`) | `/btb destruction reload` |
| `ambience/<id>.json` | Ambiente-Zonen | `/btb ambience reload` |
| `parkour/<id>.json` | Jump'n'Run-Parcours | per Befehl |
| `triggers/<id>.json` | Story-Auslöser | `/btb trigger reload` |
| `director_cues.json` | Cue-Liste + Schnell-Slots (Client) → [DIRECTOR.md](DIRECTOR.md) | automatisch |
| `obs.json` | OBS-Verbindung (Client) → [DIRECTOR.md](DIRECTOR.md) | Neustart |

## Kamera
```json
{ "id": "heart_flyover", "mode": "smooth", "easing": "ease_in_out", "speed": 1.0,
  "points": [ { "pos": [0, 80, -40], "yaw": 0, "pitch": 20, "fov": 70, "roll": 0, "ticks": 100 }, … ] }
```
`ticks` = Fahrzeit vom Punkt zum nächsten. `mode`: linear | smooth | bezier.

## HUD-Texte
```json
{ "style": "text", "lines": ["IF YOU WANT TO LEAVE THIS PLACE,", "FIND THE HEART OF THE NETHER."],
  "font": "cinematic", "scale": 2.0, "color": "#E8D8FF", "glow": "#7A40FF", "effect": "ethereal",
  "animation_in": "blur", "animation_out": "fade", "letter_spacing": 1.2, "line_spacing": 6,
  "in": 50, "hold": 100, "out": 50 }
```
| Feld | Werte |
|---|---|
| `style` | `text, lower_third, dialogue, objective, camcorder, credits, roll, end_card, book, boss_intro` |
| `lines` / `text` | Zeilen bzw. ein Text |
| `font` | `cinematic` (Cinzel), `cinematic_bold`, `default` |
| `anchor` | `center, top, bottom, left, right, top_left, top_right, bottom_left, bottom_right`; `offset [x,y]`, `align` |
| `animation_in` | `none, fade, typewriter, letters, slide_up, slide_down, zoom, tracking, glitch, blur` (`char_ticks` für Schreibmaschine) |
| `animation_out` | `none, fade, slide_up, slide_down, zoom, tracking, glitch` |
| `effect` | `none, wave, pulse, shake, glitch, rainbow, flicker, ethereal` (wabernde Geisterschrift) |
| `color`, `glow`, `accent`, `background`, `shadow` | Farben / an-aus |
| `in`, `hold`, `out`, `delay` | Ticks; `hold: -1` = bis `hud_hide` |
| `title`, `header` | Überschrift (Roll / Buch, Dialog-Name usw.) |
| `above_effects` | Text über Blenden zeichnen (z. B. Titel auf Schwarz) |

Zu lange Zeilen werden automatisch verkleinert, damit nichts aus dem Bild läuft.

### Credits mit Link-Icons
```json
{ "style": "credits", "anchor": "bottom_left", "scale": 2.2, "accent": "#FF7A1A", "entry_ticks": 70,
  "entries": [
    { "heading": "BEHIND THE BLOCKS PRESENTS" },
    { "role": "Musik", "name": "Euer Artist", "icon": "spotify", "link": "open.spotify.com/artist/…" } ] }
```
- `credits`: ein Eintrag nach dem anderen (Vorspann), `entry_ticks` pro Eintrag.
- `roll`: Abspann, scrollt nach oben (`roll_speed`), Einträge mit `heading` sind Zwischenüberschriften.
- `end_card`: großer Schlusstitel mit Link-Zeile.
- Icons: `youtube, spotify, twitch, instagram, tiktok, discord, x, soundcloud, patreon, github, web, mail`.
  Die Dauer von Credits/Roll ergibt sich automatisch aus der Zahl der Einträge.

## Showcase
```json
{ "item": "btb_cinematic:heart_of_the_nether", "pos": [0.5, -54.6, 0.5], "scale": 1.8,
  "animation": "showcase", "glow": "#FF5A00", "light": true, "particles": "minecraft:flame", "background": "ring",
  "satellites": [ { "item": "btb_cinematic:nether_crystal", "count": 4, "radius": 1.5, "speed": 1.6, "scale": 0.55 } ],
  "camera": { "radius": 4.5, "height": 1.2, "degrees": 300, "seconds": 14 }, "name_card": "heart_title" }
```

## Portal
```json
{ "dimension": "minecraft:overworld",
  "from": [10, -60, 0], "to": [12, -57, 0],                          // Rechteck …
  "origin": [10, -60, 0], "axis": "x", "cells": [[0,0,0], …],        // … oder beliebige Form (von /btb portal fill)
  "style": "white", "facing": "south",
  "keystone": [11, -61, 0], "require_key": true, "charge_ticks": 120, "settle_ticks": 20,
  "link": "portal_b",                                                  // oder "exit": {"pos": [x,y,z], "yaw": 0}
  "transition": {"color": "white", "in": 10, "hold": 16, "out": 30},
  "arrival_scene": "", "activation_scene": "" }
```
`axis: "y"` + `facing: "up"` = Boden-Portal (man fällt hinein, beim Partner taucht man oben auf).

## Zerstörung
```json
{ "from": [x,y,z], "to": [x,y,z], "effect": "wall_burst", "duration": 80, "seed": 1234,
  "center": [x,y,z], "debris": 250, "place_lava": true,
  "direction": "south", "proximity": 5.5, "proximity_once": true }
```
Effekte: `collapse, explode, crumble, lava, fire, wall_burst`. Gleicher `seed` = jedes Mal derselbe Ablauf.

## Ambiente-Zone
```json
{ "pos": [x,y,z], "radius": 110, "enabled": true,
  "sound": "btb_cinematic:heart_pulse", "sound_interval": 32, "sound_volume": 2.5, "sound_pitch": 0.75,
  "echo": {"count": 2, "delay": 10, "decay": 0.45},
  "particles": [ {"particle": "dust:#FF2A00", "count": 3, "spread": [6, 1, 6], "speed": 0.02, "y": 0} ],
  "shake_intensity": 0.12, "shake_interval": 32,
  "flash_color": "#40FF2000", "flash_interval": 64,
  "orbit_blocks": [ {"block": "minecraft:magma_block", "count": 10, "radius": 22, "height": 40, "speed": 0.4, "scale": 0.8} ] }
```

## Jump'n'Run
```json
{ "dimension": "minecraft:the_nether", "fall_y": 40, "follow": "Darsteller", "camera_players": ["Director"],
  "checkpoints": [ {"pos": [x,y,z], "radius": 2.5, "camera": {"mode": "side", "distance": 9, "height": 2, "side": "+z"}} ] }
```

## Trigger
```json
{ "type": "interact", "pos": [x,y,z], "once": true, "cancel": true,
  "actions": ["btb hud show book_heart", "btb sound play crystal_shimmer"] }
```
`type: "zone"` + `radius`: feuert beim Betreten (nicht, wer beim Anlegen schon drinsteht).
Trigger werden bei `/btb resetall`, Szenen-Reset und `/btb trigger rearm` wieder scharf.
