# Teststand 1.0.0 (18.11.2026)

Ehrlich aufgeschlüsselt: was im echten Spiel geprüft wurde, was nur kompiliert/unit-getestet ist
und was gar nicht getestet werden konnte. Testumgebung: Minecraft 26.3-Dev-Client ohne GPU
(Software-Rendering, Mesa/EGL), ein Spieler, Einzelspielerwelt, plus ein dedizierter Server.

## Im Spiel geprüft (mit Screenshots in `screenshots/`)

| System | Geprüft |
|---|---|
| Szenen | Start, Pause (Uhr friert ein, auch Kamera/HUD), Resume, Reset, Fehlerausgabe |
| Kamera | Pfade (linear/smooth/bezier), Orbit, Shake, Zoom, Perspektive, **Follow** (side/behind/top), **Pfad-Aufnahme** + Wiedergabe |
| Effekte | Fade, Flash, Vignette, Blur, Letterbox, Tint, Iris/Wipe/Bars, **Lens Flare** inkl. Verdeckung |
| HUD | Text-Animationen, Lower Third, Dialog, Objective, Camcorder, **Vorspann-Credits mit Icons**, **Abspann**, **End-Card**, **Buch** (Tinten-Effekt), **Boss-Intro**, **wabernde Botschaft**; sichtbar trotz F1 |
| Sound | eigene Sounds, OpenAL-Hall (gemessen: Nachhall 0,15 s → 2,3 s), Echo |
| Showcase | Herz-Modell (GeckoLib, Glowmask), alle Presets, Satelliten, Kamera |
| Portale | Schlüssel → Aktivierung → nahtloser Transfer; 40×24-Portal; **weißes Ring-Portal (119-Block-Rahmen, Flood-Fill)**; **Boden-Portal**; **Verbindung Ring → Boden** |
| Zerstörung | collapse, explode, wall_burst mit Proximity (Lava aus der Wand beim Vorbeilaufen), Restore |
| Ambiente | Herz-Turm: Pulsieren, Partikelsäule, rote Blitze, kreisende Trümmer |
| Jump'n'Run | Checkpoints, Kamerawechsel pro Checkpoint, Fallen → Abblende → Checkpoint, Stop → normale Ansicht |
| Trigger / Gegner | Zone feuert beim Betreten → Gegnerwelle + Boss-Intro + Shake; `spawn clear`; Warnung bei „Friedlich"; **Mutant Monsters 26.3.1** (Zombie, Creeper, Enderman) per Befehl |
| Director | Regiepult, Clean Feed (F8), Cue GO, **OBS: Auth + Pause beim Öffnen / Fortsetzen beim Schließen** (gegen obs-websocket-v5-Testserver) |
| Dedizierter Server | startet, alle Event-Typen registriert, keine Client-Klassen auf dem Server |
| Demo | `/btb demo setup`/`remove`, Szene `heart_discovery` |

## Ohne Dateien (neu, im Spiel geprüft)
- `/btb hud text` in allen Stilen (Titel, Kapitel, Boss, Bauchbinde, Untertitel …), `hud save`, Credits-Listen per Befehl inkl. Endkarte mit Link-Icon
- Szenen-Baukasten: `create/add/events/remove/move/shift`, Syntaxprüfung beim Hinzufügen, **Live-Aufnahme** (Zeiten stimmen, `/tp` wird nicht mitgeschrieben), Abspielen
- Regiepult: Baukasten-Reiter, „Klick → Szene" (Effekte landen bei der eingestellten Sekunde), HUD-Texteingabe, Credits-Eintrag, Cue hinzufügen, OBS-Felder
- Ambiente-Zone komplett per Befehl (Sound, Partikel inkl. Farbstaub, Beben, Blitze, kreisende Blöcke), `destruction proximity/direction`, `portal activationscene`

## Automatische Tests
28 JUnit-Tests grün (neu: Schnell-Stile für HUD-Texte): Szenen-Laden/Validierung, Timing, Pause, Loop, Fehlerpolitik, Beat-Sync,
Kameramathe (Easing, Pfade, Orbit), HUD-/Effekt-Spec-Validierung.

## Beim letzten Testlauf gefundene und behobene Fehler
- Buch: Seite öffnete sich erst nach mehreren Sekunden, Text erschien ruckartig. Jetzt öffnet die Seite über `in`, dann läuft die Tinte.
- Vorspann: lange Überschriften liefen rechts aus dem Bild. Jetzt automatisch verkleinert.
- Zonen-Trigger feuerte sofort beim Anlegen (man stand drin) und ohne `once` alle 4 Ticks. Jetzt nur beim Betreten.
- Parcours: Wer gleichzeitig Kameramann und Darsteller ist, wurde ignoriert. Jetzt möglich (Solo-Dreh in dritter Person).

