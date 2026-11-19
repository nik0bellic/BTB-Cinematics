# Euer Storyboard → Werkzeuge

Für jeden Moment eurer Timeline: was ihr dafür nehmt. Alles lässt sich als Szene (JSON),
per Befehl, per Director-Knopf oder als Cue (Nummernblock-Enter) auslösen.

| # | Moment | Werkzeug |
|---|---|---|
| 0 | **Vorspann mit Credits** | `hud_show credits_opening` (Einträge mit Spotify-/YouTube-Icons), Szene `opening` |
| 1 | Flug über die Bastion | `/btb camera record bastion_fly` → Spectator abfliegen → `record stop`; in der Szene `camera_play` + `letterbox` |
| 2 | Buch: „If you want to leave this place, find the Heart of the Nether" | `trigger interact` auf den Block mit dem Buch → `btb hud show book_heart` (Buchseite, Text erscheint wie Tinte) |
| 3 | **Beat Drop**, Gegner tauchen auf | Szene mit `bpm`; `spawn_wave` mit `"bar": 8` → exakt auf dem Drop; dazu `screen_flash`, `camera_shake` |
| 4 | Flug über brennende Brücken | Kamerapfad + `destruction` mit Effekt `fire` (Brücke brennt reproduzierbar, `restore` setzt zurück) |
| 5 | **Lava bricht durch Wände** | `destruction` Effekt `wall_burst` + `proximity`: bricht auf, sobald jemand vorbeiläuft |
| 6 | Lava-Wüste, Seelensand, Ghasts | `ambience`-Zone (Partikel, Hall, Grollen), `reverb nether`; `fireball`-Event (Ghast-Feuerball, zerstört nichts) |
| 7 | **Jump'n'Run mit Perspektivwechseln** | `/btb parkour …`: Checkpoints mit `side`/`behind`/`top`-Kamera, Fallen = Abblende + zurück zum Checkpoint, kein Tod |
| 8 | Die Botschaft taucht wabernd wieder auf | `hud_show heart_message_ethereal` (Effekt `ethereal`) |
| 9 | Leuchtendes Gebäude am Horizont | `lens_flare` auf den Gebäudepunkt + `ambience` mit `flash_color` |
| 10 | Festung, **Boss** | eigener Boss **Herzwächter** (`/btb spawn btb_cinematic:herzwaechter …` oder Regiepult → Action), `hud_show boss_intro`, `music_play` |
| 11 | Schlüssel | Item `nether_key`: der Herzwächter lässt ihn beim Tod fallen (auch `/give`) |
| 12 | Tor | `destruction` (`crumble`/`explode`) oder `portal_frame`-Blöcke als Tor |
| 13 | Halbfertiges großes Portal, Schlüssel einsetzen | Portal mit `keystone` + `require_key`: Rechtsklick mit `nether_key` startet die Aktivierung |
| 14 | Portal aktiviert sich, **Kamera kreist, alles bebt** | Szene im Baukasten live aufnehmen (Orbit, Shake, Blitz) und `/btb portal activationscene <portal> <szene>`; weißes Portal: `style white` |
| 15 | Hineinlaufen, **Weißblende** | `transition` des Portals (`white`, ein/halten/aus) |
| 16 | Aus einem anderen Portal kommen | `/btb portal link a b both`; beliebige Form (`/btb portal fill`), auch Boden-Portal |
| 17 | Überall Gegner | `arrival_scene` am Zielportal → `spawn_wave` |
| 18 | **Schwarzblende** | `screen_fade black` mit `hold: -1` |
| 19 | **Ending** | Szene `ending`: `credits_roll` (Abspann), `end_card` „TO BE CONTINUED" mit Links |

## Der Herz-Turm

`/btb demo setup` baut den Turm des Herzens (80 Blöcke hoch, Lava-Graben, Brücke, pulsierende
Adern, Knochen-Stacheln, kreisende Trümmer, Lava-Wände am Weg, Ruine) mit Herz-Showcase und
Szene `heart_discovery`. `/btb demo remove` stellt das Gelände wieder her.

## Für die Aufnahme

Siehe [DIRECTOR.md](DIRECTOR.md): Cue-Liste, Clean Feed, OBS-Pause während das Menü offen ist.
