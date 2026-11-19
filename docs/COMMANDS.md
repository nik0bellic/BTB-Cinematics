# Befehlsübersicht

Alle Befehle: Operator-Level 2. Tab-Completion schlägt IDs, Farben, Easings, Presets usw. vor.
`[targets]` = Spielerauswahl (`@a`, Name …). Ohne Angabe: bei Kamera der ausführende Spieler,
bei Effekten/HUD/Sound alle Spieler. Fast alles davon geht auch per Klick im Director (F7).

**Farben in Befehlen:** Namen (`white, black, red, dark_red, orange, gold, yellow, green, cyan, blue,
purple, magenta, gray, nether_red, lava, soul, portal`) oder Hex **ohne `#`**: `ff6040`, `80ff6040`
(Brigadier erlaubt `#` in Befehlsargumenten nicht). In JSON-Dateien geht `#ff6040` ganz normal.

## Allgemein
| Befehl | Wirkung |
|---|---|
| `/btb` · `/btb help` | Kurzhilfe |
| `/btb reload` | Szenen neu laden |
| `/btb events` | alle Timeline-Event-Typen mit Parametern |
| `/btb director` · `/btb director refresh` | Regiepult öffnen / aktualisieren |
| `/btb resetall` | ganzes Set zurück: Szenen, Portale, Zerstörung, Showcases, Effekte, Kamera, Sound, gespawnte Gegner, Trigger neu scharf, Parcours gestoppt |
| `/btb demo setup` · `/btb demo remove` | Demo-Set (Herz-Turm) bauen / Original wiederherstellen |
| `/btb film setup` · `/btb film remove` | **Komplettes Film-Set zum Storyboard** (Bastion → Brücken → Wüste → Festung/Turm → Portalhalle → Ausgang) inkl. Szenen `film_01`…`film_07`, `film_vorschau` |

## Szenen
`/btb scene list | status | info <id> | problems | reload | start <id> | restart <id> | stop [id] | pause [id] | resume [id] | reset <id>`

### Szenen bauen (ohne Dateien)
| Befehl | Wirkung |
|---|---|
| `/btb scene create <id> [sekunden]` | neue, leere Szene |
| `/btb scene add <id> <sekunde> <befehl …>` | Schritt hinzufügen (jeder Befehl, z. B. `btb hud text …`); Tippfehler werden sofort gemeldet |
| `/btb scene rec <id>` · `/btb scene rec stop` | **Live-Aufnahme**: alles, was ihr danach klickt/tippt, landet mit genauer Zeit in der Szene |
| `/btb scene events <id>` | nummerierte Liste mit anklickbaren [▶ testen] [✎ Zeit] [✕ löschen] |
| `/btb scene test <id> <nr>` | nur diesen Schritt jetzt ausführen |
| `/btb scene move <id> <nr> <sekunde>` · `shift <id> <nr> <±sekunden>` | Schritt verschieben |
| `/btb scene remove <id> <nr>` | Schritt löschen |
| `/btb scene duration <id> <sekunden>` · `loop <id> on\|off` | Länge / Endlosschleife |
| `/btb scene copy <id> <neu>` · `delete <id>` | kopieren / löschen |

Bei der Live-Aufnahme werden Verwaltungsbefehle (Szenen bearbeiten, Portale/Bereiche anlegen, `resetall` …)
und Navigation (`/tp`, `/gamemode`) nicht mitgeschrieben.