- Cue-Monitor stand dauerhaft im Bild. Jetzt nur noch beim Halten von Numpad *, mit OBS erst nach der Aufnahme-Pause.
- Regiepult: Mit OBS wird es erst nach der Aufnahme-Pause gezeichnet (vorher konnten 1–2 Bilder im Video landen).
- Cue-Befehle schrieben Erfolgsmeldungen in den Chat. Jetzt stumm (Fehler bleiben sichtbar).
- Szene von Hand gestoppt: Texte/Kinobalken blieben stehen. Jetzt blenden sie aus.
- Boss-Intro: Untertitel klebte an der roten Randlinie. Das Band passt sich jetzt der Schrift an.
- `/btb spawn` nahm keine IDs anderer Mods (`mutantmonsters:…`). Jetzt geht jede Entity-ID.

- Untertitel und Bauchbinde überlappten sich. Die Bauchbinde sitzt jetzt darüber.
- Regiepult: Chat schien durch das halbtransparente Fenster, Bestätigungen landeten im Chat. Jetzt deckend und stumm (Fehler bleiben sichtbar).

- Szene endete auf Schwarz und blieb schwarz. Jetzt kommt das Bild nach 5 s zurück (Stop: sofort); Befehle in Szenen gehören jetzt richtig zur Szene.
- Herz in der Hand: Kristalle leuchteten flächig pink. Jetzt nur Spitzen und Risse.
- Weißer Rahmen (`radiant_frame`) leuchtet jetzt immer voll, auch nachts.
- Ambiente des Turms (Beben, rote Blitze, Partikel) im Regiepult unter Action einzeln abschaltbar.

## Film-Set (neu, im Spiel geprüft)
- `/btb film setup` baut alle Abschnitte ohne fehlgeschlagene Befehle; Rundgang `film_vorschau`
- Buch per Rechtsklick, Boss → Schlüssel, Schloss „Verschlossen“ / Gitter fährt hoch, Ghast-Angriff
- Portal mit Schlüssel → Kamera kreist → Durchgehen → Ausgang mit Statisten → Schwarzblende → End-Card
- Nicht einzeln durchgespielt: Beat-Drop-Wellen im Innenhof, brennende/einstürzende Brücke im Set, Jump'n'Run-Kurs `felsen` im Set (die Mechaniken selbst sind getestet)

## Runde 4 (im Spiel geprüft)
- Turm-Einsturz mit Staub, Trümmern und Schutthaufen; Herzwächter (Modell, Leuchten, Schlüssel-Drop)
- Auto-Regie mit Hindernis-Ausweichen; Film-Set auf unebenem Gelände (Höhen erkannt, Treppen auf Rampen); Weg durch die Lavaschlucht
- Herz in der Hand als normales Modell (GeckoLib nur noch für die schwebende Anzeige); OBS-Test-Knopf gegen Test-Server
- Boss-Zusatz `boss` bei `/btb spawn` erzeugt jetzt immer den Herzwächter (ohne Namensschild)
- OBS ohne Pause-Funktion: Aufnahme wird beim Öffnen des Menüs gestoppt und danach neu gestartet (gegen Test-Server geprüft)
- Nicht geprüft: mit echtem Iris-Shader-Pack, mit echtem OBS, Herzwächter-Kampf über längere Zeit (KI nur kurz gesehen)

## Runde 5 (im Spiel geprüft)
- Kamera bleibt beim Kreisen/Drehen vor Wänden; Auto-Regie läuft jetzt auch in Szenen (`execute … run` wurde vorher verworfen)
- Einzelne Set-Teile in Blickrichtung (Süden geprüft), Herz-Enthüllung, Herzwächter steigt aus dem Boden
- Filmkorn (Shader), Dolly-Zoom, Zeitlupe (Tickrate 6 → zurück auf 20), dumpfer Ton (OpenAL-Filter aktiv), Regieklappe, Glutfackel, Seelenkompass
- Nicht im Spiel angesehen: Hitzeflimmern auf echtem Lavagelände, Farbstimmung pro Zone, Nachbeben, Tor im Torhaus, komplettes Set mit Wegen auf Nether-Gelände; Shader-Effekte mit Iris-Shaderpack

## Runde 6 (im Spiel geprüft, Bilder: `screenshots/runde5_*.png`)
- Sechs Atmosphäre-Blöcke mit eigenen Modellen und Partikeln, im Film-Set verteilt (Bastion, Turm, Portalhalle geprüft)
- Herz in der Hand: pulsierender roter Bildrand; Herz-Enthüllung startet am Turmtor, Wächter steigt aus dem Boden
- Hinweis: Die Lichtstärke selbst (wie hell der Boden wird) ist in der Testumgebung nicht sichtbar, auch eine normale Fackel hellt dort nichts auf (Software-Rendering).

