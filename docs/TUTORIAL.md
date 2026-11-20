# Tutorial – Behind The Blocks Cinematic Engine

Von der Installation bis zur fertigen Aufnahme, Schritt für Schritt.

> **Ihr müsst keine Dateien bearbeiten.** Alles geht per Befehl im Chat oder im Regiepult (F7).
> Die Mod schreibt die Dateien unter `config/btb_cinematic/` selbst; wer mag, kann sie trotzdem öffnen.

---

## 1. Was muss wohin? (Abhängigkeiten)

Alle Dateien kommen in den `mods`-Ordner. **Server und alle Clients brauchen dieselben Mods**
(Ausnahme: OBS läuft nur auf dem aufnehmenden PC und ist keine Mod).

| Datei | Version (für MC 26.3) | Server | Jeder Client | Pflicht? | Download |
|---|---|:-:|:-:|---|---|
| Fabric Loader | ≥ 0.20.1 | ✔ | ✔ | Pflicht | https://fabricmc.net/use/ |
| Fabric API | ≥ 0.166.2+26.3 | ✔ | ✔ | Pflicht | https://modrinth.com/mod/fabric-api |
| GeckoLib | 5.5.7 (Fabric 26.3) | ✔ | ✔ | Pflicht (Herz-Modell) | https://modrinth.com/mod/geckolib |
| **btb_cinematic-1.0.0.jar** | 1.0.0 | ✔ | ✔ | Pflicht | diese Mod |
| Mutant Monsters | 26.3.1 (Fabric) | ✔ | ✔ | optional (Mutanten-Gegner) | https://modrinth.com/mod/mutant-monsters |
| ↳ Puzzles Lib | ≥ 26.3.11 (getestet 26.3.18) | ✔ | ✔ | nötig für Mutant Monsters | https://modrinth.com/mod/puzzles-lib |
| ↳ Forge Config API Port | 26.3.1 | ✔ | ✔ | nötig für Mutant Monsters | https://modrinth.com/mod/forge-config-api-port |
| OBS Studio | ≥ 28 | – | nur Aufnahme-PC | optional | https://obsproject.com |
| Java | 25 | ✔ | ✔ (bringt der Launcher mit) | Pflicht | – |

Auf Modrinth bei jeder Mod **Minecraft 26.3** und **Fabric** auswählen.

**Einzelspieler:** Der Client ist gleichzeitig der Server, also alles in `.minecraft/mods`.
**Server:** dieselben JARs in `<server>/mods` und bei jedem Mitspieler in `.minecraft/mods`.
Ein Spieler ohne die Mod kann zwar beitreten, sieht aber keine Kamerafahrten, Blenden oder Texte.

Rechte: Alle `/btb`-Befehle brauchen Operator-Level 2 (`/op Name` auf dem Server, „Cheats an" im Einzelspieler).

---

## 2. Die ersten 5 Minuten

1. Neue Welt, **Kreativ**, **Cheats an**, am besten flach. Nicht in eurer Film-Welt testen!
2. `/btb demo setup` baut den Herz-Turm um euch herum. Das Gelände wird vorher gesichert.
3. `/btb scene start heart_discovery` startet die Beispielszene: Kamerafahrt, Texte, Effekte, das Herz.
4. **F7** öffnet das Regiepult. Aktionen starten erst, wenn ihr das Menü schließt („Klick: beim Schließen“), damit nichts davon im Bild ist; oben rechts lässt sich auf „Klick: sofort“ umstellen. Klickt euch durch die Reiter. Viele Knöpfe erklären beim Drüberfahren, was sie tun.
5. Den Weg zum Turm entlanglaufen: An den Lava-Wänden bricht Lava heraus, sobald ihr vorbeigeht.
6. `/btb resetall` setzt alles zurück (Lava weg, Wände wieder da, Gegner weg …).
7. `/btb demo remove` stellt das ursprüngliche Gelände wieder her.

---

## 3. Eine eigene Kamerafahrt

**Am einfachsten: abfliegen.**
1. `/gamemode spectator`
2. Zum Startpunkt fliegen, in die gewünschte Richtung schauen.
3. `/btb camera record bastion_fly` → die Fahrt langsam und gleichmäßig abfliegen.
4. `/btb camera record stop`. Der Pfad wird automatisch geglättet und gespeichert.
5. `/btb camera play bastion_fly` zum Ansehen, `/btb camera stop` bricht ab.

**Präzise: Punkt für Punkt.**
`/btb camera create brücke` (aktuelle Ansicht = Punkt 1) → hinfliegen → `/btb camera point brücke` → …
Mit `duration`, `easing`, `fov`, `roll` feinjustieren (siehe COMMANDS.md).

**Kreisen um etwas:** `/btb camera orbit entity @p 8 3 10 360`: 8 Blöcke Abstand, 3 hoch, 10 s, eine volle Runde.

**Verfolgen:** `/btb camera follow Darsteller side 9 2`: Seitenansicht, die dem Darsteller weich folgt.

---

## 4. Texte direkt eintippen

Im Chat, mit `|` für eine neue Zeile bzw. „Name | Untertitel":
```
/btb hud text titel 5 KAPITEL EINS | Die Ankunft
/btb hud text boss 6 DER WÄCHTER | Hüter des Herzens
/btb hud text bauchbinde 6 Alex | Der Wanderer
/btb hud text untertitel 4 Wo bin ich hier gelandet?
/btb hud text dialog 8 Wächter | Wer das Herz sucht, muss zuerst den Schlüssel finden.
/btb hud text buch 10 Das letzte Kapitel | If you want to leave this place, find the Heart of the Nether.
/btb hud text wabernd 8 IF YOU WANT TO LEAVE THIS PLACE, | FIND THE HEART OF THE NETHER.
/btb hud text endkarte 0 TO BE CONTINUED | Das Portal hat uns nicht nach Hause gebracht …
```
- Die Zahl ist die Sekundenzahl auf dem Bildschirm (inkl. Ein-/Ausblenden), **0 = bleibt stehen**.
- Alle Stile: `/btb hud styles` → `titel, glut, kapitel, untertitel, bauchbinde, dialog, ziel, buch, boss, wabernd, endkarte, camcorder`.
- Ausblenden: `/btb hud hide` (alle) oder `/btb hud hide boss`.
- Für später speichern: `/btb hud save mein_titel titel 5 KAPITEL EINS | Die Ankunft` → wieder zeigen mit `/btb hud show mein_titel`.

**Im Menü:** F7 → **HUD** → Text ins Feld tippen, Sekunden eintragen, auf einen Stil klicken (Titel, Boss, Buch …).
„Als Vorlage" + Name + „Speichern" legt ihn dauerhaft an.

### Credits (Vorspann, Abspann, Endkarte)
```
/btb hud credits vorspann new vorspann EIN FILM VON BEHIND THE BLOCKS
/btb hud credits vorspann add Regie & Kamera | Alex | youtube | youtube.com/@behindtheblocks
/btb hud credits vorspann add Musik | Euer Artist | spotify | open.spotify.com/artist/…
/btb hud credits vorspann list            (nummeriert)  ·  remove 2  ·  clear
/btb hud show vorspann                    Vorschau

/btb hud credits abspann new abspann ENDE
/btb hud credits abspann heading Darsteller
/btb hud credits abspann add Held | Spieler Eins
/btb hud credits schluss new endkarte TO BE CONTINUED | Das Portal hat uns nicht nach Hause gebracht …
/btb hud credits schluss link youtube @behindtheblocks
```
Format beim Hinzufügen: `Rolle | Name | Icon | Link` (Icon und Link optional). Icons: `youtube, spotify,
twitch, instagram, tiktok, discord, x, soundcloud, patreon, github, web, mail`.
**Im Menü:** F7 → **Credits** → Liste wählen (◀ ▶), Rolle/Name/Link eintippen, Icon durchklicken, „+ Eintrag".
Jede Zeile hat ein ✕ zum Löschen. Neue Liste: Name eintippen und „vorspann" / „abspann" / „endkarte".

### Effekte
```
/btb effects fade black 2             Schwarzblende (bleibt stehen) – /btb effects fadeout 1
/btb effects letterbox 0.12 1         Kinobalken
/btb effects flare 100 90 -40 orange 0.8   Lens Flare auf einen Punkt am Horizont
```
Oder F7 → **Effekte**.

---

## 5. Eine Szene zusammenbauen (ohne Dateien)

> **Wozu?** Beim Filmen habt ihr keine Hand frei fürs Menü. Eine Szene ist ein „Drehbuch zum Abspielen":
> Ihr legt *vorher* fest, wann welche Blende, welcher Text, welcher Kameraflug kommt, und beim Dreh drückt
> ihr **eine Taste** (siehe unten „Auf Taste legen"), und alles läuft von selbst in der richtigen Reihenfolge ab.
>
> **„Live aufnehmen"** nimmt dafür *kein Video* auf, sondern eure **Regie-Aktionen**: Ihr spielt die Szene
> einmal als Probe durch (Knöpfe drücken, Befehle tippen, Cue-Tasten), und die Mod merkt sich jede Aktion mit
> der Sekunde, in der ihr sie ausgelöst habt. Danach kann die Szene das alles allein wiederholen.

Eine Szene ist eine Liste: *zu welcher Sekunde passiert was*. Drei Wege, alle ohne Datei:

**A) Live aufnehmen – am schnellsten.**
1. F7 → **Baukasten** → Szenenname eintippen (z. B. `intro`) → **● Live aufnehmen**. Oder im Chat: `/btb scene rec intro`
2. Menü schließen und einfach inszenieren: Knöpfe im Regiepult, Befehle im Chat, Cue-Tasten.
   Alles landet **mit der genauen Zeit** in der Szene (oben in der Leiste steht „● REC intro 12,5 s").
3. **■ Aufnahme stoppen** (bzw. `/btb scene rec stop`) zeigt die Liste der Schritte.
4. **▶ Abspielen**: Die Szene läuft genau so noch einmal ab, beliebig oft.

**B) Klicken statt ausführen.**
1. F7 → oben rechts **„Klick: ausführen"** anklicken, bis **„Klick → Szene"** dasteht (rote Zeile oben zeigt Szene und Zeit).
2. Im **Baukasten** bei „Bei" die Sekunde eintragen, z. B. `12`.
3. In die anderen Reiter wechseln und klicken (Effekte, HUD-Texte, Kamera, Portale …): Jeder Klick wird
   bei 12 s zur Szene hinzugefügt, statt sofort ausgeführt.
4. Zurück auf „Klick: ausführen" schalten, wenn ihr fertig seid.

**C) Eintippen.**
```
/btb scene create intro 60                              neue Szene, 60 s
/btb scene add intro 0 btb effects letterbox 0.12 1
/btb scene add intro 2 btb hud text titel 5 BEHIND THE BLOCKS | präsentiert
/btb scene add intro 8 btb camera play bastion_fly
/btb scene add intro 20 btb spawn mutantmonsters:mutant_zombie 2 ^ ^ ^10 4
/btb scene events intro                                 Liste mit [▶ testen] [✎ Zeit] [✕ löschen] zum Anklicken
```
Ein Tippfehler wird sofort gemeldet, nicht erst beim Dreh.