## Kamera
| Befehl | Wirkung |
|---|---|
| `/btb camera create <id>` | neuer Pfad, aktuelle Ansicht = Punkt 1 |
| `/btb camera point <id> [index]` | aktuelle Ansicht anhängen bzw. Punkt ersetzen |
| `/btb camera record <id> [intervall-sek]` | **Kamerafahrt einfach abfliegen** (Spectator), Punkte werden mitgeschrieben |
| `/btb camera record stop` | Aufnahme beenden, Pfad wird geglättet/vereinfacht und gespeichert |
| `/btb camera remove <id> <index>` | Punkt löschen |
| `/btb camera duration <id> <index> <sekunden>` | Fahrzeit vom Punkt zum nächsten |
| `/btb camera easing <id> <easing> [index]` | Easing global oder pro Segment |
| `/btb camera mode <id> linear\|smooth\|bezier` | Interpolation |
| `/btb camera speed <id> <faktor>` | Geschwindigkeit |
| `/btb camera fov <id> <index> <fov>` · `roll <id> <index> <grad>` | Zoom / Neigung pro Punkt |
| `/btb camera info <id>` · `list` · `delete <id>` · `goto <id> <index>` · `reload` | Verwaltung |
| `/btb camera play <id> [targets] [lookat <entity>]` | abspielen, optional auf Spieler/Objekt ausgerichtet |
| `/btb camera stop [targets]` | zurück zum Spielerblick |
| `/btb camera orbit <pos> <radius> <höhe> <sek> [grad] [targets]` | Orbit um Punkt |
| `/btb camera orbit entity <entity> <radius> <höhe> <sek> [grad] [targets]` | Orbit um Spieler/Objekt |
| `/btb camera follow <entity> side\|behind\|fixed\|top [abstand] [höhe] [+x\|-x\|+z\|-z] [targets]` | Verfolgerkamera (weich nachgeführt), z. B. Seitenansicht beim Jump'n'Run |
| `/btb camera auto <entity> [sek] [targets]` | **Auto-Regie**: folgt und wechselt die Perspektive alle n Sekunden (mit harten Schnitten) |
| `/btb camera shake <stärke> <sek> [targets]` | Kamerawackeln |
| `/btb camera zoom <fov> <sek>` · `zoom reset` | Zoom |
| `/btb camera perspective first\|third\|front` | Perspektivwechsel |

Easings: `linear, ease_in, ease_out, ease_in_out, ease_in_out_cubic, ease_out_back, smoother`.