## Runde 7: mit echten Shaderpacks (Iris 1.11.7)
- **MakeUp Ultra Fast 9.6** und **Complementary Reimagined r5.9.3**: Hitzeflimmern über Lava, Filmkorn und der Herzschlag-Rand funktionieren beide (Bilder `screenshots/runde6_*`)
- Hitzeflimmern feiner gemacht: kleine, schnelle Wellen statt schwankender Säulen
- Nicht geprüft: andere Shaderpacks (BSL, SEUS …); Leistung auf echter Grafikkarte

## Runde 8 (im Spiel geprüft, Bilder `screenshots/runde8_*`)
- Tiefenschärfe ohne Shader (echte Entfernung) und mit Complementary (Bereich um den Darsteller); Lichtstreifen an Lampen; Rückblende; Formate 2.39 und 4:3
- Markierungen, Lava-Ausbruch, Ghast-Absturz (auch auf „Friedlich“), Decke mit Warnung, schwebender Schlüssel, altes Buch, Wind gesetzt
- Nicht einzeln geprüft: Herzschlag nach Nähe (nur gebaut), Markierungen für Spieler mit Tag `kamera` (nur ein Client), Wind sieht man erst in Bewegung

## Runde 9: Bild-im-Bild
- Gegen den OBS-Testserver geprüft: Szene finden, Quelle „BTB PiP“, Position/Größe (1920×1080 → 576×324 oben rechts), ein/aus
- Zuschauer-Fenster: folgt dem Darsteller, ohne HUD; Rahmen + Name im Hauptbild; Sicht-Schnitt `camera pov`
- Nicht geprüft: mit echtem OBS und einem echten zweiten Minecraft-Client gleichzeitig

## Runde 10 (im Spiel geprüft)
- Herzwächter: Phase 1 (Faustschlag, Bodenrisse, Schockwelle), Phasenwechsel mit Brüllen, Lavasäulen, Felswurf (Phase 3)
- LUTs: alle eingebauten Looks und eine eigene .cube-Datei; Ego-Kamera kompiliert/startet
- Glutling, Aschekriecher, Seelenwisp; Portalfokus (animiert), Runensäule, Glyphenplatte
- Wetter (Asche, Glutsturm), Blutmond, Countdown (nur Darsteller), Lampen dimmen, Fahrt-Vorschau
- Nicht einzeln geprüft: Kettenreaktion, glimmende Trümmer, Probe-Modus, Schärfe pro Kamerapunkt, Glätten, Szene ab Sekunde X (nur kompiliert)

## Runde 11: Replay, Export, Doubles (im Spiel geprüft)
- Replay: Aufnahme (Darsteller + Herzwächter), Wiedergabe mit Puppen (Skin, Schwert, Wege), Darsteller danach wieder normal
- Export: 60 Bilder 2562×1440 → MP4 1920×1080 @ 30 FPS (ffmpeg), ohne Hotbar
- Statisten mit Gesten (ducken, Arm, Schild, Bogen, winken)
- Nicht geprüft: 4K/60 über lange Zeit (nur kurzer Test), Export mit Shaderpack, Double neben einem echten zweiten Spieler

## Runde 12: Replay-Editor (im Spiel geprüft)
- Editor öffnen (Befehl und F6), Abspielen/Pause, Spulen per Timeline-Ziehen, Tempo
- 3 Kamera-Keyframes per K im Flug gesetzt, Kamerafahrt mit J abgespielt
- Export aus dem Editor: Bereich 1–5 s, 1080p/30 → 120 Bilder + MP4, Kamera fährt die Keyframes
- Behoben: Szenen und Replays liefen beim Export im eingefrorenen Spiel in Echtzeit weiter (jetzt nur noch Bild für Bild)
- Nicht geprüft: Spulen mit laufenden Szenen/Einstürzen im echten Set, „Fahrt+Blick“, Mausrad-Zoom, Replay-Wechsel ◀ ▶

## Runde 13: Fehlerbehebungen (im Spiel geprüft)
- Herzwächter: Film-Kreaturen bleiben jetzt auch auf „Friedlich“ (vorher von Minecraft sofort gelöscht → „spawnt nicht“)
- Herzwächter steigt sofort aus dem Boden (kein kurzes Stehen mehr), kräftigere Laufanimation, Schritttempo passt zur Geschwindigkeit
- Schlüssel: schießt aus dem Herz, fliegt im Bogen mit Feuer-/Lichtspur, schlägt mit Glutring und Beben auf, liegt 3 s leuchtend (Lichtsäule), dann aufhebbar
- Einsturz: Trümmer landen auf dem echten Boden, fallen nach außen, drehen ruhiger und werden dort, wo sie liegen, zum Schutthaufen (kein Schweben, kein plötzlicher Haufen)
- Tor: zweiflügeliges Tor, die Flügel schwingen an ihren Angeln auf (vom Spieler weg) und bleiben offen stehen – nichts schwebt, nichts verschwindet; kurzer Sound. Zurücksetzen schließt es wieder
- Abspann ruckelt nicht mehr (stufenlose Bewegung); Credits: Einträge ändern, verschieben, Tempo (Regiepult ✎ ▲ ▼, Befehle edit/move/tempo)
- Export: Fortschritt im Fenstertitel, Esc bricht ab (kein Menü mehr im Video), kein Pausieren bei Fokusverlust, Seitenverhältnis bleibt erhalten (Zuschnitt statt Verzerrung)
- Replay-Editor zeigt Fehler und Meldungen (rot/grün) über dem Panel und in der Flug-Leiste

