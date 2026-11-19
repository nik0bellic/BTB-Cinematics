# Plugin-API für andere Mods

Andere Mods können eigene Timeline-Events, Overlays und Menüs im Takt der BTB-Szenen bauen.

## Server/Common: Entrypoint `btb_cinematic`

`fabric.mod.json` der eigenen Mod:
```json
"entrypoints": { "btb_cinematic": ["com.example.MyBtbPlugin"] }
```
```java
public class MyBtbPlugin implements BtbCinematicPlugin {
    @Override
    public void register(BtbCinematicApi api) {
        api.registerEventType(BuiltinEvents.simple("mymod_announce", "Nachricht an alle. {text}", p -> {
            String text = p.requireString("text");           // wird beim Laden der Szene geprüft
            return ctx -> ctx.server().getPlayerList().getPlayers()
                    .forEach(pl -> pl.sendSystemMessage(Component.literal(text)));
        }));
        api.subscribe(SceneMessages.Started.class, m -> System.out.println("Szene läuft: " + m.sceneId()));
    }
}
```

`BtbCinematicApi`:
| Methode | Zweck |
|---|---|
| `registerEventType(CinematicEventType)` | eigenes Event für Szenen-JSON |
| `subscribe(Class<T>, Consumer<T>)` | Bus-Nachrichten (Szene gestartet/gestoppt/pausiert/zurückgesetzt …) |
| `startScene / stopScene / resetScene(id)` | Szenen steuern |
| `showHud(JsonObject, players)` · `hideHud(id, fade, players)` | HUD-Texte (gleiches Format wie `hud/*.json`) |
| `screenEffect(EffectSpec, players)` · `cameraShake(...)` | Bildschirmeffekte |
| `sendClientCue(type, data, players)` | eigene Daten an den Client (s. u.) |
| `playShowcase(id)` · `activatePortal(id, instant)` · `triggerDestruction(id)` | Module auslösen |
| `engine()` | voller Zugriff auf Module (`engine.module(PortalManager.class)` …) |

Der Parameter-Parser (`JsonParams`) prüft Typen schon beim Laden der Szene. Fehler erscheinen in
`/btb scene problems` und nicht erst beim Dreh.

## Client: eigene Overlays und Menüs synchron zur Szene

```java
BtbCinematicClientApi.registerCue("mymod:map_reveal", (sceneId, tick, data) -> MyOverlay.show(data));
// in der Render-Methode:
double t = BtbCinematicClientApi.sceneTime("heart_discovery", partialTick); // friert bei Pause ein
```
Auslösen aus einer Szene:
```json
{ "time": 40, "type": "client_cue", "cue": "mymod:map_reveal", "data": { "region": "bastion" } }
```
Cues eines Ticks kommen gebündelt in einem Paket an. So laufen eigene Overlays exakt im selben
Frame wie BTB-Blenden und Texte. Eigene Overlays am besten in Fabrics HUD-Callback zeichnen; die
BTB-Overlays sind auch bei F1 sichtbar (siehe `HudMixin`).