**Nachbearbeiten:** im Baukasten pro Schritt **▶** (nur diesen Schritt testen), **−1s / +1s** (verschieben),
**✕** (löschen). Oder per Befehl: `move intro 3 12.5` · `shift intro 3 -0.5` · `remove intro 3` ·
`duration intro 90` · `loop intro on` · `copy intro intro_v2` · `delete intro`.

**Auf Taste legen:** im Baukasten **⌨ Auf Num 5** (mit ▸ die Taste wählen). Beim Filmen startet
Numpad 5 dann die ganze Szene, ohne Menü.

Wer und wo: Jeder Schritt merkt sich, wer ihn ausgelöst hat und von wo (Position und Blickrichtung).
„Für mich", `~` und `^` funktionieren beim Abspielen also wie beim Bauen.

Für Fortgeschrittene: Takt-genaues Timing per BPM (Beat-Sync) und alle Event-Typen stehen in [SCENES.md](SCENES.md).

---

## 6. Portale (beliebige Form, weiß oder Nether)

1. Rahmen bauen, **in jeder Form** (Kreis, Bogen, Boden-Loch …), aus `portal_frame` (Nether-Look)
   oder `radiant_frame` (weiß). Beides im Kreativ-Tab „Behind The Blocks".
2. In den Rahmen hineinzielen bzw. eine Position im Inneren nehmen:
   `/btb portal fill tor_a ~ ~ ~` füllt die Innenfläche (bis 8192 Blöcke).
3. `/btb portal style tor_a white` für das epische weiße Portal.
4. Schlüsselstein: `portal_keystone` neben den Rahmen setzen → `/btb portal keystone tor_a <pos>`.
5. Zweites Portal genauso (`tor_b`), dann verbinden: `/btb portal link tor_a tor_b both`.
6. Mit dem Item `nether_key` auf den Schlüsselstein klicken: Das Portal lädt sich auf und aktiviert sich.
   Ohne Schlüssel: `/btb portal activate tor_a`.
7. Hineinlaufen → Weißblende → ihr kommt aus `tor_b` heraus.

Boden-Portal: Loch im Boden mit Rahmen drumherum, `fill` in das Loch. Man fällt hinein und taucht beim Partner oben auf.

**Kamera kreist während der Aktivierung:** Szene `aktivierung` bauen (Kapitel 5, z. B. live: Orbit, Shake, Blitz) und
`/btb portal activationscene tor_a aktivierung`: Sie startet automatisch, sobald der Schlüssel eingesetzt wird.
Bei Ankunft am anderen Portal: `/btb portal arrival tor_b gegner_ueberall`.

---

## 7. Lava aus Wänden, Einstürze, brennende Brücken

1. `/btb destruction create wand1 <ecke1> <ecke2>`: Bereich markieren (das Original wird gesichert).
2. `/btb destruction effect wand1 wall_burst`
3. Auslösen beim Vorbeilaufen: `/btb destruction proximity wand1 5.5` (ausschalten: `… proximity wand1 off`).
   Richtung, aus der die Lava herausbricht: `/btb destruction direction wand1 south` (oder `auto`).
4. Manuell: `/btb destruction trigger wand1` · zurücksetzen: `/btb destruction restore wand1`.

Brücke brennt: Effekt `fire`. Turm stürzt ein: `collapse`. Explosion: `explode`. Gleicher `seed` =
bei jedem Take exakt gleich.