## Effekte
| Befehl | Wirkung |
|---|---|
| `/btb effects fade <farbe> <sek> [halten] [aus]` | Blende (ohne „halten" bleibt sie stehen) |
| `/btb effects fadeout [sek]` | Blende auflösen |
| `/btb effects flash [farbe] [sek]` | Lichtblitz |
| `/btb effects shake <stärke> <sek>` | Kamerawackeln für alle |
| `/btb effects vignette <0..1> [sek]` · `vignette off [sek]` | Vignette |
| `/btb effects blur <0..10> [sek]` · `blur off` | Unschärfe |
| `/btb effects letterbox <0..0.4> [sek]` · `letterbox off` | Kinobalken |
| `/btb effects tint <farbe> <alpha> [sek]` · `tint off` | Farbüberblendung |
| `/btb effects transition <stil> <farbe> <sek>` | `wipe_left/right/up/down, iris_close/open, bars_close/open` |
| `/btb effects flare <pos> [farbe] [stärke]` · `flare off` | Lens Flare auf einen Weltpunkt (leuchtendes Gebäude am Horizont); wird verdeckt, wenn Blöcke davor sind |
| `/btb effects clear [sek]` | alles ausblenden |

## HUD
`/btb hud show <id> [targets]` · `hide [id]` · `list` · `reload` · `subtitle <sek> <text>` · `title <sek> <text>`

| Befehl | Wirkung |
|---|---|
| `/btb hud text <stil> <sek> <text>` | **Text direkt aus dem Chat**, `|` = neue Zeile / „Name \| Untertitel"; 0 Sek. = bleibt stehen |
| `/btb hud save <id> <stil> <sek> <text>` | dasselbe als Vorlage speichern (`/btb hud show <id>`) |
| `/btb hud delete <id>` · `/btb hud styles` | Vorlage löschen / Stile anzeigen |

Stile: `titel, glut, kapitel, untertitel, bauchbinde, dialog, ziel, buch, boss, wabernd, endkarte, camcorder`.

### Credits
| Befehl | Wirkung |
|---|---|
| `/btb hud credits <id> new vorspann\|abspann\|endkarte [titel]` | neue Liste |
| `/btb hud credits <id> add Rolle \| Name \| icon \| link` | Eintrag (Icon/Link optional) |
| `/btb hud credits <id> heading <text>` · `link <icon> <text>` · `title <text>` | Überschrift / reine Link-Zeile / Titel |
| `/btb hud credits <id> list` · `remove <nr>` · `clear` | Einträge verwalten |

Mitgelieferte IDs: `heart_discovery, heart_title, chapter_card, lower_third_hero, dialogue_guardian,
objective_heart, camcorder, credits_opening, credits_roll, end_card, book_heart, boss_intro,
heart_message_ethereal`.

## Sound
| Befehl | Wirkung |
|---|---|
| `/btb sound play <sound> [lautstärke] [pitch]` | z. B. `/btb sound play portal` |
| `/btb sound at <pos> <sound> [lautstärke] [pitch]` | räumlich |
| `/btb sound stop [sound]` | stoppen (mit Fade) |
| `/btb sound music <sound> [fade-sek]` · `music stop [sek]` | Musik mit Crossfade |
| `/btb sound reverb off\|room\|hall\|cave\|arena\|cathedral\|nether [mix]` | Raumhall (OpenAL EFX) |
| `/btb sound volume <0..1>` | Gesamtlautstärke der Cinematic-Sounds |

## Showcase
`/btb showcase create <id> [pos]` · `item <id> <item>` · `animation <id> <preset>` · `scale` · `pos` · `duration` ·
`particles` · `background ring|embers|pillar|aura|none` · `glow <farbe|none>` · `light on|off` · `namecard <hud-id>` ·
`camera <id> <radius> <höhe> <grad> <sek>|off` · `satellite <id> add <anzahl> <radius> <item>` / `clear` ·
`play <id> [preset]` · `stop [id]` · `list` · `info` · `delete` · `reload`

Presets: `float, rotate, reveal, pulse, ascend, explode, showcase`.

## Portale
| Befehl | Wirkung |
|---|---|
| `/btb portal create <id> <from> <to>` | rechteckige Portalfläche |
| `/btb portal fill <id> <innen-pos>` | **beliebige Form**: füllt das Innere eines fertig gebauten Rahmens (Kreis, Bogen, Stern, Boden-Portal …). Rahmen = `portal_frame`/`radiant_frame`-Blöcke |
| `/btb portal style <id> nether\|white` | Nether-Portal oder **weißes episches Portal** |
| `/btb portal build <id>` | Rahmen + Schlüsselstein automatisch bauen (jede Form) |
| `/btb portal keystone <id> <pos>` | Schlüsselstein festlegen (Spieler setzt dort `nether_key` ein) |
| `/btb portal link <id> <ziel> [both]` | Portale einzeln verbinden; `both` = in beide Richtungen |
| `/btb portal exit <id> <pos> <yaw>` | festes Ziel statt Partnerportal |
| `/btb portal facing <id> <north\|south\|east\|west\|up\|down>` | Austrittsrichtung |
| `/btb portal requirekey <id> on\|off` · `arrival <id> <szene\|none>` | Schlüssel nötig / Szene bei Ankunft |
| `/btb portal transition <id> <farbe> <ein> <halten> <aus>` | Blende beim Durchschreiten |
| `/btb portal activationscene <id> <szene\|none>` | Szene, die beim Einsetzen des Schlüssels startet (Kamera kreist, Beben …) |
| `/btb portal activate [id] [instant]` · `deactivate [id]` | Aktivierungssequenz / schließen |
| `/btb portal state <id>` · `list` · `info <id>` · `delete <id>` · `reload` | Verwaltung |

Maximale Fläche: 8192 Blöcke pro Portal.

## Zerstörung
`/btb destruction create <id> <from> <to>` · `effect <id> collapse|explode|crumble|lava|fire|wall_burst|gate` ·
`duration <id> <sek>` · `seed <id> <zahl>` · `center <id> <pos>` · `trigger <id> [effekt]` ·
`restore [id]` · `backup <id>` · `list` · `delete` · `reload`

`proximity <id> <radius>|off` = auslösen, sobald jemand so nah vorbeiläuft (Lava aus der Wand) ·
`direction <id> north|south|east|west|auto` = Seite, aus der `wall_burst` herausbricht.

## Ambiente
`/btb ambience list | reload | on <id> | off <id> | delete <id>`

| Befehl | Wirkung |
|---|---|
| `/btb ambience create <id> <radius>` | Zone um die aktuelle Position |
| `/btb ambience sound <id> <sound> <alle-sek> [lautstärke]` · `sound <id> off` | wiederkehrender Klang (mit Hall) |
| `/btb ambience particles <id> <partikel> <anzahl> [streuung]` · `particles <id> clear` | z. B. `minecraft:ash`, farbiger Staub `dust:ff2a00` |
| `/btb ambience shake <id> <stärke> <alle-sek>` | leichtes Beben |
| `/btb ambience flash <id> <farbe> <alle-sek>` · `flash <id> off` | Lichtpulse |
| `/btb ambience orbit <id> <block> <anzahl> <radius> <höhe>` · `orbit <id> clear` | kreisende Blöcke |

## Jump'n'Run
| Befehl | Wirkung |
|---|---|
| `/btb parkour create <id>` | neuer Parcours in der aktuellen Dimension |
| `/btb parkour checkpoint <id> [radius]` | aktuelle Position als nächsten Checkpoint |
| `/btb parkour camera <id> <nr> side\|behind\|fixed\|top\|none [abstand] [höhe] [seite]` | Perspektivwechsel, sobald der Darsteller diesen Checkpoint erreicht |
| `/btb parkour follow <id> <spieler>` | welcher Darsteller verfolgt wird |
| `/btb parkour cameraman <id> <spieler …>` | wessen Kamera umgeschaltet wird (Director / Kamera-Client). Ist es derselbe Spieler wie `follow`, filmt er sich selbst in der dritten Person |
| `/btb parkour fally <id> <y>` | unter dieser Höhe: Abblende + zurück zum letzten Checkpoint |
| `/btb parkour start <id>` · `stop <id>` · `list` · `delete <id>` | |

Während der Parcours läuft: kein Tod, kein Fallschaden, Lava setzt nur zurück (unsichtbare Feuer-/Schadensresistenz).

## Trigger (Story-Auslöser)
| Befehl | Wirkung |
|---|---|
| `/btb trigger zone <id> <radius>` | Zone um die aktuelle Position; feuert beim **Betreten** |
| `/btb trigger interact <id> <pos>` | feuert beim Rechtsklick auf den Block (z. B. das Buch auf dem Podest) |
| `/btb trigger action <id> <befehl>` | Befehl anhängen (mehrere möglich), z. B. `btb hud show book_heart` |
| `/btb trigger needitem <id> <item> [consume]` · `needitem <id> none` | Schloss: Interact-Trigger nur mit diesem Item in der Hand (z. B. `btb_cinematic:nether_key`) |
| `/btb trigger clear <id>` · `once <id> on\|off` · `enable <id>` · `disable <id>` | |
| `/btb trigger fire <id>` · `rearm` · `list` · `reload` · `delete <id>` | |

## Gegner
`/btb spawn <entity> <anzahl> <pos> <radius> [still] [key] [boss] [glow]`: still = steht still, key = lässt den Netherschlüssel fallen, boss = großer Wächter mit Schlüssel ·
`/btb ghastattack <anzahl> <pos>`: Ghasts am Horizont, feuern 30 s lang harmlose Feuerbälle
`/btb spawn <entity> <anzahl> [pos] [radius]` (Wellen mit Seelenfeuer-Effekt) · `/btb spawn clear`
Entities anderer Mods mit voller ID, z. B. `/btb spawn mutantmonsters:mutant_zombie 1 ^ ^ ^8 0` (Mutant Monsters).
Auf „Friedlich" verschwinden Monster sofort, dafür gibt es eine Warnung (`/difficulty easy`).

## Tasten (Client, frei belegbar unter Steuerung → „BTB Cinematic")
F7 Director · F8 Clean Feed · Nummernblock-Enter = Cue GO · Nummernblock 0 = Cue zurück · Nummernblock 1–9 = Schnell-Slots ·
Nummernblock 1–9 belegen: Regiepult → „Klick → Taste" ·
Nummernblock * halten = Cue-Monitor mit Tastenbelegung zeigen (sonst unsichtbar; pausiert dabei die OBS-Aufnahme)


## Kino-Werkzeuge (neu)
| Befehl | Wirkung |
|---|---|
| `/btb camera handheld <0..2>\|off [ziele]` | Handkamera-Gefühl |
| `/btb camera dollyzoom <motiv> <von> <bis> <sek> [ziele]` | Dolly-Zoom (Vertigo); Richtung = von dir zum Motiv |
| `/btb camera autobeat <spieler> <bpm> <schläge> [ziele]` | Auto-Regie mit Schnitten im Takt |
| `/btb slowmo <0.1..1> <sek>` · `/btb slowmo off` | Zeitlupe für die ganze Welt |
| `/btb effects focus <sek> [stärke] [in\|out]` | Fokus-Zug |
| `/btb effects grain <0..1> [sek]` · `haze <0..1> [sek]` · `… off` | Filmkorn, Hitzeflimmern (Shader) |
| `/btb effects muffle <sek> [stärke]` | dumpfer Ton (Tiefpass auf alle Geräusche) |
| `/btb ambience grade <zone> <farbe> <alpha>` · `ambience haze <zone> <stärke>` | Farbstimmung / Flimmern pro Zone |
| `/btb destruction screenfx <id> on\|off` | Wackeln/Blitz eines Bereichs an/aus |
| `/btb film piece <teil>` · `film remove [teil]` · `film list` · `film chunks on\|off` | Film-Set-Teile |
| `/btb kompass [pos]` | Seelenkompass |

## Runde 8
| Befehl | Wirkung |
|---|---|
| `/btb effects dof auto\|<blöcke> [stärke] [sek]` · `dof off` | Tiefenschärfe |
| `/btb effects anamorph <0..1> [sek]` · `flashback <0..1> [sek]` | Lichtstreifen, Rückblende |
| `/btb effects format <2.39\|2\|1.85\|4x3\|1x1> [sek]` · `format off` | Kinoformat |
| `/btb mark add <name> [farbe]` · `remove` · `clear` · `hide` · `show` · `list` | Crew-Markierungen |
| `/btb wind <richtung\|grad\|~> <0..1>` · `wind off` | Wind für Partikel |
| `/btb ausbruch <pos> [sek] [stärke]` | Lava-Ausbruch |
| `/btb ghastcrash <pos>` | Ghast-Absturz in Blickrichtung |
| `/btb destruction warn <id> <sek>` | Warnung vor dem Einsturz |
| `/btb ambience beatnear <zone> on\|off` | Herzschlag schneller/lauter mit Nähe |

## Bild-im-Bild
| Befehl | Wirkung |
|---|---|
| `/btb pip <darsteller> [tr\|tl\|br\|bl] [0.15–0.5]` · `/btb pip off` | Sicht des Darstellers als Einblendung (zweites Fenster mit Tag `pip` + OBS-Quelle „BTB PiP“) |
| `/btb camera pov <darsteller> [sek] [ziele]` | Sicht-Schnitt durch die Augen des Darstellers |

## Runde 10
| Befehl | Wirkung |
|---|---|
| `/btb effects lut <look\|datei> [stärke] [sek]` · `lut off` | Farbkorrektur (nether, film, kalt, bleach, nacht, glut oder .cube) |
| `/btb camera ego <darsteller> [sek] [ziele]` | Ego-Kamera / Bodycam |
| `/btb scene start\|restart <id> from <sek>` | Szene ab Sekunde X |
| `/btb camera preview <fahrt> [sek]` · `focus <fahrt> <nr> <blöcke>` · `smooth <fahrt> [1–5]` | Fahrt-Vorschau, Schärfe pro Punkt, Glätten |
| `/btb countdown [sek] [szene]` · `/btb probe an\|aus` | Countdown für Darsteller, Probe-Modus |
| `/btb wetter asche\|glutsturm\|klar [stärke] [sek]` · `/btb blutmond an\|aus [sek]` | Nether-Wetter, Blutmond |
| `/btb licht <von> <bis> <0–15> [sek]` | Lampen dimmen |
| `/btb destruction chain <id> <nächste[,…]> <sek>` · `chain <id> off` | Kettenreaktion |

## Replay, Export, Doubles
| Befehl | Wirkung |
|---|---|
| `/btb replay record <name>` · `stop` · `play <name> [from <sek>]` · `list` · `delete` | Replay-Dreh |
| `/btb replay export <name> [4k\|1440p\|1080p] [fps]` | ganzes Replay exportieren, Länge kommt aus der Datei (Standard 1080p, 60 FPS) |
| `/btb hud credits <id> edit <nr> Rolle \| Name \| icon \| link` · `move <nr> up\|down` · `tempo <x>` | Credits ändern, verschieben, Tempo (Abspann: Scrolltempo, Vorspann: Sekunden pro Eintrag) |
| `/btb effects <grain\|haze\|vignette\|blur\|letterbox\|anamorph\|flashback> <stärke> <einblenden> [dauer]` · `tint <farbe> <alpha> <sek> [dauer]` · `lut <look> <stärke> <sek> [dauer]` · `dof <blöcke\|auto> <stärke> <sek> [dauer]` · `format <verhältnis> <sek> [dauer]` | Mit **dauer** endet der Effekt nach so vielen Sekunden von selbst (blendet so lange aus, wie er eingeblendet hat). Ohne dauer bleibt er bis `… off` / Szenenende |
| `/btb film trailer` · `film trailer export [4k\|1440p\|1080p] [fps]` | Trailer (2:05) zum Song `config/btb_cinematic/music/trailer.*` ansehen / mit Ton exportieren |
| `/btb sound music musik.<name> <sek> once` | eigene Musik aus `config/btb_cinematic/music` (einmal abspielen) |
| `/btb waechter angriff schlag\|bruellen\|saeulen\|wurf` | Herzwächter greift auf Kommando an |
| `/btb replay editor [name]` · `edit <name>` · `pause` · `seek <sek>` · `speed <0.1–4>` | Replay-Editor (Timeline, Keyframes, Export; Taste F6) |
| `/btb export <name> <sek> [4k\|1440p\|1080p] [fps] [replay]` · `export stop` | bildgenauer Film-Export (PNG + MP4 mit ffmpeg) |
| `/btb double record <name>` · `double <name> <spieler>` · `double stop` | Doubles |
| `/btb statist <name> [pos]` · `statist clear` · `/btb geste <ziel> <geste> [sek]` | Statisten und Gesten |

## Sprache / Language
| Befehl | Wirkung |
|---|---|
| `/btb sprache` · `/btb language` | zeigt die aktuelle Einstellung |
| `/btb sprache <auto\|de\|en>` | `auto`: jeder Spieler sieht die Mod in seiner Minecraft-Sprache (Deutsch → Deutsch, sonst Englisch). `de`/`en`: für alle fest. Wird in `config/btb_cinematic/settings.json` gespeichert und sofort an alle Spieler geschickt. |

## Studio (neu)
| Befehl | Wirkung |
|---|---|
| `/btb stop` | alles stoppen, was läuft (Szenen, Kamera, Effekte, Zeitlupe, Ton, Dialog) – das Set bleibt |
| `/btb undo` · `/btb rueckgaengig` | letzte Änderung an Szenen, Pfaden, Texten, Credits, Dialogen … zurücknehmen |
| `/btb versionen [datei]` · `/btb versionen <datei> wiederherstellen "<version>"` | automatische Versionen (`config/btb_cinematic/.versionen`, je Datei die letzten 25) |
| `/btb projekt export <name>` · `import <name>` · `liste` | ganzes Projekt als zip (`config/btb_cinematic/projekte`) – ohne OBS-Passwort |
| `/btb vorlage <name> [darsteller] [szene]` | fertige Shots: establishing, verfolgung, dialog, enthuellung, heldenshot, ueberflug, auto. Mit `szene` nur anlegen |
| `/btb welt zeit <morgen\|mittag\|abend\|nacht\|0–24000> [sekunden]` | Tageszeit, mit Sekunden als weicher Übergang (nur Oberwelt) |
| `/btb welt wetter <klar\|regen\|gewitter>` | Wetter (nur Oberwelt) |
| `/btb gruppe <name> spawn <typ> <anzahl> [radius]` | Statisten-Gruppe (kämpfen nicht, bewegen sich nur auf Kommando) |
| `/btb gruppe <name> laufen <pos> [tempo]` · `fliehen` · `jubeln [s]` · `schauen <pos>` · `stehen` · `weg` | die ganze Gruppe auf einmal |
| `/btb dialog <id> add <sek> <dauer> Sprecher \| Deutsch \| English` | Dialogzeile; Sprecher = Name eines Statisten → bewegt Kopf/Arme beim Sprechen |
| `/btb dialog <id> play` · `srt` · `shift <nr> <s>` · `remove <nr>` · `clear` · `delete` · `/btb dialog stop` | abspielen (jeder in seiner Sprache), `.srt` für den Schnitt (`btb_export/`) |
| `/btb rolle <spieler> <regie\|kamera\|darsteller\|aus>` | Rollen; „kamera“ = Tag `kamera` (Kamerafahrten laufen dort) |
| `/btb hinweis <spieler\|darsteller> <vorlauf> <text>` | Souffleur: „In 3 … 2 … 1: Text“ bei den Darstellern |
| `/btb camera preview <pfad> an` · `/btb camera preview off` | Pfad als Linie in der Welt, live (Änderungen sofort sichtbar) |
| `/btb scene play <id>` | wie `restart` |

Tippfehler: Die Mod sagt jetzt, was fehlt oder falsch ist, und schlägt das nächstliegende Wort vor („meintest du …?“).

Replay-Editor: **Kamera A–D** (jede mit eigenen Keyframes) und **✂ → A** (Schnitt an der Abspielposition). Die Schnitte erscheinen als farbiges Band über der Zeitleiste.