## Runde 14: Trailer (im Spiel geprüft)
- Song analysiert (Lautstärke, Tempo 161,5 BPM, Schläge, Phrasen bei 29,1 / 52,4 / 76,7 / 100,0 s, Stille ab 121,7 s), Szene `trailer` mit ~45 Schnitten auf die Schläge
- Eigene Musik: config/btb_cinematic/music → automatisches Ressourcenpaket, `musik.<name>`; Export legt den Song per ffmpeg unter das Video
- Trailer live durchgespielt und als 1080p/24-Vorschau mit Ton exportiert (Software-Rendering ohne Shader)
- Nicht geprüft: Export in 4K/60 und mit Shaderpack, Wiedergabe mit echter Soundkarte (hier stumm)

## Runde 15: Trailer-Texte, Effekt-Dauer
- Trailer: „BEHIND THE BLOCKS presents“, Titel „THE HEART OF THE NETHER“ + „Trailer“, Directed by / Feat. (Spotify-Icon), Endkarte „Coming 2027“ mit Instagram/TikTok, Held mit Skin Nik2810 (Skin lädt nur online), keine Buch-/Story-Texte
- Endkarten-Titel passt sich an die Bildbreite an
- Effekte mit optionaler Dauer: enden von selbst; der Trailer räumt am Ende alle Effekte weg

## Runde 16: Tiefenschärfe, Flare, Zurücksetzen (im Spiel geprüft)
- Tiefenschärfe war seit 26.3 kaputt (umgekehrte Tiefe „Reversed-Z“ → alles gleich unscharf). Jetzt echt nach Entfernung: ohne Shader und mit Shaderpack (MakeUp, Tiefe direkt aus Iris)
- Flare: neun Sichtstrahlen, nur feste Blöcke verdecken, weiches Aus-/Einblenden am Bildrand, kein Springen in die Ecke, stabil auf große Entfernung
- Bereiche mit gleichem Namen neu angelegt (z. B. Set woanders gebaut): alte Sicherung wird gelöscht – Turm und Tor kommen beim Zurücksetzen wirklich zurück; Nachbeben nach dem Zurücksetzen fallen weg
- Netherkristalle kreisen wieder um das Herz; Effekt-Dauer geprüft (Tönung endet nach der angegebenen Zeit)
- Trailer: Ghast-Absturz-Einstellung folgt dem Ghast, Einsturz-Totale über der Mauer
- Nicht geprüft: Tiefenschärfe mit Complementary und anderen Shaderpacks

## Nicht getestet
- **Echte GPU / Performance-Messung**: Software-Rendering sagt nichts über FPS aus. Bitte auf euren Rechnern prüfen.
- **Mehrere echte Clients gleichzeitig** (Director + Kamera + Darsteller). Logik ist dafür gebaut, aber nur mit einem Client getestet.
- Shaderpacks außer MakeUp und Complementary
- **Echtes OBS Studio** (nur Protokoll-Testserver).
- **Dimensionswechsel durch Portale** (Overworld → Nether): Code vorhanden, getestet nur innerhalb einer Dimension.
- **Musik-Crossfade** nur im Log geprüft, nicht angehört.
- Regiepult bei sehr großen Fenstern/kleiner GUI-Skala nur kurz angesehen; getestet vor allem im engsten Fall (854×480, Skala 2)
- Ghast-`fireball`-Event, `portal_transfer` per Szene, Ambiente im Nether.

## Bekannte Einschränkungen
- Hex-Farben in Befehlen ohne `#` schreiben (`ff6040`). Brigadier erlaubt kein `#`.
- Monster verschwinden auf „Friedlich" (Vanilla-Verhalten).
- Maximal 8192 Blöcke Portalfläche pro Portal.
- Ember's Text API gibt es nicht für 26.3, daher eigener Text-Renderer mit TTF-Schrift (Cinzel).
- Es gibt keine fertigen Screen-Effekt-Mods für 26.3, deshalb sind alle Effekte selbst gebaut.