### Ambiente: ein Ort, der lebt (Lava-Wüste, Herz-Turm …)
```
/btb ambience create wueste 60                          Zone mit 60 Blöcken Radius um euch
/btb ambience sound wueste btb_cinematic:nether_drone 8 2   alle 8 s, Lautstärke 2
/btb ambience particles wueste minecraft:ash 6 20          6 Partikel, 20 Blöcke Streuung
/btb ambience particles wueste dust:ff2a00 3 8             rote Glut (Farbe ohne #)
/btb ambience shake wueste 0.1 4                           leichtes Beben alle 4 s
/btb ambience flash wueste dark_red 6                      roter Blitz alle 6 s
/btb ambience orbit wueste minecraft:magma_block 10 25 30   kreisende Brocken
/btb ambience off wueste · on · delete
```

---

## 8. Jump'n'Run mit Perspektivwechseln

1. Auf den Start stellen: `/btb parkour create sprung`
2. Auf jede Plattform stellen: `/btb parkour checkpoint sprung`
3. Kamera pro Checkpoint: `/btb parkour camera sprung 1 side 9 2` · `… 2 behind 6 3` · `… 3 top 14 0`
4. Wer wird gefilmt, wer filmt: `/btb parkour follow sprung Darsteller` ·
   `/btb parkour cameraman sprung Kamera` (allein drehen: zweimal euren eigenen Namen).
5. Fallhöhe: `/btb parkour fally sprung <y>`
6. `/btb parkour start sprung`. Wer fällt oder in Lava springt, bekommt eine Abblende und landet am letzten Checkpoint. Niemand stirbt.
7. `/btb parkour stop sprung`

---

## 9. Story-Auslöser und Gegner

**Buch auf dem Podest:**
```
/btb trigger interact buch <pos-des-pults>
/btb trigger action buch btb hud show book_heart
/btb trigger action buch btb sound play crystal_shimmer
```
**Hinterhalt beim Betreten:**
```
/btb trigger zone hinterhalt 4                      (Zone um eure Position)
/btb trigger action hinterhalt btb spawn wither_skeleton 6 ~ ~ ~ 5
/btb trigger action hinterhalt btb hud show boss_intro
```
**Mutanten:** `/btb spawn mutantmonsters:mutant_zombie 1 ^ ^ ^8 0` (8 Blöcke vor euch).
Weitere: `mutant_skeleton`, `mutant_creeper`, `mutant_enderman`. Im Regiepult unter **Action**
gibt es dafür eigene Knöpfe, sobald Mutant Monsters installiert ist.
`/btb spawn clear` entfernt alle gespawnten Gegner.

> Gegner verschwinden auf **Friedlich** sofort. Zum Drehen `/difficulty easy` oder höher.
> Damit Darsteller nicht sterben: `/effect give Darsteller resistance infinite 4 true`.

Trigger sind nach `/btb resetall` wieder scharf.

---

## 10. Aufnehmen, wenn der Director gleichzeitig filmt

1. **OBS einrichten** (einmalig): OBS → *Werkzeuge → WebSocket-Server-Einstellungen* → aktivieren,
   Passwort setzen. In Minecraft: F7 → **Aufnahme** → Host (meist `127.0.0.1`), Port (`4455`) und
   Passwort eintragen → **Speichern & verbinden**.
