# Springreiten-Trainer – Architektur-Vertrag (technische Spec)

Gilt für SRT-001 bis SRT-006. Fachliches „Was": `docs/features/springreiten-trainer/concept.md`
(Regeln Rn) und die Tickets in `docs/features/springreiten-trainer/tasks/`. Dieses Dokument regelt
das „Wie": Module, Schnittstellen, Koordinaten, Datei-Ownership. Abweichungen erst hier ändern.

## Stack

- Vite 8 (statisch, `base: './'`), Vanilla-JS ES-Module (kein TypeScript, keine Typprüfung als Gate),
  three.js 0.186 (`import * as THREE from 'three'`).
- Tests: Vitest (`src/**/*.test.js` und `tests/**/*.test.js`, Umgebung `node`; DOM-Tests per `// @vitest-environment jsdom`).
- Lint: ESLint 10 flat config, Format: Prettier (singleQuote, printWidth 100).
- Smoke: Playwright gegen `vite preview` (Port 4173).
- PWA: vite-plugin-pwa (generateSW, kein skipWaiting/clientsClaim → neue Version erst nach
  Schließen aller Tabs).
- **Keine Asset-Dateien.** Texturen nur zur Laufzeit (CanvasTexture / DataTexture / Vertex-Farben /
  Shader), Klänge nur per WebAudio-Synthese, Schrift = Systemschrift. Einzige Datei-Ausnahme:
  `public/icon.svg` (+ beim Build erzeugte PNGs in `public/generated/`).

## Koordinaten und Einheiten

- Meter, Sekunden, Radiant. three.js: Y nach oben.
- Reitplatz zentriert im Ursprung: `x ∈ [-20, 20]` (Breite 40 m), `z ∈ [-35, 35]` (Länge 70 m),
  Konstanten `ARENA.width = 40`, `ARENA.length = 70` in `src/domain/sim/tuning.js`.
- Blickrichtung (heading) `h`: Vorwärtsvektor `f = (sin h, 0, cos h)`; `h = 0` blickt nach +Z.
  Modelle sind so gebaut, dass sie in ihrem lokalen Raum nach **+Z** schauen; `object.rotation.y = h`.
- Rechts vom Reiter: `r = (-cos h, 0, sin h)`.

## Hindernisse (Daten, geteilt von Sim, View, Parcours)

```js
// Ein springbares Element (Kreuz, Steilsprung, Oxer oder ein Teil einer Kombination)
Element = {
  id: 'f3a',            // eindeutig im Layout
  kind: 'cross' | 'vertical' | 'oxer',
  height: 0.6,          // m, Höhe der obersten Stange (Kreuz: Höhe des Kreuzungspunkts)
  spread: 0,            // m, Tiefe Oxer (Abstand vordere/hintere Stange), sonst 0
  x: 0, z: 0,           // Mittelpunkt der Grundfläche
  rot: 0,               // Sprungachse n = (sin rot, 0, cos rot); Stangen liegen quer dazu
}
// Ein nummeriertes Hindernis
Obstacle = {
  number: 3,            // nur im Parcours; im freien Modus null
  elements: [Element] | [ElementA, ElementB],   // Kombination: a dann b in Richtung +n
  directed: true,       // Parcours: true = nur Sprung in Richtung +n gilt; freier Modus: false
}
```

- Stangenlänge (Abstand der Ständer innen) `POLE_LENGTH = 3.5`, Ständerbreite ca. 0,15 m.
- Lokales Element-System: Achse `n` (Sprungrichtung), Querachse `t = (-cos rot, 0, sin rot)`
  (zeigt nach rechts, wenn man in +n springt). Fahnen im Parcours: **rot rechts** (+t-Seite),
  **weiß links** (−t-Seite).
- Vorderkante relativ zur Anreitrichtung `dir ∈ {+1, −1}`: Stangen bei `n·(p−c) = ±spread/2`.
- Kombinations-Abstand a→b (Mitte zu Mitte): `COMBI_DISTANCE` (abgeleitet in `tuning.js`:
  `2 · TUNING.course.takeoffLanding + TUNING.course.stride`, ca. 7,3 m).
