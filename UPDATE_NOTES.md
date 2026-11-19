# BTB Cinematics – Update Notes

## 1.0.0 – Sprache & Performance

### 🌍 Komplett Deutsch oder komplett Englisch
- Die ganze Mod gibt es jetzt in **Deutsch und Englisch**: Regiepult (alle 13 Tabs), Replay-Editor,
  Export, Chat-Meldungen, Tooltips, Hover-Texte, Fehler.
- **Kein Mischmasch mehr**: Englische Reste im deutschen Menü sind übersetzt (z. B. Wackeln, Enthüllen,
  Explodieren, Stopp, Seitenzahlen, Hinweise).
- **Server-Einstellung** in `config/btb_cinematic/settings.json`:
  - `"auto"` (Standard): jeder Spieler sieht die Mod in seiner Minecraft-Sprache (Deutsch → Deutsch, alles andere → Englisch)
  - `"de"` / `"en"`: für alle Spieler fest
- Neuer Befehl `/btb sprache <auto|de|en>` (auch `/btb language`) – wird gespeichert und sofort an alle
  Spieler geschickt, kein Neustart nötig.
- Eigene Inhalte (Szenen-Namen, Credits-Texte, Cue-Namen) bleiben so, wie ihr sie geschrieben habt.

### 🧭 Neues Regiepult – einfacher und übersichtlicher
- **Zwei Ansichten zum Umschalten:** „Tabs: kompakt“ (8 Tabs nach Aufgabe mit Unterseiten) oder „Tabs: alle“
  (jeder Bereich als eigener Tab wie früher, scrollbar wenn das Fenster zu klein ist). Die Wahl wird gemerkt.
- **Klicks wirken sofort.** Der alte „beim Schließen“-Modus heißt jetzt **🎬 Dreh-Modus** und färbt den Rahmen.
- **Statuszeile** oben: was gerade läuft (Szenen, Replay-Aufnahme, Export, Shot) + **■ Alles stoppen**.
- **Kurze Rückmeldung** nach jedem Klick im Menü (grün/rot) statt nur im Chat.
- **Suchfeld** (Strg+F): findet Knöpfe in allen Bereichen.
- **Einsteiger/Profi**: Einsteiger sieht nur das Wichtigste, Profi alle Feinheiten.
- **Tooltips in Klartext**; den Befehl zeigt Shift. Klarere Knöpfe („Hin“, „Reset“, „Bearbeiten“ statt Symbolen).
- **Leere Bereiche erklären den nächsten Schritt**, mit passendem Knopf.
- **Eingabefelder klappen sich automatisch ein**, wenn sonst kaum Platz für die Liste bleibt.
- **Assistenten**: „Ersten Film drehen“, „Replay & Export“, „Kamerafahrt bauen“ – Schritt für Schritt.
- Tab-Namen werden nie mehr abgeschnitten (die Spalte passt sich an), die untere Leiste wird bei kleinen Fenstern kürzer.

### ✨ Verbessert
- **↶ Rückgängig (Strg+Z)** für Szenen, Kamerapfade, Texte/Credits, Dialoge u. a.
- **Automatische Versionen**: jede gespeicherte Datei behält ihre letzten 25 Stände, im Menü wiederherstellbar.
- **Zeitleiste im Baukasten**: Schritte als Marker, per Maus verschieben, Klick setzt die Zeit, „▶ ab Bei“.
- **Kamerapfade**: Linie in der Welt live an/aus (👁), Punkte bearbeiten (Hin, Hierher, schneller/langsamer, löschen)
  und eine **Draufsicht** mit laufendem Punkt als Vorschau.
- **Verständliche Fehler** bei Tippfehlern, mit „meintest du …?“.

### 🎥 Neu
- **Storyboard**: Shots mit Titel, Notiz, Länge, Bild (📷 aus der aktuellen Ansicht) und optionaler Aktion;
  „🎬 Dreh starten“ / „✓ Weiter“ hakt Shot für Shot ab (Anzeige beim Filmen nur mit Numpad *).
- **Multicam im Replay-Editor**: Kameras A–D mit eigenen Keyframes, Schnitte an der Abspielposition.
- **Dialog & Untertitel** (Deutsch + Englisch, jeder Spieler in seiner Sprache), Export als **.srt**.
- **Sprech-Animation**: Statisten, die gerade sprechen, nicken, schauen sich um und gestikulieren.
- **Zeit & Wetter** als weicher Übergang (z. B. Sonnenuntergang in 20 s) – nur in der Oberwelt.
- **Statisten-Gruppen**: eine Menge spawnen und gemeinsam laufen, fliehen, jubeln, herschauen lassen.
- **Vorlagen**: Establishing, Verfolgung, Schuss-Gegenschuss, Enthüllung, Helden-Shot, Überflug, Auto-Regie.
- **Projekt-Export/-Import** als eine zip-Datei (ohne OBS-Passwort).
- **Rollen** (Regie, Kamera, Darsteller) und **Souffleur**: Darsteller sehen „In 3 … 2 … 1: nach links laufen“.

### 🎬 Mehr per Menü statt per Befehl
- **Replays im Regiepult** (Tab „Aufnahme“): Namen eintippen, „● Aufnehmen“, „■ Stopp“. Alle gespeicherten
  Replays stehen mit Länge in der Liste: ▶ Abspielen, ✂ Editor, Export 1080p oder 4K, ✕ Löschen
  (zweimal klicken, damit nichts aus Versehen weg ist). Läuft gerade eine Aufnahme oder ein Replay,
  zeigt eine Zeile das an, mit Stopp bzw. Pause.
- Neuer Befehl `/btb replay export <name> [4k|1440p|1080p] [fps]` exportiert ein ganzes Replay, ohne
  dass man die Länge eintippen muss.
- **Kamera-Tab**: „Kamera stop“ und „Stop alle“, Orbit (nah/weit um den nächsten Darsteller, um mich),
  Verfolger (Seite, Hinten, Oben, Fest), Auto-Regie, „+ Neuer Pfad“. Pro Kamerapfad zusätzlich
  ■ Stopp, ⌖ zum Startpunkt und ✕ Löschen.

### ⚡ Performance
- Übersetzungen werden zwischengespeichert – Menüs zeichnen ihre Texte jedes Bild, ohne erneut zu suchen.
- Tiefenunschärfe mit Iris-Shadern: die Shader-Puffer werden nicht mehr jedes Bild per Reflection gesucht.
- Große Einstürze (Turm mit Hunderten Trümmerteilen): weniger Netzwerk-Updates, die Bewegung bleibt
  durch Interpolation flüssig.
- Gespawnte Gegner entfernen (`/btb resetall`, Szenen-Stopp) durchsucht nicht mehr alle Entities der Welt.
- Regiepult öffnet wieder zuverlässig bei vielen Szenen (größere Cue-Pakete erlaubt).

### 🐛 Fixes
- Wiederherstellen nach `destruction create` an neuer Stelle stellte 0 Blöcke wieder her (altes Backup) – behoben.
- Tiefenunschärfe war überall gleich unscharf (Reversed-Z in 26.3) – behoben, auch mit Iris-Shadern.
- Lens Flare: stabil auf große Entfernung und beim Drehen der Kamera.