2. **Cue-Liste** (der Ablauf eures Drehs, ein Eintrag pro Moment), alles in F7 → **Aufnahme**:
   - Befehl eintippen (+ optional Name) → **+ Cue**, oder
   - oben rechts **„Klick → Cue-Liste"** wählen und in den anderen Reitern klicken: Jeder Klick wird ein Cue
     (z. B. „Szene intro starten", „Weißblende", „OBS Start").
   - In der Liste: **↑ ↓** sortieren, **✕** löschen, **Ab hier** = GO beginnt bei diesem Cue.
3. **Einzelne Aktionen auf Tasten legen** (Blenden, Blitze, Texte, Kamerafahrten, Szenen, OBS …):
   F7 → oben rechts so oft klicken, bis **„Klick → Taste"** dasteht, daneben mit **Num N ▸** die Taste wählen,
   dann die gewünschte Aktion in irgendeinem Reiter anklicken (z. B. Effekte → Blenden → Weiß).
   Sie liegt jetzt auf **Numpad N**; der nächste Klick geht automatisch auf die nächste Taste.
   Übersicht und Freigeben: F7 → **Aufnahme** (pro Taste ▶ testen, Belegen, ✕).
   Beim Schließen des Menüs schaltet der Modus automatisch zurück auf „Klick: ausführen".
4. **F8** drücken (Clean Feed): Hotbar, Hand, Chat und Fadenkreuz verschwinden, eure Film-Texte und Blenden bleiben.
5. Drehen:
   - **Numpad 1–9** = eure belegten Aktionen, **Numpad Enter** = nächster Cue (GO), **Numpad 0** = einen zurück.
   - **Numpad * halten** zeigt, was auf welcher Taste liegt (mit OBS pausiert die Aufnahme dabei).
   - Es erscheint **nichts** auf dem Bildschirm, auch keine Chat-Meldung. Wollt ihr sehen, was
     als Nächstes kommt: **Numpad * gedrückt halten**. Die Aufnahme pausiert kurz (mit OBS), der
     Monitor erscheint, beim Loslassen geht die Aufnahme weiter. Ohne OBS ist der Monitor sichtbar,
     solange ihr haltet; dann nur zwischen den Takes nachschauen.
   - Regiepult (F7) öffnen pausiert die OBS-Aufnahme, Schließen setzt sie fort.
6. Nach dem Take: `/btb resetall`, nächster Take.

Für große Drehs ist ein zweiter Client als Regie trotzdem die sauberste Lösung. Alle Befehle wirken
per Spielername auch auf andere (`/btb camera play bastion_fly Kamera1`).

---

## 11. Das komplette Film-Set zu eurem Storyboard

In einer **leeren, flachen Testwelt** an den Startpunkt stellen, mit Blick nach Osten (+X):
```
/btb film setup                      baut alles in einer Linie nach +X (ca. 400 Blöcke, Original wird gesichert)
/btb scene start film_vorschau       Rundgang durch alle Abschnitte mit den Zeitmarken (ca. 100 s)
/btb film remove                     alles weg, Gelände wie vorher
```

| Zeit | Abschnitt | Was passiert / wie es ausgelöst wird |
|---|---|---|
| 0:00–0:46 | **Bastion** (teilweise zerstört) | In der Halle stehen → `/btb scene start film_01_bastion`: Schwarzblende auf, Kamerafahrt von weit draußen durchs Dachloch zu euch, Vorspann-Credits, „KAPITEL I“ |
| 0:47–1:32 | **Buchraum** | Rechtsklick auf das Lesepult → Buchseite „If you want to leave this place …“ (Szene `film_02_buch`) |
| 1:33–2:18 | **Innenhof → Korridor → Brücken** | Innenhof betreten → Beat-Drop: Blitz, Beben, Gegnerwellen (`film_03_ausbruch`). Im Korridor brechen zwei Lava-Wände auf, sobald ihr vorbeilauft. Brücke betreten → die Ränder brennen; am Ende stürzt die Brücke hinter euch in den Lavasee |
| 2:19–3:05 | **Lavawüste** | Wüste betreten → `film_04_wueste`: Asche, Seelensand, Ghasts am Horizont schießen (harmlose) Feuerbälle, Jump'n'Run über den Lavafluss mit Perspektivwechseln (Kurs `felsen`) |
| 3:06–3:52 | **Festung + Turm des Herzens** | Am Ende der Wüste → Kamera zeigt den leuchtenden Turm, die Botschaft wabert, Lens Flare (`film_05_festung`). Im Hof spawnen Gegner, am Turmeingang „DER WÄCHTER“: der Boss lässt den **Schlüssel** fallen. Das Herz bleibt auf dem Altar |
| (Tor) | **Festungstor** | Mit dem Schlüssel in der Hand auf den Schlussstein neben dem Gitter klicken → das Gitter fährt hoch. Ohne Schlüssel: „Verschlossen“ |
| 3:53–4:09 | **Portalhalle** | Schlüssel in den Schlussstein vor dem halbfertigen weißen Portal → Kamera kreist um euch, alles bebt, das Portal öffnet sich (`film_06_portal`) |
| 4:10–Ende | **Ausgang** | Ins Portal laufen → Weißblende → ihr kommt aus dem zweiten Portal, die Kamera fährt heraus, überall Gegner (stehen still), Schwarzblende, „TO BE CONTINUED“ (`film_07_ende`) |

- Alle Szenen könnt ihr im Regiepult unter **Szenen → ✎** im Baukasten ändern (Zeiten, Texte, neue Schritte).
- Wackeln, rote Blitze und Partikel am Turm: Regiepult → **Action** → Ambiente `herzturm` (An/Aus, Beben aus, Blitze aus).
- Perspektivwechsel im Jump'n'Run gelten für den Kameramann: `/btb parkour cameraman felsen <Name>` (allein drehen: euren eigenen Namen, plus `/btb parkour follow felsen <Name>`).
- Nach einem Take: `/btb resetall`. Wände, Brücke und Tor sind wieder da, Gegner weg, Trigger scharf.

### Einzelne Teile statt des ganzen Sets
Ihr könnt jedes Teil einzeln an eure Position stellen. Es wird **in eure Blickrichtung** gebaut (Norden, Süden, Osten oder Westen), und ihr steht dabei am Eingang:
```
/btb film piece festung        Turm des Herzens + Festung + Tor (Herz-Enthüllung, Herzwächter, Turm-Einsturz)
/btb film piece bastion        Halle, Buchraum, Innenhof, Gang mit Lava-Wänden
/btb film piece bruecken       brennende Brücken über dem Lavasee
/btb film piece wueste         Lavawüste mit Jump'n'Run
/btb film piece schlucht       Weg durch die Lavaschlucht (Säulen kippen)
/btb film piece portalhalle    halbfertiges weißes Portal mit Schlüsselstein
/btb film piece ausgang        zweites Portal (verbindet sich automatisch mit der Portalhalle)
/btb film remove festung       nur dieses Teil weg, Gelände kommt zurück
/btb film list                 was ist gebaut
```
Das ganze Set (`/btb film setup`) läuft jetzt in Kurven mit Wegen zwischen den Abschnitten, und die Ränder gehen weich ins Gelände über.
Alle Chunks des Sets bleiben geladen (`/btb film chunks off` gibt sie frei, `on` lädt sie wieder).
Für weite Kamerafahrten trotzdem die **Sichtweite** im Spiel auf mindestens 16 stellen.

### Neue Szenen am Turm
- `film_05_herz`: startet am Turmtor. Die Kamera kreist um den Altar, das Herz erscheint und beginnt zu schlagen, dann wacht der Herzwächter auf.
- `film_turm_einsturz`: Der ganze Turm stürzt ein, mit weiter Kreisfahrt, Staub, Trümmern und Beben. Start im Regiepult unter **Szenen** oder per Cue.
- Buch und Gang laufen ohne Bildschirm-Effekte (kein Blitz, kein Wackeln, keine Vignette).
- Das Festungstor fährt in ein Torhaus hoch und **bleibt oben**, bis ihr `/btb resetall` macht.

### Ins bestehende Gelände
`/btb film setup` misst unter jedem Abschnitt die Geländehöhe (auch Lava-/Wasseroberfläche) und baut ihn
dort hin, mit Fundament bis zum Boden. Brücken und Wege steigen mit Treppen zwischen den Höhen an.
Die Umgebung bleibt, wie sie ist. Gebaut wird in Blickrichtung **Osten (+X)** ab eurer Position,
etwa 450 × 70 Blöcke. Zwischen Festung und Portalhalle führt jetzt ein Weg durch eine **Lavaschlucht**
(Bögen, Laternen, Lavafälle, zwei Säulen kippen beim Vorbeilaufen in die Schlucht).

### Kamera beim Dreh: Tag `kamera`
Alle Kamerafahrten der Film-Szenen gehen an Spieler mit dem Tag `kamera`, also an den Aufnahme-Client:
```
/tag EuerKameraName add kamera
```
Die Darsteller behalten ihre eigene Sicht. Ist niemand getaggt (z. B. allein testen), sieht jeder die Fahrten.
In jeder Szene läuft nach der Eröffnung die **Auto-Regie**: Sie folgt dem nächsten Darsteller und wechselt
alle paar Sekunden die Perspektive (Schulter, Seite, Front, Kran, Untersicht, Verfolger, Kreisen), dazu Vignette.
Einzeln: `/btb camera auto <Spieler> [sekunden] [zuschauer]` oder Regiepult → Spieler → „Auto-Regie“, „Schulter“, „Front“, „Kran“.

### Der Herzwächter
Eigener Boss-Mob (kein Namensschild): ein Blackstone-Koloss mit glühendem Herz in der Brust, eigenen
Animationen (atmen, stampfen, Schlag mit beiden Fäusten), feuerfest, lässt beim Tod den **Netherschlüssel** fallen.
`/btb spawn btb_cinematic:herzwaechter 1 ~ ~ ~5 0` (mit `still` steht er still für Aufnahmen), oder im Regiepult
unter **Action → Boss: Herzwächter**. Der Zusatz `boss` bei `/btb spawn` erzeugt immer den Herzwächter.

### Einstürze
`collapse` stürzt jetzt richtig ein: Explosionen an der Bruchkante, Staubwolken und Asche (auch aus der Ferne
sichtbar), Grollen, große Trümmerbrocken, und am Ende bleibt ein **Schutthaufen** aus den echten Blöcken liegen.
`/btb destruction trigger turm_einsturz` holt im Film-Set den ganzen Herz-Turm herunter, `restore` baut ihn wieder auf.

### Neue Werkzeuge, die das Set benutzt
```
/btb spawn btb_cinematic:herzwaechter 1 ~ ~ ~5 0   der Herzwächter: eigener Boss, lässt den Schlüssel fallen
/btb spawn piglin 6 ~ ~ ~ 10 still              Gegner, die sich nicht bewegen (Statisten für Aufnahmen)
/btb spawn zombie 3 ~ ~ ~ 4 key                 einer davon trägt den Schlüssel
/btb ghastattack 3 ~ ~30 ~-60                   Ghasts am Horizont, die 30 s lang auf euch feuern (ohne Blockschaden)
/btb destruction effect tor gate                Bereich wird zum Gitter, das hochfährt (restore = wieder zu)
/btb trigger needitem schloss btb_cinematic:nether_key   Schloss: Trigger nur mit Schlüssel in der Hand
```

---

## 11b. Alles auf eure eigenen Bauten anwenden

Ihr braucht das Film-Set nicht. Jedes Werkzeug funktioniert auf allem, was ihr selbst gebaut habt.
Koordinaten bekommt ihr mit **F3** (Zeile „Targeted Block“), oder ihr steht einfach an der Stelle und nehmt `~ ~ ~`.

**1. Kamera-Spieler markieren** (einmal):
```
/tag EuerKameraName add kamera
```

**2. Auslöser an euren Bau setzen**
```
/btb trigger zone tor_innen 4                 an die Stelle stellen: feuert beim Betreten (Radius 4)
/btb trigger interact buch 120 65 -40         Rechtsklick auf diesen Block (Pult, Knopf, Altar …)
/btb trigger action tor_innen btb scene start meine_szene
/btb trigger needitem buch btb_cinematic:nether_key      nur mit Schlüssel in der Hand
```

**3. Zerstörung auf euren Bau legen** (zwei Ecken des Bereichs):
```
/btb destruction create mein_turm 100 64 100 120 140 120
/btb destruction effect mein_turm collapse     oder explode, crumble, lava, fire, wall_burst, gate
/btb destruction duration mein_turm 16         Sekunden
/btb destruction proximity mein_turm 5         löst aus, wenn jemand vorbeiläuft (optional)
/btb destruction screenfx mein_turm off        ohne Wackeln/Blitz im Bild (optional)
/btb destruction trigger mein_turm             testen
/btb destruction restore mein_turm             wieder aufbauen
```
Vor dem ersten Auslösen wird eine Sicherung gemacht, euer Bau kommt also immer zurück.
Ein Tor, das hochfährt: Eisengitter bauen, den Bereich mit `effect gate` anlegen und darüber 8 Blöcke Platz lassen.

**4. Euer Portal**: einen beliebigen Rahmen bauen, dann:
```
/btb portal create mein_portal <ecke1> <ecke2>    Portalfläche im Rahmen
/btb portal style mein_portal white
/btb portal keystone mein_portal <pos>            Schlüsselstein (Rechtsklick mit nether_key)
/btb portal link mein_portal zweites_portal
/btb portal activationscene mein_portal meine_aktivierung
```

**5. Herz oder ein anderes Item ausstellen**: an die Stelle stellen und dann:
```
/btb showcase create mein_herz
/btb showcase item mein_herz btb_cinematic:heart_of_the_nether
/btb showcase play mein_herz reveal
```

**6. Kamerafahrt an eurem Bau**: in den Zuschauermodus wechseln und `/btb camera record anflug` eingeben. Dann langsam fliegen und `/btb camera record stop` eingeben.

**7. Alles zu einer Szene zusammensetzen**: `/btb scene rec meine_szene` starten und dann nacheinander klicken oder tippen, was passieren soll. Danach `/btb scene rec stop` eingeben. Fertig ist eine Szene mit den richtigen Zeiten. Ändern könnt ihr sie im Regiepult unter **Szenen → ✎**.
Beispiele für Schritte:
```
btb camera play anflug @a[tag=kamera]
btb camera auto @p[tag=!kamera] 5 @a[tag=kamera]      Auto-Regie folgt dem Darsteller
btb camera orbit 110 70 110 20 10 15 120 @a[tag=kamera]  Kreisfahrt um euren Turm
btb effects letterbox 0.12 1
btb destruction trigger mein_turm
btb spawn btb_cinematic:herzwaechter 1 ~ ~ ~5 0
```

**8. Auslöser → Szene**: `/btb trigger action tor_innen btb scene start meine_szene`. Fertig: Wer den Bau betritt, startet den Film.

Tipp: Die Film-Set-Teile (`/btb film piece …`) sind gute Vorlagen. Bei jedem Schritt zeigt `/btb scene events <szene>`, wie es gemacht ist.

## 11c. Kino-Werkzeuge: Kamera, Bild, Ton, Zeit

Alles auch im Regiepult (F7): **Kamera** (Handkamera, Dolly-Zoom, Zeitlupe), **Effekte** (Fokus, Filmkorn,
Hitzeflimmern, dumpfer Ton, Stille), **Action → Requisiten**.

**Kamera**
```
/btb camera handheld 1 [@a[tag=kamera]]     Handkamera: leichtes Atmen/Schwanken (0.5 ruhig, 1 Doku, 1.8 Lauf) · handheld off
/btb camera dollyzoom <x y z> 4 14 4          Vertigo-Effekt: Kamera fährt von 4 auf 14 Blöcke weg, das Motiv bleibt gleich groß
/btb camera autobeat <spieler> 128 8          Auto-Regie schneidet im Takt: 128 BPM, alle 8 Schläge ein neuer Winkel
/btb slowmo 0.4 2.5                           Zeitlupe für die ganze Welt (Trümmer, Mobs, Partikel), 2,5 echte Sekunden · slowmo off
```
**Bild**
```
/btb effects focus 3 6           Fokus-Zug: Bild startet unscharf und wird in 3 s scharf (… out = wird unscharf)
/btb effects grain 0.5 2         Filmkorn + leicht abgedunkelte Ränder (0.3 leicht, 1 stark) · grain off
/btb effects haze 0.8 2          Hitzeflimmern über dem Boden · haze off  (läuft auch mit Iris-Shaderpacks)
/btb ambience grade <zone> 6A4018 0.12   Farbstimmung einer Zone: blendet beim Betreten weich ein, beim Verlassen aus
/btb ambience haze <zone> 0.8            Hitzeflimmern automatisch in dieser Zone
```
**Ton**
```
/btb sound volume 0.1 … /btb sound volume 1   Stille vor dem großen Moment, dann zurück
/btb effects muffle 6 0.85                    Dumpfer Ton wie nach einer Explosion, klart in 6 s auf
/btb sound play ear_ring 0.7 1                Pfeifen im Ohr dazu
/btb sound play white_tone 1.5 1              Heller, reiner Ton (Portal, Weißblende)
```
**Welt**
- Der **Herzwächter steigt aus dem Boden**, wenn er erscheint: Risse, Glut, Staub, dann richtet er sich auf und brüllt.
- Nach jedem **Einsturz** rieselt noch Sekunden lang Staub, einzelne Blöcke fallen nach, ein letztes Grollen.
- Wer das **Herz trägt**, sieht seinen Schlag: Der Bildrand pulsiert dunkelrot im Herzrhythmus.

**Licht und Atmosphäre (neue Blöcke)**, im Kreativ-Tab „Nether Core“ oder im Regiepult unter Action:
| Block | Wirkung |
|---|---|
| **Glutkessel** | Feuerschale auf drei Beinen: Flammen, Funken, Rauch, Licht 15 |
| **Kettenleuchter** | Eisenring mit sechs Kerzen für die Decke, kleine Flammen, Licht 15 |
| **Lichtsäule** | weicher Lichtstrahl, übereinander stapeln. Staub treibt hindurch. Man kann durchlaufen |
| **Glutriss** | Boden mit glühenden Rissen, aufsteigende Glut |
| **Nebelquelle** | flaches Gitter im Boden, aus dem Bodennebel kriecht |
| **Seelenkristall-Leuchte** | kalte blaue Kristalle, Funkeln und Seelen |

Im Film-Set sind sie schon verteilt:
- Bastion: Lichtstrahl durchs Dachloch, Leuchter, Glutkessel im Innenhof, Glutrisse, Nebel im Gang
- Lavawüste: Glutrisse
- Turm: Seelenkristalle und Glutrisse um den Altar
- Schlucht: Nebel
- Portalhalle: Lichtstrahlen durch das offene Dach, Kristalle am Portal, Leuchter
- Ausgang: Glutkessel und Glutrisse

**Requisiten**
- **Regieklappe** (`/give @s btb_cinematic:clapperboard`): Rechtsklick gibt eine laute Klappe und zeigt „Take N“. So legst du im Schnitt Bild und Ton genau übereinander. Schleichen + Rechtsklick setzt das ganze Set für den nächsten Take zurück.
- **Glutfackel** (`/give @s btb_cinematic:ember_flare 16`): Rechtsklick dorthin, wo du hinschaust. 45 s lang gibt es echtes Licht, roten Rauch und Funken. Gut zum Ausleuchten dunkler Ecken oder als Markierung für die Darsteller.
- **Seelenkompass** (`/btb kompass` oder `/btb kompass <x y z>`): Die Nadel zeigt immer zum Herzen bzw. zum angegebenen Ort.

**Schon im Film-Set eingebaut:**
- Jeder Abschnitt hat seine eigene Farbstimmung: Bastion kalt, Lavasee orange, Wüste staubig, Turm tiefrot, Portalhalle fast weiß.
- Hitzeflimmern über Lava und Wüste, Glutfunken in der Luft.
- Der Herzschlag als Motiv: leise beim Buch, lauter am Ende der Wüste, laut am Turm.
- Handkamera bei Flucht, Wüste und Kampf; Filmkorn ab dem Anflug.
- Herz-Szene: erst Stille, dann Dolly-Zoom auf den Altar, Herzschlag. Der Herzwächter steigt aus dem Boden, beim Brüllen kurz Zeitlupe.
- Turm-Einsturz: Handkamera, Zeitlupe, dumpfer Ton mit Pfeifen im Ohr, danach Nachbeben.
- Portal: Die Musik bricht weg, ein einziger heller Ton bleibt.

## 11d. Noch mehr Kino: Tiefenschärfe, Linse, Formate, Welt-Ereignisse

**Bild** (alles auch im Regiepult unter Effekte):
```
/btb effects dof auto 0.6 1      Tiefenschärfe: Die Schärfe folgt dem verfolgten Darsteller bzw. dem Punkt in der Bildmitte
/btb effects dof 3 0.7 1         Schärfe fest auf 3 Blöcke (z. B. das Buch) · dof off
/btb effects anamorph 0.7 1      lange, feine Lichtstreifen an hellen Lampen (Kino-Linse) · anamorph off
/btb effects flashback 0.7 1.5   Rückblende: warm, ausgewaschen, weiche Ränder · flashback off
/btb effects format 2.39 1       Kinoformat (2.39, 2, 1.85, 4x3, 1x1) · format off
```
Mit Shaderpack (Iris) gibt Minecraft keine Tiefeninfo heraus. Die Tiefenschärfe hält dann den Bereich um den
Darsteller scharf und macht den Rand weich. Ohne Shader wird nach echter Entfernung scharf gestellt.

**Für die Crew:**
```
/btb mark add <name> [farbe]     Markierung am Boden (X + Ring + leuchtender Punkt), nur für die Crew sichtbar
/btb mark remove <name> · mark clear · mark hide · mark show
```
Spieler mit dem Tag `kamera` sehen Markierungen nie, im Clean Feed (F8) sind sie auch weg. Steht man darauf, erscheint der Name.
Farben: green, yellow, orange, red, cyan, blue, purple, magenta, gold, white …

**Welt-Ereignisse:**
```
/btb ausbruch <x y z> [sek] [stärke]   Lava bricht aus dem Boden: Feuerfontäne, Funkenregen, Rauchsäule, Beben
/btb ghastcrash <x y z>                ein brennender Ghast stürzt in deine Blickrichtung ab und schlägt auf (geht auch auf „Friedlich“)
/btb wind <richtung|~> <0..1>          Wind für alle Partikel (Asche, Rauch, Funken, Flammen) · wind off
/btb destruction warn <id> <sek>       vor dem Einsturz rieselt Staub und es knirscht (Decke, Wand …)
```
- **Herzwächter besiegt:** Der Schlüssel fällt nicht einfach herunter. Er steigt langsam auf und schwebt leuchtend, bis ihn jemand nimmt.
- **Das alte Buch** (`/give @s btb_cinematic:heart_book`): Rechtsklick öffnet die Seite mit der Botschaft.
- **Herzschlag nach Nähe:** Am Turm schlägt das Herz schneller und lauter, je näher man kommt. Jeder Spieler hört seinen eigenen Rhythmus.
  Für eigene Zonen: `/btb ambience beatnear <zone> on`.

**Im Film-Set eingebaut:**
- Kinoformat 2.39 beim Anflug
- Tiefenschärfe auf das Buch
- Rückblende, wenn die Botschaft wiederkommt
- Tiefenschärfe und Lichtstreifen bei der Herz-Szene, Lichtstreifen am Portal
- Wüste: Wind, ein Ghast stürzt brennend ab, die Lava neben den Steinen bricht aus
- Bastion: Ein Stück Gangdecke kommt nach dem Buch herunter (mit Warnung)
- Markierungen für Start, Buch, Tor und Schlüsselstein

## 11e. Bild-im-Bild: die Sicht eines Darstellers einblenden

**Echtes Bild-im-Bild (läuft gleichzeitig zum Hauptbild):**
1. Ein **zweites Minecraft** (zweiter Account) betritt dieselbe Welt. Im Einzelspieler: *Für LAN öffnen*. Es kann auf demselben PC laufen, mit kleiner Sichtweite.
2. `/tag <Name des zweiten Spielers> add pip`
3. In **OBS** in deiner Aufnahme-Szene eine **Fensteraufnahme** des zweiten Minecraft-Fensters anlegen, die Quelle **`BTB PiP`** nennen und ausblenden (Auge aus).
4. Im Regiepult unter **Aufnahme** die OBS-Verbindung eintragen (wie für die Pause).
5. Einblenden:
```
/btb pip <Darsteller> [tr|tl|br|bl] [größe 0.15–0.5]     z. B. /btb pip Steve tr 0.3
/btb pip off
```
Was dann passiert:
- Das zweite Fenster wird Zuschauer und sieht durch die Augen des Darstellers, ohne Hotbar und Hand.
- OBS zeigt die Quelle in der gewählten Ecke in passender Größe.
- Im Hauptbild erscheint dort ein feiner Rahmen mit dem Namen des Darstellers.

Das geht auch in Szenen und Cues, zum Beispiel als Schritt `btb pip Steve br 0.25` bei Sekunde 12 und `btb pip off` bei Sekunde 18.

**Ohne zweites Fenster, als Sicht-Schnitt:**
```
/btb camera pov <Darsteller> [sekunden]     die Aufnahme-Kamera schaut ein paar Sekunden durch seine Augen, dann zurück
```
Beides findest du im Regiepult unter **Kamera**.

## 11f. Boss-Kampf, Farbkorrektur, Ego-Kamera, Kreaturen und Dreh-Werkzeuge

**Herzwächter-Kampf in drei Phasen.** Jeder große Angriff kündigt sich vorher an, so ist er im Film lesbar und man kann ausweichen.
- **Phase 1 – Faustschlag:** Die Fäuste glühen, dann schlägt er auf den Boden. Eine Schockwelle rollt nach außen, Bodenrisse mit Feuer rasen auf die Spieler zu.
- **Phase 2 (unter der Hälfte) – Das Herz bricht auf:** Er bäumt sich auf und brüllt, roter Puls, Schockwelle. Danach erscheinen glühende Kreise unter den Spielern, kurz darauf schießen Lavasäulen heraus.
- **Phase 3 (unter einem Viertel) – Felswurf:** Er reißt einen Felsbrocken aus dem Boden, stemmt ihn hoch und wirft ihn dorthin, wohin der Spieler läuft.

Darsteller im Kreativmodus werden nicht verletzt, aber trotzdem weggeschleudert. Der Shot sieht also gleich aus. Mit `still` greift er nicht an.

**Farbkorrektur (LUT):**
```
/btb effects lut nether 0.8 2      eingebaute Looks: nether, film, kalt, bleach, nacht, glut
/btb effects lut meinlook 1        eigene .cube-Datei aus DaVinci Resolve/Premiere: config/btb_cinematic/luts/meinlook.cube
/btb effects lut off 2
```

**Ego-Kamera (Bodycam):** `/btb camera ego <Darsteller>`. Die Kamera sitzt in den Augen des Darstellers: der Kopf wippt beim Gehen, dreht ein wenig nach, man spürt den Atem. Beenden mit `/btb camera stop`.

**Kreaturen** (eigene Modelle):
- **Glutling:** kleiner Glutgeist mit Flammenflügeln, schwirrt durch Wände und setzt in Brand. `/btb spawn btb_cinematic:glutling 5 ~ ~2 ~ 3`
- **Aschekriecher:** niedriger, sechsbeiniger Krabbler mit glühenden Augen, klettert an Wänden. `btb_cinematic:aschekriecher`
- **Seelenwisp:** harmloses, schwebendes Seelenlicht, lautlos. `btb_cinematic:seelenwisp`

**Portalraum** (neue Blöcke):
- **Portalfokus:** schwebender Kristall, um den sich zwei goldene Ringe drehen
- **Runensäule:** mit leuchtenden Runen, zum Stapeln
- **Glyphenplatte:** Boden mit glühendem Glyphenrahmen

**Dreh-Werkzeuge:**
```
/btb scene start <szene> from 23        Szene ab Sekunde 23 starten (alles davor wird übersprungen)
/btb camera preview <fahrt> [sek]       Fahrt als leuchtende Linie, Punkte als helle Marken – Punkt verschieben: /btb camera point <fahrt> <nr>
/btb camera focus <fahrt> <nr> <blöcke> Schärfe an diesem Kamerapunkt (mit /btb effects dof auto)
/btb camera smooth <fahrt> [1–5]        selbst geflogene Fahrt glätten (Bildstabilisator)
/btb countdown 3 <szene>                3-2-1-ACTION nur bei den Darstellern (nie beim Kamera-Spieler), dann startet die Szene
/btb probe an|aus                       Probe: nichts wird zerstört, keine Gegner, kein Ausbruch – Kamera, Effekte, Markierungen laufen
```

**Welt:**
```
/btb wetter asche|glutsturm|klar [stärke] [sek]   Ascheregen oder Glutsturm zieht auf (mit Wind, Tönung, Flimmern)
/btb blutmond an|aus [sek]                        alles wird tiefrot und dunkel
/btb licht <von> <bis> <0–15> [sek]               Lampen der Mod (und Licht-Blöcke) in einem Bereich langsam dimmen/aufdrehen
/btb destruction chain <a> <b> <sek>              Kettenreaktion: wenn a fällt, folgt b nach der Zeit
```
Nach Einstürzen, Explosionen und Lava-Ausbrüchen glimmen die Trümmer noch eine Minute weiter: Rauch, Glut, Lavaspritzer.

Im Film-Set eingebaut:
- Portalraum: Portalfoki, Runensäulen, Glyphenkreis um den Schlüsselstein, Seelenwisps
- Aschekriecher beim Ausbruch in der Bastion
- Glutlinge am Ausgang

## 11g. Replay, 4K-Export, Doubles, Gesten und Statisten

**Replay: einmal spielen, beliebig oft filmen**
```
/btb replay record take1      alle Darsteller (ohne Tag kamera) werden aufgezeichnet: Wege, Blick, Ducken, Gegenstände, Schläge;
                              dazu Kreaturen in der Nähe, gestartete Szenen, Einstürze, Ausbrüche, Ghast-Abstürze, Wächter-Angriffe
/btb replay stop              Aufnahme beenden (wird gespeichert)
/btb replay play take1        Set wird zurückgesetzt, die Darsteller werden kurz Zuschauer, Puppen mit ihren Skins spielen alles nach
/btb replay play take1 from 12   ab Sekunde 12
/btb replay list · delete <name>
```
Während das Replay läuft, filmst du mit jeder Kamera: Fahrten, Auto-Regie, Ego-Kamera und so weiter.
Am Ende kommen die Darsteller automatisch zurück. Trigger-Zonen und neue Gegner sind während des Replays aus,
denn die Puppen *sind* die Aufnahme.

**Film-Export: Bild für Bild, ruckelfrei, in 4K**
```
/btb export film1 30 4k 60 take1     30 Sekunden vom Replay „take1“, 3840×2160, 60 FPS
/btb export film1 30 1080p 30        ohne Replay: das, was gerade passiert (Szenen, Kamerafahrten)
/btb export stop
```
Wie der Export arbeitet:
- Das Spiel wird angehalten und genau einen Tick pro 3 Bilder weitergeschaltet (bei 60 FPS).
- Jedes Bild wird gerendert und gespeichert, egal wie langsam der PC ist. Das Ergebnis läuft trotzdem perfekt flüssig.
- Bilder: `.minecraft/btb_export/<name>/frame_000000.png …`
- Ist **ffmpeg** installiert, entsteht automatisch `<name>.mp4` in der Zielgröße. Ohne ffmpeg steht der passende Befehl im Chat.
- **Ohne Ton:** Musik und Sounds kommen im Schnitt dazu.
- Funktioniert im Einzelspieler bzw. als Host einer LAN-Welt.
- Tipp: Fenster auf 1920×1080 stellen, dann sitzen Texte und Balken pixelgenau.

**Doubles: Ein Darsteller spielt sich selbst ein zweites Mal**
```
/btb double record solo1         du spielst deine Rolle (nur du bist Darsteller)
/btb replay stop
/btb double solo1 <DeinName>     deine Aufnahme läuft als Puppe neben dir – du spielst live die zweite Rolle
/btb double stop
```

**Gesten und Statisten** (für Puppen, Doubles und Statisten):
```
/btb statist <Spielername> [x y z]    Figur mit dem Skin eines Spielers (online) – sonst Standard-Skin
/btb geste <Ziel> <geste> [sek]       ducken, liegen, kriechen, zielen (Bogen), abwehren (Schild), arm (Arm heben),
                                      fernrohr, winken, normal
/btb statist clear
```
Beispiel: `/btb geste @e[type=minecraft:mannequin,limit=1,sort=nearest] winken 4`. Gesten der echten Darsteller
(Bogen spannen, Schild heben, Ducken) zeichnet das Replay ganz normal auf.

## 11h. Replay-Editor: Timeline, Kamera-Keyframes, Export (wie ReplayMod)

Ein aufgenommenes Replay lässt sich wie in einem Schnittprogramm bearbeiten – ohne dass jemand die Szene noch einmal spielen muss.

1. Take aufnehmen: `/btb replay record take` … spielen … `/btb replay stop`
2. Editor öffnen: **F6**, `/btb replay editor [name]` oder im Regiepult (F7) → Kamera → Replay → „✂ Editor“.
   Das Replay lädt pausiert, die Darsteller werden Zuschauer, die Puppen stehen bei 0:00.
3. **Timeline** (unten): klicken/ziehen = spulen, Mausrad = zoomen. Grüne Balken = Darsteller, rote/orange/grüne Striche = Ereignisse (Wächter-Angriff, Einsturz, Szenenstart).
   Beim Spulen wird das Set zurückgesetzt, laufende Szenen springen an die richtige Stelle, schon passierte Einstürze werden ausgelöst.
4. **Kamera**: „✈ Fliegen“ (oder Esc) schließt das Panel – du fliegst frei, eine kleine Timeline bleibt unten sichtbar.
   Guten Blickwinkel suchen → **K** setzt einen Keyframe an der aktuellen Zeit. Mit ←/→ (1 s) weiterspulen, nächster Blickwinkel, K …
   **H** schaltet zwischen „frei“ und „◆ Keyframes“ (Kamera fährt die Keyframes ab), **↑** = Play/Pause.
5. **Keyframe bearbeiten** (im Panel anklicken): ziehen = Zeit verschieben, Rechtsklick = löschen, Doppelklick = hinspringen.
   - Modus pro Abschnitt: **Fahrt** (weiche Kurve), **Schnitt** (harter Cut zum nächsten Keyframe), **Fahrt+Blick** (Kamera schaut automatisch auf den nächsten Darsteller), **Frei** (keine Steuerung)
   - FOV−/FOV+ (Brennweite), ↺/↻ (Kamera neigen), „Sicht“ (aktuelle Sicht übernehmen), „Hin“ (hinspringen und dorthin stellen)
6. **Tempo**: ¼× bis 4× – die ganze Welt läuft langsamer/schneller (auch Partikel und Szenen).
7. **Export**: Name, Auflösung (1080p/1440p/4K), FPS (24/30/60), Bereich mit **[ In** / **Out ]** (oder Tasten I/P, Rechtsklick in linke/rechte Timeline-Hälfte) → „● Exportieren“.
   Das Spiel friert ein und rendert Bild für Bild mit der Keyframe-Kamera; danach liegt `<name>.mp4` in `.minecraft/btb_export/<name>/` (MP4 braucht ffmpeg, sonst PNG-Bilder).

Keyframes und Export-Einstellungen werden neben dem Replay gespeichert: `config/btb_cinematic/replays/<name>.cam.json`.
Der Editor läuft nur im Einzelspieler bzw. als Host einer LAN-Welt.

## 11i. Trailer zu eurem Song (2:05)

Der Trailer ist eine fertige Szene im kompletten Film-Set. Jeder Schnitt liegt auf einem Schlag des Songs „HoN-Trailer“ (161,5 BPM):
0–5,8 s Intro aus dem Schwarz · 5,8–29 s Aufbau (Bastion, Buch, Gang, Turm am Horizont) · 29,09 s Drop: Lavawände, Gegner,
brennende Brücke, Ghasts, Ausbruch · 52,4 s Festung und Herzwächter (Aufsteigen, Herz, Schlag, Brüllen, Lavasäulen, Felswurf,
Schlüssel) · 76,7 s der Turm stürzt ein, das Tor schwingt auf, Schlucht, Portal zündet · 100 s durch das Portal, Montage,
120,4 s letzter Schlag → Schwarz → „BEHIND THE BLOCKS – Demnächst“.

1. Song in den Ordner `.minecraft/config/btb_cinematic/music/` legen und **trailer.ogg** (oder trailer.mp3, dann braucht es ffmpeg) nennen.
   Die Mod baut daraus beim Start ein Ressourcenpaket „btb_musik“ und schaltet es ein. Lautstärke: Regler **Jukebox**.
2. Ganzes Set bauen (`/btb film setup`), **Sichtweite mindestens 16**.
3. Live ansehen: `/btb film trailer` (setzt alles zurück, du wirst Zuschauer und fliegst mit den Einstellungen mit).
4. Als Video: `/btb film trailer export` (4K/60) oder `/btb film trailer export 1080p 30`.
   Bild für Bild gerendert, der Song wird am Ende von ffmpeg exakt ab Bild 0 darunter gelegt → `.minecraft/btb_export/trailer/trailer.mp4`.

Eigene Musik allgemein: jede Datei in `config/btb_cinematic/music/` heißt im Spiel `musik.<name>`, z. B. `/btb sound music musik.trailer 0 once`.
Wächter-Angriffe auf Kommando (für eigene Trailer): `/btb waechter angriff schlag|bruellen|saeulen|wurf`.

## 12. Probleme?

| Problem | Lösung |
|---|---|
| Schwarzer Bildschirm nach einer Szene | kommt nach 5 s von selbst zurück; sofort: `/btb effects clear` (Chat geht mit T auch im Schwarz) |
| Trigger löst nicht aus | Eine Zone feuert beim **Hineinlaufen** (raus und wieder rein). Testen: `/btb trigger fire <name>` |
| Klick im Menü macht nichts | Standard ist „Klick: beim Schließen“: Die Aktion startet, sobald das Menü zu ist. Zum Testen oben rechts „Klick: sofort“ |
| Kein Pause-Knopf in OBS | OBS bietet Pause nur bei bestimmten Einstellungen: *Einstellungen → Ausgabe* → bei „Einfach“ die **Aufnahmequalität** auf etwas anderes als „Gleich wie Stream“ stellen (z. B. „Hohe Qualität, mittlere Dateigröße“), oder Ausgabemodus „Erweitert“. Geht es trotzdem nicht, macht die Mod es automatisch anders: Sie **stoppt** die Aufnahme, solange das Menü offen ist, und startet sie danach neu (jeder Take = eigene Datei). Der Knopf **„OBS testen“** unter Aufnahme zeigt den Status. Status steht im Regiepult unter Aufnahme. In OBS: Einstellungen → Ausgabe, eine Aufnahmequalität wählen, die nicht „wie Stream“ ist |
| Grafikfehler mit Shadern (Iris) | Leuchtschicht und Umriss sind dann automatisch aus; Unschärfe wird zu einem weichen Schleier |
| Befehl mit Farbe `#ff6040` geht nicht | In Befehlen ohne `#`: `ff6040` (in JSON-Dateien mit `#`) |
| Gegner verschwinden sofort | Schwierigkeit „Friedlich" → `/difficulty easy` |
| `mutantmonsters:…` unbekannt | Mutant Monsters + Puzzles Lib + Forge Config API Port auf Server **und** Client? |
| Mitspieler sieht keine Effekte | Mod fehlt auf seinem Client |
| Szene startet nicht | `/btb scene problems` zeigt die fehlerhafte Zeile |
| Monitor/Chat im Video | F8 (Clean Feed); Monitor nur mit Numpad * halten |
| Portal-`fill` meldet „No closed frame found" | Rahmen hat eine Lücke, oder die Position liegt nicht im Inneren |
| Alles kaputt nach Test | `/btb resetall`, notfalls `/btb destruction restore` |