- Rails: Kreuz = 2 gekreuzte Stangen (zählt als eine fallende „Stange" = rail 0, beide fallen),
  Steilsprung = obere Stange rail 0 (+ untere feste Füllstange, fällt nie),
  Oxer = vordere obere rail 0, hintere obere rail 1.

## Schichten (Clean Architecture, siehe CLAUDE.md)

Abhängigkeiten zeigen nur nach innen: `adapters → application → domain`; `shared` darf jede Schicht
nutzen. ESLint (`no-restricted-imports`, `no-restricted-globals`, `no-restricted-properties`)
erzwingt die Grenzen.

| Pfad | Inhalt | Darf importieren |
| --- | --- | --- |
| `src/shared/` | reine Helfer ohne Seiteneffekte (Event-Emitter, `math.js`: `clamp`, `isPlainObject`) | nichts außer `shared` |
| `src/domain/sim/` | Reit-/Sprung-Simulation, Spielwerte (`tuning.js`), Geometrie, seedbarer Zufall | `domain`, `shared` |
| `src/domain/course/` | Parcours-Layouts, freie Aufstellung, Ritt-Zustandsautomat, Wertung (der geometrische Layout-Prüfer `layout-check.js` ist reines Test-Orakel und liegt in `tests/support/`) | `domain`, `shared` |
| `src/domain/progress/` | Fortschritt, Bestleistung, Freischaltung, Auszeichnungen | `domain`, `shared` |
| `src/domain/horse/` | Pferdename, Fellfarben, Abzeichen (Werte und Regeln) | `domain`, `shared` |
| `src/application/save-schema.js` | Spielstand-Bereiche mit Bereinigung (Regel 47), Einstellungsfelder | `domain`, `shared`, `application/languages.js` |
| `src/application/languages.js` | `LANGS` – die angebotenen Sprachen (einzige Quelle für Schema und UI) | – |
| `src/application/graphics-levels.js` | `GRAPHICS_LEVELS` – die wählbaren Grafikstufen (einzige Quelle für Schema, Einstellungs-Dienst, Grafik-Presets und Pferd) | – |
| `src/application/settings-service.js` | Anwendungsfälle für Einstellungen: der **einzige Schreiber** des Bereichs `settings` (siehe „Einstellungs-Dienst“) | `domain`, `application`, `shared` |
| `src/application/settings-schema.js` | Registriert Einstellungsfelder (Grafik, Kamera, Hilfe, Klang) und die Bereiche `horse`/`progress` im Spielstand-Schema | `domain`, `application`, `shared` |
| `src/application/ride-session.js` | Anwendungsfall „Ritt": Sim-Schritt, Ereignisse, Stangen-Wiederaufbau, Sprungzähler + Sofort-Auszeichnungen, Absprung-Hilfe, Rückmeldungen | `domain`, `application`, `shared` |
| `src/application/modes/` | Modus-Strategien `free-mode.js`, `course-mode.js` (Uhr, HUD-Modell, Rittende-Ergebnis). **Modi speichern nichts**: nur die Ritt-Sitzung schreibt, und zwar über den Fortschritts-Dienst | `domain`, `application`, `shared` |
| `src/application/progress-service.js` | Sprung zählen, Ritt abschließen, Fortschritt löschen (über Port `store`) | `domain`, `shared` |
| `src/adapters/storage/` | localStorage-Store (implementiert Port `store`) | innen |
| `src/adapters/platform/` | WebGL-Prüfung, Touch-Modus, Hochformat, PWA | innen |
| `src/adapters/input/` | Tastatur, Touch-Bedienung (nipplejs) → `InputState` | innen |
| `src/adapters/view3d/` | Renderer, Grafikstufen, Welt, Hindernisse, Pferd/Reiter, Kamera, Engine | innen |
| `src/adapters/audio/` | WebAudio-Synthese | innen |
| `src/adapters/ui/` | App-Rahmen, Bildschirme (`screens/`, z. B. `screens/ride-screen.js`), Einstellungs-Abschnitte (`settings-sections.js`, `audio-wiring.js`), i18n + Texte, Styles | innen |
| `src/main.js` | Composition Root (verdrahtet Store, Einstellungs-Dienst, App, Bildschirme; Startfehler → allgemeine Fehlermeldung) | alles |
| `tests/support/` | Test-Hilfen, die nie in den Produktions-Build gelangen: `sim-utils.js`, `test-ports.js` (Fake-Store/-Uhr), `test-host.js`, `autopilot.js` (+ Fahrbarkeits-Test), `layout-check.js` (+ Test; Layout-Orakel für `courses.test.js`). Vitest-Include und ESLint-Override sind dafür eingerichtet | alles |

### Ports (als Parameter injiziert)

- `store`: `{ get(section), update(section, fn), onChange(section, fn) }` – Adapter: `adapters/storage`.
  `update` gibt den **bereinigten** neuen Bereich zurück (nicht, was die Funktion geliefert hat).
- `clock`: `{ nowIso() }` für Auszeichnungs-Datum; Zeit im Spiel kommt als `dt`.
- `rng`: `() => number` in [0, 1) – Domain nutzt nie `Math.random()` direkt.
- Die Ritt-Sitzung liefert Ereignisse/Kommandos (`endGallop`, `badges`, `feedback`,
  `finished`, Klang-Ereignisse); der UI-Adapter setzt sie um (Eingabe, Toasts, Klang, Bildschirmwechsel).

### Ritt-Sitzung (application/ride-session.js)

```js
const session = createRideSession({ mode, store, clock, rng });  // mode = createRideMode(params)
session.restart();                       // Startpose, Stangen auf, Modus zurücksetzen
const out = session.step(dt, input);     // input = InputState ohne pause/camera
// out = { events (Sim), commands: [{type:'endGallop'} | {type:'resetTouchGallop'} |
//         {type:'feedback', key} | {type:'badges', ids} | {type:'sound', name: 'takeoff'|'landing'|'railDown'|'finishSignal'} |
//         {type:'finished', screen, params: {courseId, result, isNewBest, unlockedCourse, awarded}}] }
// On a real finish the session sends {type:'sound', name:'finishSignal'} right before 'finished';
// the ride screen plays no finish sound of its own. At most one railDown sound per element per jump.
session.dispose()                        // stops the store listener (settings cache)
session.view  // { horse, rails, fallDirs (Map elementId → ±1, last fall direction), aid: null|{elementId, dir, zone}, highlight, finishMarked,
              //   lines, hud: mode-spezifisches Modell (reine Daten) }
```
Weitere Application-Dienste: `progress-service.js` (recordJump, finishRide, resetProgress),
`horse-service.js` (Name, Aussehen), `course-catalog.js` (Auswahl, Freischaltung),
`badge-overview.js`, `result-summary.js`, `modes/index.js` (`createRideMode(params)`; Modi bekommen
keinen Store, Fortschritt schreibt nur die Sitzung über den Fortschritts-Dienst).
Pause, Kamera-Umschaltung, Auto-Pause und DOM bleiben im UI-Adapter
(`adapters/ui/screens/ride-screen.js`). `session.view` und `view.aid` sind **wiederverwendete
Objekte** (kein Allokieren pro Frame): Felder lesen, die Objekte nicht über Frames speichern; leere
`commands`/`events`-Arrays sind eingefroren (nie `push`). Der Modus bekommt einen `host`
(`feedback`, `rebuildIn`, `rebuildNow`, `cancelRebuild`); ein gezählter Abwurf bricht einen noch
ausstehenden unbewerteten 3-s-Wiederaufbau desselben Elements ab. Das HUD-Modell des Parcours ist
`{ phase, timeCs, allowedS, faults, overTime, nextLabel, missingHint }` (`nextLabel`: Nummer des fälligen Hindernisses, `"3b"` für Teil b einer Kombination, sonst `'finish'`); die Ergebnis-Zeilen
(`result-summary.js`) liefern je Fehlerart `{ count, each, points }`. Das UI rechnet nichts davon nach.

`services.ride` (`{ session, engine, screen }`, gesetzt vom Ritt-Bildschirm, beim Verlassen entfernt)
ist **nur für den Test-Hook** (`adapters/platform/test-hooks.js`, nur mit `?testhooks`): er liest
daraus einen Schnappschuss. Produktionscode liest es nicht.
Der Test-Hook bietet außer Lesefunktionen `go(name, params)`, `setAutoLevel(level)` (ändert die
Stufe wie der Governor: Automatik bleibt an) sowie `loseContext()` / `restoreContext()`
(simulierter WebGL-Kontextverlust über `WEBGL_lose_context`, Rückgabe `false` ohne Engine oder
Erweiterung); der Schnappschuss enthält `contextLost` und `graphicsSettling` (ein Stufenwechsel
läuft noch: Schritte ausstehend, Pixel-Ratio vorgemerkt oder Shader werden kompiliert) und
`graphicsPixelRatio` (Pixel-Ratio des Renderers). `?testhooks&gpubudget=<MB>` ersetzt das
GPU-Speicher-Budget der Engine (`gpuBudgetOverride` in `test-hooks.js`; `main.js` legt es als
`app.services.gpuBudgetOverrideMB` ab; ohne `?testhooks` wirkungslos).

### Einstellungs-Dienst (application/settings-service.js)

```js
const settings = createSettingsService(store);   // in main.js einmal erzeugt, als ctx.settings verteilt
settings.get()                    // gespeicherter Stand (bereinigte Kopie)
settings.setLang('de'|'en')
settings.setGraphicsAuto(deviceLevel|null)  // „Automatisch“ an, Stufe passend zum Gerät
settings.setGraphicsLevel(level)            // manuelle Wahl, Automatik aus
settings.setAutoLevel(level)                // Governor senkt die Stufe, Automatik bleibt an
settings.setCamera('follow'|'rider')        // CAMERA_MODES in settings-schema.js
settings.setAid('free'|'course', on)
settings.setShowFps(on)                     // fps-Anzeige im Ritt (Regel 4, 44); Standard aus
settings.setVolume('music'|'sfx', v) ; settings.setMuted('music'|'sfx', m)
settings.onChange(fn) → unsubscribe
```
Kein Adapter ruft `store.update('settings', …)` direkt auf (Einstellungs-Bildschirm, Audio-
Verdrahtung, Ritt-Bildschirm/Kamera, Vorstart-Hilfe-Schalter, Engine/Grafik-Governor).

## Schnittstellen

### i18n (`src/adapters/ui/i18n.js`)

```js
registerStrings({ de: { 'menu.settings': 'Einstellungen', ... }, en: { ... } });
t('menu.settings', { name: 'Blitz' });  // {name} Platzhalter
getLang(); setLang('de'|'en'); onLangChange(fn) → unsubscribe
detectLang(navigatorLanguages) → 'de'|'en'
```
Jeder Bereich registriert seine Texte in `src/adapters/ui/i18n/<bereich>.js` (Default-Export
`{ de, en }`), importiert in `src/adapters/ui/i18n/index.js` (`STRING_AREAS`, `registerAllStrings()`).
Ein Test liest `STRING_AREAS` und prüft: gleiche Schlüssel und Platzhalter in de und en, keine
doppelten Schlüssel. Die Sprachliste `LANGS` kommt aus `application/languages.js`.

### Speicher (`src/adapters/storage/local-store.js`, `src/application/save-schema.js`)

Ein JSON-Objekt unter `localStorage['zoes-horse-farm.save']`:
```js
{
  version: 1,
  settings: { lang, graphicsAuto, graphicsLevel, camera, aidFree, aidCourse, showFps,
              musicVolume, musicMuted, sfxVolume, sfxMuted },
  horse:    { name: null|string, nameAnswered: bool, coat, marking },
  progress: { unlocked: 1..5, courses: { '1': { faults, timeCs, stars } }, jumps,
              finishedRides, badges: { [badgeId]: ISO-Datum } },
  // unbekannte Schlüssel (spätere Bereiche/Versionen) bleiben unverändert erhalten
}
```
API:
```js
const store = createStore({ backend = localStorage, sessionBackend = sessionStorage });
store.get('settings')                 // bereinigte Kopie (Defaults für Fehlendes/Ungültiges)
store.update('settings', s => ({...s, lang: 'en'}))  // speichert sofort, gibt den bereinigten Bereich zurück
store.flush()                         // aktuellen Stand schreiben (erster Start)
store.canSave                         // false, wenn Schreiben scheitert
store.shouldShowSaveNotice()          // true höchstens einmal je Sitzung (sessionStorage)
store.onChange(section, fn)
store.onSaveFailed(fn)
```
`createStore({ backend, sessionBackend, env, noticeMarker })`: `env.defaultLang` = Startsprache;
`noticeMarker` = Ersatz-Merker in `history.state`, falls auch sessionStorage fehlt.
Neue Einstellungsfelder: `addSettingsFields({...})`; „Fortschritt löschen" =
`progress-service.resetProgress(store)` (nur Regel-48-Felder).
Jeder Bereich hat einen Sanitizer in `save-schema.js` (Feld für Feld, ungültig → Default,
unbekannte Felder bleiben). Neue Bereiche: neuen Sanitizer registrieren (`registerSection`).

### Eingabe (`src/adapters/input/`)

`InputState` je Frame: `{ steer: -1..1 (rechts +), throttle: -1..1 (W +), gallop: bool,
jump: bool (Flanke: in diesem Frame gedrückt), pause: bool (Flanke), camera: bool (Flanke) }`.
`throttle < 0` kommt von S/Pfeil-runter und vom Joystick nach unten gleichermaßen; im Halt steuert
es das Rückwärtsrichten (siehe Reit-Simulation), sonst bremst es.

```js
const input = createInput({ container, inputMode, isActive });  // isActive: false bei Pause/anderem Bildschirm
input.poll()            // InputState (Tastatur + Touch zusammengeführt)
input.endGallop()       // Spiel beendet den Galopp (Verweigerung, Zaun): Touch aus, Shift neu drücken
input.resetTouchGallop()// nur der Touch-Galopp-Schalter aus (z. B. beim Verlassen des Rittes)
input.clearEdges()      // Flanken (Sprung/Pause/Kamera) verwerfen, z. B. nach „Weiter“
input.dispose()         // Listener von Eingabemodus, Tastatur und Touch entfernen
input.keyboard.latchGallop({ onNextShiftPress })  // Galopp sperren, bis Shift losgelassen ist;
                        // mit onNextShiftPress zählt auch der nächste Shift-Druck nicht
                        // (Moduswechsel durch Shift selbst, Regeln 9/11)
input.keyboard          // createKeyboard(target, { isActive }): inaktiv → keine Tasten aufnehmen
input.touch.setGallop(bool) // Galopp-Umschalter setzen (Touch)
```
Tastatur: `gallop = shiftHeld && !shiftLatched`. Ein fokussiertes Bedienelement (Button, Eingabefeld,
Schieberegler) **behält seine Tasten** (Leertaste/Enter/Pfeile): die Tastatur ignoriert Ereignisse,
deren Ziel ein solches Element ist. Touch: Galopp-Umschalter. Joystick (`joystick-mapping.js`,
rein): **hybride Totzone**
(„scaled radial followed by sloped scaled axial“, minimuino.github.io/thumbstick-deadzones). Erst
eine skalierte **radiale** Totzone (`TUNING.control.stickDeadZone`; Länge des Stick-Vektors, Rest
auf 0..1 umgerechnet), dann axiale Totzonen auf den Einheits-Richtungskomponenten, danach mal
Länge: `control.stickAxialThrottle` (≈ ±11,5° um die Waagerechte: keine Tempoänderung, Drehen auf
der Stelle bleibt Drehen und startet kein Rückwärtsrichten) und `control.stickAxialSteer` (≈ ±7°
um die Senkrechte: keine Lenkung, Fingerwackeln beim geraden Anreiten dreht das Pferd nicht). So
laufen Lenken und Tempo nicht ineinander. Dazu Lenk-Verstärkung: volle Lenkung ab
`TUNING.control.stickSteerFull` (≤ 2/3 seitlicher Auslenkung), schräg nach vorn gehalten lenkt damit
deutlich. Tempo = umgerechnete Vertikalkomponente (ganz nach unten = −1).

### Reit-Simulation (`src/domain/sim/`, rein, deterministisch mit injiziertem RNG)

```js
const sim = createRidingSim({ obstacles, rules, rng = createRng(1), tuning = TUNING });
sim.reset({ x, z, heading });           // Halt, kein Galopp
const events = sim.step(dt, input);     // input = InputState
sim.horse  // { x, z, heading, speed (< 0 = Rückwärtsrichten), gait: 'halt'|'walk'|'trot'|'canter'|'back', gallop,
           //   y, jump: null|{ phase: 'takeoff'|'flight'|'landing', progress 0..1, elementId },
           //   hop: null|{ progress }, refusal: null|{ type: 'stop'|'runout', elementId, progress },
           //   turnRate }
sim.rails  // Map elementId → boolean[] (true = Stange liegt oben)
sim.rebuild(elementId)                  // Stangen wieder aufbauen
sim.rebuildAll()
sim.approach  // null | { elementId, dir, distance, angle } aktuell angerittenes Element
sim.zoneFor(elementId, dir, speed) → { far, near, lastPoint, reach }  // Abstände (m) vor der Vorderkante
```
`rules` kommt vom Modus:
```js
rules.canRefuse(elementId, dir) → bool   // freier Modus: true; Parcours: nur an der Reihe + Richtung + Ritt
```
Events (Array, je Step): `{type:'takeoff', elementId, dir, self, risk}`,
`{type:'railDown', elementId, rail, dir}` (dir = jump direction ±1), `{type:'landed', elementId, dir, knocked: bool}`
(Sprung gezählt), `{type:'refusal', elementId, dir, reason:'gait'|'speed'|'angle'}`,
`{type:'swerve', elementId}`, `{type:'hop'}`, `{type:'fenceStop'}`,
`{type:'gallopEnded', reason:'refusal'|'fence'}`.
Weitere Regeln der Sim: Eine Leertaste kurz vor der Landung wird gepuffert und wirkt nach der
Landung auf das nächste Element in Reichweite (Kombination, Teil b); sie wird nie zu einem Hop.
Der Aufprall auf Ständer/Hindernis ohne Sprung lässt das Pferd seitlich ausweichen (`swerve`) bei
**jeder** Geschwindigkeit ab der Halt-Schwelle (`speeds.haltBelow`), also auch im Schritt. Endet der
Galopp unter `trotMin` (Absprung-Anlauf), beschleunigt das Pferd mit `control.gallopEndTrotUp`
bis in den Arbeitstrab statt in den Schritt zu fallen; Zaun/Verweigerung halten an.
**Rückwärtsrichten (Regeln 8, 9, 24; `rein-back.js`).** Modelliert als **negative Geschwindigkeit**
(`horse.speed < 0`, Gangart `'back'`), nicht als eigener Zustand: `advance`, Lenkung (`updateSteering`
nutzt `max(0, speed)`, also volle Drehung wie im Halt), Zaun (`keepRearInside` schiebt die Hinterhand
zurück) und das Ritt-Protokoll arbeiten unverändert, es gibt keinen zweiten Bewegungspfad.
`gaitForSpeed` liefert bei `speed < 0` `'back'`. `updateReinBack(state, throttle, dt, tuning)` läuft
vor `updateSpeed` und übernimmt den Schritt nur, wenn es das Tempo setzt: im Halt (Tempo genau 0, kein
Galopp, nicht in Verweigerung/Ausweichen) mit gehaltenem `throttle < 0` startet das Pferd nach
`TUNING.reinBack.delayS` (< 0,5 s) und beschleunigt mit `accel` auf `maxSpeed · Auslenkung`; loslassen
bremst mit `decel` bis 0; `throttle > 0` oder Galopp setzen das Tempo sofort auf 0 und die normale
Beschleunigung übernimmt. Bremsen aus der Fahrt mit gehaltenem S führt erst in den Halt, die Pause
beginnt danach. Alle Verbraucher von Tempo/Gangart bleiben dadurch richtig (jeder mit einem
expliziten Wächter, wo nötig):
- Sprung/Hop/Puffer: `pressJump` kehrt bei `speed < 0` sofort zurück (Space wirkt nicht, kein Hop,
  Puffer wird verworfen); `gaitAllows('back')` ist ohnehin `false`.
- Anreiten/Verweigerung/Ausweichen: `approaches()` liefert rückwärts nichts (kein `sim.approach`, keine
  Absprung-Hilfe), `checkLastPoints` läuft nur bei `speed >= 0`; der Aufprall-Ausweichbogen
  (`swerve`) braucht `speed >= haltBelow`.
- Hindernisse: `holdRearBack` macht einen Rückwärtsschritt rückgängig (Position und Richtung), wenn der
  Hinterhand-Punkt (`horse.rearLength` hinter dem Bezugspunkt) in einen gesperrten Bereich geriete
  (längs der Stangen mit `reinBack.rearClearance` statt `horse.frontMargin`, denn Schweif und Kruppe
  reichen ≈ 0,27 m hinter den Punkt),
  auch wenn er schon darin steht (direkt nach einer Landung). Drehen im Halt und Vorwärtsreiten
  bleiben unberührt.
- Zaun/Hindernis halten auf: Bleibt die Rückwärtsstrecke eines Schritts unter
  `reinBack.blockedShare` der beabsichtigten, hält das Pferd an (`speed = 0`, Gangart `halt`, kein
  `fenceStop`-Ereignis) und bleibt stehen, bis S/Joystick losgelassen wird (`backBlocked`).
- Start-/Ziellinie: `run.onLineCross(prev, next, timeMs, { backwards })`; der Parcours-Modus setzt
  `backwards` aus `movedBackwards(prev, next, heading)` (Schritt gegen die Blickrichtung). Rückwärts
  gekreuzte Linien zählen nie; Kombination (`run.update`) und Wertung kennen keine Geschwindigkeit.
- Ritt-Klang: `ride-sounds` hängt nur an Ereignissen; Rückwärtsrichten erzeugt keine. Der Hufschlag
  kommt über `horse.onFootfall('back', leg)` (leiser, langsamer Tritt in `sfx.hoof`).
Spielwerte stehen im eigenen Block `TUNING.reinBack` (`delayS`, `maxSpeed`, `accel`, `decel`,
`blockedShare`, `rearClearance`; kein Messwert veröffentlicht: Fußfolge Zweitakt-Diagonale wie der
Trab rückwärts, Tempo geschätzt aus den wenigen klaren Tritten der Dressur-Aufgabe).
Spielwerte (Tempi, Abstände, Toleranzen, Risiko-Kurven, Parcours-Bau `TUNING.course`: Galoppsprung, Landung/Absprung, freie Strecke, Oxer-Tiefen, Wiederaufbau-Verzögerung `rebuildDelayS`,
Hinweis-Dauer `missingHintS`, `control.stickDeadZone`, `control.stickSteerFull`,
`control.stickAxial*`, Lenkraten `control.turn*`) nur in `src/domain/sim/tuning.js`;
Regel-Konstanten (Fehlerpunkte, Zeitfehler-Schritt, Sterne, Auszeichnungs-Schwellen) bleiben in
ihren Domain-Modulen. Die Governor-Defaults (`GOVERNOR_DEFAULTS` in `view3d/quality.js`) sind
Regel-4-Werte und bleiben dort; ebenso die Werte des Hinweises „Stufe zu hoch“
(`LOW_FPS_HINT_DEFAULTS`: 5 s Fenster, 3 s Schonzeit, Grenze 30 fps). Technische Werte der Grafik
stehen im Adapter-Modul, nicht in `tuning.js`: `STAGE_GAP_FRAMES` (`view3d/quality-stages.js`),
`COMPILE_HOLD_MAX_MS` (`engine.js`), `CONTEXT_RESTORE_TIMEOUT_MS` (`resilience.js`).

### Parcours (`src/domain/course/`, rein)

```js
COURSES            // [ {id:1, obstacles:[Obstacle], start:{a:[x,z],b:[x,z]}, finish:{...},
                   //    startPose:{x,z,heading}, allowedTimeS} ... ]  (5 Stück)
FREE_LAYOUT        // { obstacles, startPose }
allowedTime(course, speed) ; timeFaults(overMs) ; starsFor(faults) ; isBetterResult(a, b)
const run = createCourseRun(course);
run.phase                    // 'prestart' | 'riding' | 'finished'
run.current                  // null (→ Ziel) | { obstacleIndex, part, elementId }
run.rules                    // für die Sim: canRefuse(elementId, dir)
run.onLineCross(prev, next, timeMs, { backwards })  // Start-/Ziellinie mit Richtung; rückwärts zählt nie
run.onLanded(elementId, dir, knocked)      // → { scored, rebuildAfterS: 3|null }
run.onRefusal(elementId, dir)
run.update(horse, timeMs)                  // Kombination-Abwenden, Zeit
run.faults → { knockdowns, refusals, timeFaults, total } ; run.timeMs ; run.overTime ; run.result (bei finished)
run.missingHint                            // Nummer des fehlenden Hindernisses (Regel 30) oder null
run.drainRebuilds() → elementIds           // sofort wieder aufzubauen (Kombination neuer Anlauf)
```

### View (`src/adapters/view3d/`)

```js
const world = createWorld(renderer, { quality });  // Szene, Licht, Himmel, Reitplatz, Umgebung
world.setObstacles(obstacles, { flags: bool });     // baut Meshes
world.syncRails(rails, dt, fallDirs)                // animiert fallende/aufgebaute Stangen;
                                                    // fallDirs: Map elementId → ±1 (Fallrichtung)
world.highlight(elementIdOrNull, number)            // Hervorhebung + Nummer
world.setAid(null | { elementId, dir, zone })       // Absprung-Hilfe
world.setLines(null | { start, finish, labels: { start, finish } })  // Start-/Ziellinie; Texte vom Aufrufer übersetzt
world.setFinishMarked(bool)
world.setShadowFocus(x, z)                          // Schatten folgt dem Pferd
world.setQuality(level | preset, { gpu })     // alles auf einmal (Erstellung, Wechsel ohne laufenden
                                                    // Ritt, Kontextverlust; gpu:false = kein PMREM-Render);
                                                    // fasst nur an, was sich wirklich ändert; gibt
                                                    // Schatten-Map und PMREM-Ziel frei, wenn die Stufe
                                                    // sie nicht braucht (baut sie später neu); ändert
                                                    // NICHT die Pixel-Ratio (Sache der Engine)
world.applyQualityStage('shadows'|'materials'|'density', level | preset)
                                                    // ein Schritt des gestuften Wechsels (siehe
                                                    // „Gestufter Stufenwechsel“); jeder Schritt liest den
                                                    // echten Zustand und ist wiederholbar
world.textureSizes()                                // [{ width, height, normal }] der hochgeladenen Texturen
                                                    // (für die GPU-Speicher-Schätzung)
world.syncAnisotropy(level | preset)                // Anisotropie der Texturen; NUR aufrufen, solange nichts
                                                    // gezeichnet wird (Textur-Neuupload)
world.restoreAfterContextLoss()                     // nach wiederhergestelltem WebGL-Kontext: PMREM-
                                                    // Umgebungslicht neu rendern (altes Ziel nur
                                                    // vergessen, nicht disposen); Schatten-Map baut
                                                    // three.js selbst neu
world.update(dt, camera)                            // Himmel, Umgebung, Ringe, Linien (je Frame)
world.dispose()
const horse = createHorse({ coat, marking, quality });  // → { object, earAnchor, ... }
horse.object                                         // THREE.Group, schaut nach +Z
horse.update(dt, sim.horse)                          // Gangart-/Sprung-Animation (Gangart 'back':
                                                     // Zweitakt-Diagonale rückwärts, Huf läuft im Stand nach vorn)
horse.setAppearance({ coat, marking })
horse.setQuality(level)
horse.earAnchor                                      // Object3D für Reiter-Sicht
horse.onFootfall = (gait, leg) => {}                 // für Hufschlag
horse.dispose()
```
Die öffentliche Pferd-API enthält keine interne Bewegungs-Zustandsstruktur (nur über Tests der
reinen Module `motion.js`, `gaits.js` erreichbar). Die Geschwindigkeitsschwellen der Gangarten
(`view3d/horse/gaits.js`) werden aus `TUNING.speeds` abgeleitet, nicht kopiert; die Standard-Optik
(`DEFAULT_APPEARANCE`) kommt aus `domain/horse/appearance.js`.

### Audio (`src/adapters/audio/`)

```js
const audio = createAudio(settings, deps);   // settings: { musicVolume, musicMuted, sfxVolume, sfxMuted }
                                             // deps (nur Tests): { AudioContext }
audio.installUnlock(window)   // Gesten-Listener; legt den AudioContext erst bei der ersten Geste an
audio.unlock()                // einzelner Entsperr-Versuch (z. B. aus Tests)
audio.setVolumes({ musicVolume, musicMuted, sfxVolume, sfxMuted })
audio.setMusicWanted(bool)    // Screen-abhängig (siehe Screen-Flag `music`)
audio.setHidden(bool)         // Hintergrund → stumm, Kontext wird angehalten
audio.setPaused(bool)         // Pause → keine Effekte
audio.sfx.hoof(gait) / takeoff() / landing() / railDown() / startSignal() / finishSignal()
audio.getState()              // { unlocked, running, failed, hidden, paused, musicWanted,
                              //   musicPlaying, sfxCounts: { hoof, takeoff, ... } }
audio.dispose()
```
- **Entsperren:** `installUnlock` hört auf `pointerdown` (nur Maus), `pointerup` (Touch/Stift),
  `click`, `keydown` (nicht Escape) und `touchend`. Die Listener bleiben, bis der Kontext wirklich
  `running` ist, und werden über `ctx.onstatechange` wieder installiert, sobald der Kontext nicht
  mehr läuft und die Seite sichtbar ist (iOS-Unterbrechung, verweigertes `resume`).
- **Telemetrie:** `getState().sfxCounts` zählt pro Effektname, welche Effekte wirklich gespielt
  wurden (nicht gezählt: vor dem Entsperren, pausiert, im Hintergrund, stumm, Kontext nicht
  `running`). Der Test-Hook `window.__zhfTest.audio()` liefert diesen Zustand; die Smoke-Tests
  prüfen damit Startsignal (nur bei „Los“), Zielsignal, Pause und Hintergrund.
- **Startsignal:** der Vorstart-Bildschirm spielt es nur bei „Los“ (nicht bei „Nochmal“ oder
  Neustart). **Zielsignal:** die Ritt-Sitzung liefert das Kommando `{ type: 'sound', name:
  'finishSignal' }` vor `finished`; der Ritt-Bildschirm führt es aus (keine Entscheidung im UI).
- **Musik pro Screen:** Screen-Flag `music`, optional `musicDelayMs` (Ergebnisse: 1,5 s, damit die
  Melodie nicht mit dem Zielsignal kollidiert); `adapters/ui/music-gate.js` setzt beides um
  (`audio-wiring.js` verdrahtet Einstellungen, Sichtbarkeit und Musik).
- **Klang für kleine Lautsprecher:** Hufschlag und Landung haben ein kurzes „Klopp“-Transient
  (Bandpass-Rauschen 1–2,5 kHz, ca. 20 ms) und mehr Bandpass-Anteil, weil Handy-/Tablet-Lautsprecher
  unter ca. 350 Hz kaum etwas wiedergeben.

## Grafikstufen

`low`: pixelRatio 1, keine Schatten, Lambert-Materialien, wenig Umgebung.
`medium`: pixelRatio ≤ 1,5, Schatten 1024 (nur Pferd/Hindernisse), Standard-Materialien.
`high`: pixelRatio ≤ 2, Schatten 2048, mehr Umgebung (Bäume, Gras-Instanzen), Nebel.
Jede Stufe steht in `QUALITY_PRESETS` (`view3d/quality.js`, mit `level`-Name); `characterDetail` (`low|medium|high`)
bestimmt Geometrie und Material von Pferd und Reiter.
Automatik: `src/adapters/view3d/quality.js` (`createQualityGovernor`), misst nur beim Reiten.
Bei **manueller** Stufe über `low` (`canHintLowerLevel`) meldet `createLowFpsHint` (gleiche
Messregeln, eine Instanz je Ritt bzw. freiem Modus) einmal „Grafik zu hoch“ (< 30 fps im 5-s-Mittel);
auf `low` wird weder gemessen noch gezeigt (es gibt nichts Niedrigeres). „Neu starten“ im Parcours
ist ein neuer Ritt (Konzept-Regel 39): `reset()` erlaubt den Hinweis erneut. Der Ritt-Bildschirm
zeigt ihn als Toast (`feedback`-Element, 5 s Echtzeit, auch bei wenigen fps), die Stufe bleibt.

### Engine, Stufenwechsel und Kontextverlust (`view3d/engine.js`, `view3d/resilience.js`)

```js
engine.contextLost                    // true, solange der WebGL-Kontext weg ist
engine.on('contextLost' | 'contextRestored', fn) → unsubscribe
```
- **Kontextverlust:** `watchContextLoss` meldet Verlust/Wiederherstellung und ruft zur Sicherheit
  `preventDefault` auf (ohne das gäbe es nie eine Wiederherstellung; three.js r186 tut es in
  `onContextLost` bereits selbst, wir verlassen uns nicht darauf). Der Ritt-Bildschirm abonniert beides:
  bei Verlust pausiert er, „Weiter“ ist gesperrt und ein Hinweis steht im Pausenmenü; nach der
  Wiederherstellung (Engine: three.js hat seinen Zustand selbst neu aufgebaut, dann
  `world.restoreAfterContextLoss()`, Resize, Shader-Vorkompilierung) wird „Weiter“ wieder frei,
  der Ritt bleibt pausiert, bis das Kind fortsetzt. Beginnt ein Ritt bei verlorenem Kontext, startet
  er pausiert.
- **Kommt der Kontext nicht zurück:** Ein Wächter (`createRestoreWatchdog`, Konstante
  `CONTEXT_RESTORE_TIMEOUT_MS` = 8 s, technischer Wert, nicht in `tuning.js`) startet beim Verlust.
  Läuft er ab, wechselt der Hinweis im Pausenmenü zu „Bitte lade die Seite neu.“ und ein Knopf
  „Neu laden“ (`[data-action="reload"]`, `location.reload()`) erscheint und bekommt den Fokus. Kommt
  der Kontext doch noch, verschwinden beide wieder. Den Fokus im Pausenmenü bekommt immer der erste
  nutzbare Knopf (bei Verlust ist „Weiter“ gesperrt).
- **Kontextverlust als Hinweis auf ein überlastetes Gerät (Regel 4):** Beim Verlust entscheidet die
  reine Funktion `levelAfterContextLoss({ auto, level })` (`view3d/quality.js`) → `{ level, persist,
  hint }`: Mit Automatik geht die Stufe auf `low` und wird über `settings.setAutoLevel` gespeichert
  (Automatik bleibt an); bei manueller Stufe über `low` bleibt die Stufe und `hint` ist wahr. Die
  Engine setzt die Stufe **schon beim Verlust** um (`applyAllNow({ gpu: false })`: nichts wird
  gezeichnet, Aufrufe am verlorenen Kontext ignoriert der Browser, kein PMREM-Render), damit die
  wiederhergestellte Szene gleich auf `low` zurückkommt und es später keinen zweiten Wechsel gibt.
  Ein noch laufender gestufter Wechsel wird dabei abgeschlossen. Der Hinweis wartet in der Engine
  (`engine.takeGraphicsHint()` liest und löscht ihn); der Ritt-Bildschirm zeigt ihn als Toast
  `ride.graphicsContextLost`, sobald das Kind nach der Wiederherstellung fortsetzt (oder ein neuer
  Ritt beginnt).
- **Gestufter Stufenwechsel (`view3d/quality-stages.js`):** Ein Wechsel mitten im Ritt (Governor
  oder manuell) geschieht nicht in einem Frame, sondern in kleinen Schritten, weil ein Frame mit
  allen neuen Shadern, neuer Schatten-Map, neuem Zeichenpuffer und neu hochgeladenen Texturen den
  Grafikprozess eines Tablets überlasten und so den Kontext kosten kann (Khronos
  „HandlingContextLost“). Die reine Funktion `planQualityStages(from, to)` liefert die geordneten
  Schritte `{ id, compile }`, jeder nur, wenn sich seine Werte unterscheiden:
  `pixelRatio` (zuerst: Auflösung ist der größte Hebel und braucht keinen Shader) → `shadows`
  (Schattenpass und -Map) → `materials` (Material-Typ, Normal-Maps, Nebel an/aus, Umgebungskarte:
  alles Shader-Änderungen, ein Schritt mit einem Kompilieren) → `characters` (Pferd und Reiter) →
  `density` (Instanzen, Geometrie-Detail der Umgebung, Nebel-Distanzen als Uniforms). Beim
  Hochstufen gilt die umgekehrte Reihenfolge (Auflösung zuletzt).
  `planQualityStagesFromState(applied, to)` plant ab dem Stand je Schritt (ein unterbrochener
  Wechsel setzt fort; ein neues Ziel mitten im Wechsel erreicht nur die fehlenden Schritte).
  `createStageQueue({ gapFrames: STAGE_GAP_FRAMES = 6 })` taktet: `tick()` je gezeichnetem Frame
  liefert den nächsten Schritt frühestens nach 6 Frames (technischer Wert, nicht in `tuning.js`).
  Die Engine wendet einen Schritt an (`applyStage`: `pixelRatio` → vorgemerkt, `characters` →
  `horse.setQuality`, sonst `world.applyQualityStage`), startet bei `compile: true` die
  Vorkompilierung (`renderer.compileAsync`, KHR_parallel_shader_compile) und wartet danach wieder
  die Lücke ab; währenddessen hält der `RenderGate` (höchstens 2,5 s) Simulation und Zeichnen an,
  das letzte Bild bleibt stehen. Jeder Schritt unterbricht die Governor-Messung. Ohne laufende
  Schleife (Menü, Einstellungen vor dem Ritt) oder bei verlorenem Kontext wird alles auf einmal
  angewendet (`applyAllNow`): es ist nichts sichtbar. `engine.settling` ist wahr, solange Schritte
  ausstehen oder das Gate zu ist.
- **Keine Texturen neu hochladen:** Ein Stufenwechsel ändert nie `texture.anisotropy`. three.js liest
  den Wert nur beim Hochladen (r186 `WebGLTextures.js`, `uploadTexture` → `setTextureParameters`),
  eine Änderung hieße `needsUpdate`, also `texImage2D` plus Mipmaps für jede Boden-, Sand- und
  Holztextur: der teuerste Teil des alten Wechsels. Die Anisotropie der Stufe wird nur gesetzt, wenn
  nichts gezeichnet wird: bei der Erstellung der Welt, bei `world.setQuality` und beim Start eines
  Ritts (`engine.run(fn)` ruft `world.syncAnisotropy(level)`). Mitten im Ritt bleibt der alte Wert
  (nur der Filter-Grad der Texturen, kein sichtbarer Bruch); die Stufe gilt voll ab dem nächsten Ritt.
  Der Nebel ändert nur Distanzen am vorhandenen `Fog` (Uniform, kein Neukompilieren); ein neues
  `Fog` entsteht nur beim Ein-/Ausschalten.
- **Pixel-Ratio:** Die Engine merkt sie nur **vor** (Schritt `pixelRatio`); die Schleife wendet sie
  erst an, wenn das Gate offen ist, im selben Frame, der wieder zeichnet: Ein Resize leert den
  Zeichenpuffer, bei geschlossenem Gate wäre der Bildschirm sonst bis zu 2,5 s schwarz.
- **Robustheit:** Jeder Schritt der Schleife (Resize, Ritt-Frame, Render) und der Einstellungs-
  Handler läuft in `try/catch`; Fehler werden begrenzt geloggt (`createErrorReporter`, einmal je
  Stelle und 5 s), die Schleife läuft weiter.

### GPU-Speicher-Budget (`view3d/quality.js`, `engine.js`)

Browser nennen den GPU-Speicher nicht; ein Absturz des Grafikprozesses (Chrome: Kontextverlust,
Firefox Android: Tab-Absturz) kam bisher erst bei „Hoch“ auf dem Tablet. Darum wird vor dem
Anwenden einer Stufe der Bedarf **geschätzt** und mit einem vorsichtigen Budget verglichen. Alle
Werte sind technische Konstanten, benannt und gruppiert in `quality.js` (nicht in `tuning.js`), und
bewusst auf der sicheren Seite; die Debug-Box zeigt Schätzung und Budget, damit man sie am Gerät
nachstellen kann.

- `estimateGpuMemoryMB(preset, { cssWidth, cssHeight, devicePixelRatio, pixelRatio, antialias,
  textures })` (MiB): Zeichenpuffer (RGBA8 Farbe × 2 Puffer + 24-Bit-Tiefe = 12 B/Pixel; mit MSAA
  4 Samples Farbe + Tiefe + Auflösung = 40 B/Pixel; Pixel = CSS-Größe × min(DPR, Pixel-Ratio)²),
  Schatten-Map (three r186 `WebGLShadowMap`, PCF: RGBA8 + 32-Bit-Tiefen-Textur = 8 B/Texel, also
  2048² = 32 MiB), PMREM-Umgebungskarte (r186 `PMREMGenerator`, 256er Würfel im cubeUV-Layout
  768 × 1024 in Half-Float-RGBA ≈ 6 MiB), Texturen mit Mipmaps (× 4/3; Normal-Maps nur wenn die Stufe
  sie nutzt), Szenerie (Instanzen/LOD) und eine Grundlast (Geometrie, Programme, Pferd,
  Compositor). `antialias` ist das Attribut des **echten** Kontexts.
- `gpuBudgetMB({ deviceMemory, isTouch, rendererString })` (MiB): Touch 40 MiB je GiB
  `deviceMemory`, begrenzt auf 96…320, ohne Angabe 160; Desktop 64 je GiB, 256…1024, ohne Angabe
  512; schwache GPU (`WEAK_GPU`) × 0,75. `navigator.deviceMemory` ist auf 0,25…8 GiB gerundet und
  nur in Chrome/Edge vorhanden (MDN), sagt also über starke Desktops und Firefox/Safari wenig;
  darum die vorsichtigen Standardwerte.
- `fitPresetToBudget(preset, ctx, budgetMB)` (rein) passt eine Stufe an, solange die Schätzung das
  Budget übersteigt, in dieser Reihenfolge: 1. Pixel-Ratio (größte noch passende, in 0,05-Schritten,
  nicht unter 1), 2. Schatten-Map 2048 → 1024, 3. Gras-Büschel aus, dann Umgebungsdichte auf 0,55.
  Der Look der Stufe bleibt sonst („Hoch“ behält seine Effekte bei kleinerer Auflösung). Ergebnis:
  `{ preset (dasselbe Objekt, wenn nichts zu ändern ist; sonst eingefrorene Kopie mit `level`),
  estimateMB, requestedMB, budgetMB, fits, capped }`; passt auch nach allen Stufen nichts, läuft
  die Stufe trotzdem (`fits: false`).
- `chooseAntialias(preset, ctx, budgetMB)`: Antialiasing ist ein Kontext-Attribut und steht beim
  Erstellen des Renderers fest. Es bleibt nur, wenn die Stufe es will und das Budget die
  MSAA-Puffer trägt, auch wenn alle anderen Hebel schon benutzt sind; sonst entsteht der Kontext
  ohne (z. B. gespeichertes „Hoch“ auf einem schwachen Tablet). Die Debug-Box zeigt das.
- Die Engine prüft **jede** Stufe: beim Start, bei jedem Wechsel (Governor und manuell, auch
  „Hoch“) und beim Start jedes Ritts (die Fenstergröße kann sich geändert haben). Sie arbeitet
  überall mit dem angepassten Preset (`fitFor(level)`); der gestufte Wechsel plant gegen dieses
  Preset (eine gekappte Pixel-Ratio ist ein eigener `pixelRatio`-Schritt). `engine.level` bleibt
  der Name der Stufe. `?testhooks&gpubudget=<MB>` erzwingt in Tests ein kleines Budget.

### Diagnose-Box (`?debug`)

Nur mit `?debug` in der Adresse (Erkennung `debugRequested` in `adapters/platform/debug-info.js`,
wie `?testhooks`); ohne den Parameter wird nichts installiert und kein Element erzeugt. `main.js`
legt dann `app.services.debug = { errorLog }` an und installiert `installErrorCapture` (schreibt
`console.error`, `window`-`error` und `unhandledrejection` in `createErrorLog`, die letzten 5, je
höchstens 160 Zeichen; `console.error` druckt weiter). Der Ritt-Bildschirm hängt unter die
fps-Zeile (`.ride-hud`) die Box `[data-hud="debug"]` (`ui/debug-display.js`, `createDebugBox`): sie
aktualisiert sich etwa zweimal pro Sekunde (eigener `createFpsMeter`-Takt, kein Objekt pro Frame
im Ritt), auch im Pausenmenü und bei verlorenem Kontext. Der Text kommt aus der reinen Funktion
`formatDebugText(info, errors, t)`; alle Wörter stehen in `ui/i18n/debug.js` (`debug.*`, DE + EN),
eingesetzt werden nur Zahlen und technische Zeichenketten (GPU-Name, Fehlertexte). Die Zahlen
liefert `engine.diagnostics()` (immer dasselbe Objekt): GPU (`WEBGL_debug_renderer_info`, sonst
`RENDERER`), Stufe und Automatik, `devicePixelRatio`, Pixel-Ratio des Renderers, Zeichenpuffer,
größte Textur, Antialiasing (an/aus, oder „aus: zu wenig Grafikspeicher“), GPU-Speicher-Schätzung
gegen Budget (z. B. „GPU est. 180 / 256 MB, ratio capped 2 → 1.25“, plus Zeilen für gekappte
Schatten-Map und verringerte Szenerie), Anzahl Kontextverluste/-wiederherstellungen mit Sekunden seit Seitenstart und
ausstehende Stufenwechsel-Schritte.

### fps-Anzeige (`ui/fps-display.js`)

`createFpsMeter({ intervalS: 0.5, maxFrameS: 1 })` mittelt die Bildrate und meldet etwa zweimal pro
Sekunde einen gerundeten Wert; ein Frame länger als `maxFrameS` (ausgesetzter Tab) beginnt ein neues
Intervall. `formatFpsText({ fps, level, auto }, t)` wählt nur den i18n-Schlüssel und übergibt
Parameter: `ride.fpsLevelAuto` („58 fps · Mittel (Auto)“), `ride.fpsLevel` („58 fps · Mittel“, bei
manueller Stufe) oder `ride.fps` („58 fps“, ohne Stufe); Trennzeichen, Wortstellung und der Platzhalter
`ride.fpsNone` („–“, bis der erste Mittelwert da ist) stehen in den Sprachdateien. Das Element
`[data-hud="fps"]` ist die erste Zeile der HUD-Spalte oben links (`.ride-hud`), kann also keine Parcours-Chips verdecken; es folgt live der Einstellung
`showFps` und der Stufe (Governor, Einstellungen). Beim Ein-/Ausschalten wird der angezeigte Wert
zurückgesetzt (Platzhalter, bis der nächste Mittelwert da ist).

## Arbeitsweise

TDD für `domain` und `application` (Test zuerst). Adapter: reine Hilfsfunktionen mit Tests,
Verhalten im Browser per Smoke-Test (`tests/smoke/`).
