# Springreiten-Trainer – Architektur-Vertrag (technische Spec)

Gilt für SRT-001 bis SRT-006. Fachliches „Was": `docs/features/springreiten-trainer/concept.md`
(Regeln Rn) und die Tickets in `docs/features/springreiten-trainer/tasks/`. Dieses Dokument regelt
das „Wie": Module, Schnittstellen, Koordinaten, Datei-Ownership. Abweichungen erst hier ändern.

## Stack

- Vite 8 (statisch, `base: './'`), Vanilla-JS ES-Module (kein TypeScript, keine Typprüfung als Gate),
  three.js 0.186 (`import * as THREE from 'three'`).
- Tests: Vitest (`src/**/*.test.js`, Umgebung `node`; DOM-Tests per `// @vitest-environment jsdom`).
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
- Kombinations-Abstand a→b (Mitte zu Mitte): `COMBI_DISTANCE` (Spielwert, ca. 7,3 m).
- Rails: Kreuz = 2 gekreuzte Stangen (zählt als eine fallende „Stange" = rail 0, beide fallen),
  Steilsprung = obere Stange rail 0 (+ untere feste Füllstange, fällt nie),
  Oxer = vordere obere rail 0, hintere obere rail 1.

## Schichten (Clean Architecture, siehe CLAUDE.md)

Abhängigkeiten zeigen nur nach innen: `adapters → application → domain`; `shared` darf jede Schicht
nutzen. ESLint (`no-restricted-imports`, `no-restricted-globals`, `no-restricted-properties`)
erzwingt die Grenzen.

| Pfad | Inhalt | Darf importieren |
| --- | --- | --- |
| `src/shared/` | reine Helfer ohne Seiteneffekte (Event-Emitter, Mathe) | nichts außer `shared` |
| `src/domain/sim/` | Reit-/Sprung-Simulation, Spielwerte (`tuning.js`), Geometrie, seedbarer Zufall | `domain`, `shared` |
| `src/domain/course/` | Parcours-Layouts, freie Aufstellung, Ritt-Zustandsautomat, Wertung | `domain`, `shared` |
| `src/domain/progress/` | Fortschritt, Bestleistung, Freischaltung, Auszeichnungen | `domain`, `shared` |
| `src/domain/horse/` | Pferdename, Fellfarben, Abzeichen (Werte und Regeln) | `domain`, `shared` |
| `src/application/save-schema.js` | Spielstand-Bereiche mit Bereinigung (Regel 47), Einstellungsfelder | `domain`, `shared` |
| `src/application/settings-schema.js` | Registriert Einstellungsfelder (Grafik, Kamera, Hilfe, Klang) und die Bereiche `horse`/`progress` im Spielstand-Schema | `domain`, `application`, `shared` |
| `src/application/ride-session.js` | Anwendungsfall „Ritt": Sim-Schritt, Ereignisse, Stangen-Wiederaufbau, Sprungzähler + Sofort-Auszeichnungen, Absprung-Hilfe, Rückmeldungen | `domain`, `application`, `shared` |
| `src/application/modes/` | Modus-Strategien `free-mode.js`, `course-mode.js` (Uhr, HUD-Modell, Rittende → Fortschritt + Auszeichnungen) | `domain`, `application`, `shared` |
| `src/application/progress-service.js` | Sprung zählen, Ritt abschließen, Fortschritt löschen (über Port `store`) | `domain`, `shared` |
| `src/adapters/storage/` | localStorage-Store (implementiert Port `store`) | innen |
| `src/adapters/platform/` | WebGL-Prüfung, Touch-Modus, Hochformat, PWA | innen |
| `src/adapters/input/` | Tastatur, Touch-Bedienung (nipplejs) → `InputState` | innen |
| `src/adapters/view3d/` | Renderer, Grafikstufen, Welt, Hindernisse, Pferd/Reiter, Kamera, Engine | innen |
| `src/adapters/audio/` | WebAudio-Synthese | innen |
| `src/adapters/ui/` | App-Rahmen, Bildschirme (`screens/`), Einstellungs-Abschnitte (`settings-sections.js`, `audio-wiring.js`), i18n + Texte, Styles | innen |
| `src/main.js` | Composition Root | alles |

### Ports (als Parameter injiziert)

- `store`: `{ get(section), update(section, fn), onChange(section, fn) }` – Adapter: `adapters/storage`.
- `clock`: `{ nowIso() }` für Auszeichnungs-Datum; Zeit im Spiel kommt als `dt`.
- `rng`: `() => number` in [0, 1) – Domain nutzt nie `Math.random()` direkt.
- Die Ritt-Sitzung liefert Ereignisse/Kommandos (`endGallop`, `badgesAwarded`, `feedback`,
  `finished`, Klang-Ereignisse); der UI-Adapter setzt sie um (Eingabe, Toasts, Klang, Bildschirmwechsel).

### Ritt-Sitzung (application/ride-session.js)

```js
const session = createRideSession({ mode, store, clock, rng });
session.restart();                       // Startpose, Stangen auf, Modus zurücksetzen
const out = session.step(dt, input);     // input = InputState ohne pause/camera
// out = { events (Sim), commands: [{type:'endGallop'} | {type:'resetTouchGallop'} |
//         {type:'feedback', key} | {type:'badges', ids} | {type:'finished', summary} |
//         {type:'sound', name, gait?}] }
session.view  // { horse, rails, aid: null|{elementId, dir, zone}, highlight, finishMarked,
              //   lines, hud: mode-spezifisches Modell (reine Daten) }
```
Pause, Kamera-Umschaltung, Auto-Pause und DOM bleiben im UI-Adapter (`adapters/ui/screens/ride`).

## Schnittstellen

### i18n (`src/adapters/ui/i18n.js`)

```js
registerStrings({ de: { 'menu.settings': 'Einstellungen', ... }, en: { ... } });
t('menu.settings', { name: 'Blitz' });  // {name} Platzhalter
getLang(); setLang('de'|'en'); onLangChange(fn) → unsubscribe
detectLang(navigatorLanguages) → 'de'|'en'
```
Jeder Bereich registriert seine Texte in `src/adapters/ui/i18n/<bereich>.js` (Default-Export
`{ de, en }`), importiert in `src/adapters/ui/i18n/index.js`. Ein Test prüft: gleiche Schlüssel in de und en.

### Speicher (`src/adapters/storage/local-store.js`, `src/application/save-schema.js`)

Ein JSON-Objekt unter `localStorage['zoes-horse-farm.save']`:
```js
{
  version: 1,
  settings: { lang, graphicsAuto, graphicsLevel, camera, aidFree, aidCourse,
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
store.update('settings', s => ({...s, lang: 'en'}))  // speichert sofort
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
Tastatur: `gallop = shiftHeld && !shiftLatched`; `latchGallop()` setzt `shiftLatched` bis Shift
losgelassen wird. Touch: Galopp-Umschalter; `latchGallop()` schaltet ihn aus.

### Reit-Simulation (`src/domain/sim/`, rein, deterministisch mit injiziertem RNG)

```js
const sim = createRidingSim({ obstacles, rules, rng = createRng(1), tuning = TUNING });
sim.reset({ x, z, heading });           // Halt, kein Galopp
const events = sim.step(dt, input);     // input = InputState
sim.horse  // { x, z, heading, speed, gait: 'halt'|'walk'|'trot'|'canter', gallop,
           //   y, jump: null|{ phase: 'takeoff'|'flight'|'landing', progress 0..1, elementId },
           //   hop: null|{ progress }, refusal: null|{ type: 'stop'|'runout', progress },
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
`{type:'railDown', elementId, rail}`, `{type:'landed', elementId, dir, knocked: bool}`
(Sprung gezählt), `{type:'refusal', elementId, dir, reason:'gait'|'speed'|'angle'}`,
`{type:'swerve', elementId}`, `{type:'hop'}`, `{type:'fenceStop'}`,
`{type:'gallopEnded', reason:'refusal'|'fence'}`.
Spielwerte (Tempi, Abstände, Toleranzen, Risiko-Kurven) nur in `src/domain/sim/tuning.js`.

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
run.onLineCross(prev, next, timeMs)        // Start-/Ziellinie mit Richtung
run.onLanded(elementId, dir, knocked)      // → { scored, rebuildAfterS: 3|null }
run.onRefusal(elementId, dir)
run.update(horse, timeMs)                  // Kombination-Abwenden, Zeit
run.faults → { knockdowns, refusals, time, total } ; run.timeMs ; run.result (bei finished)
run.missingHint                            // Nummer des fehlenden Hindernisses (Regel 30) oder null
run.drainRebuilds() → elementIds           // sofort wieder aufzubauen (Kombination neuer Anlauf)
```

### View (`src/adapters/view3d/`)

```js
const world = createWorld(renderer, { quality });  // Szene, Licht, Himmel, Reitplatz, Umgebung
world.setObstacles(obstacles, { flags: bool });     // baut Meshes
world.syncRails(sim.rails, dt)                      // animiert fallende/aufgebaute Stangen
world.highlight(elementIdOrNull, number)            // Hervorhebung + Nummer
world.setAid(null | { elementId, dir, zone })       // Absprung-Hilfe
world.setFinishMarked(bool)
world.setQuality('low'|'medium'|'high')
const horse = createHorse({ coat, marking });       // THREE.Group, schaut nach +Z
horse.update(dt, sim.horse)                          // Gangart-/Sprung-Animation
horse.setAppearance({ coat, marking })
horse.earAnchor                                      // Object3D für Reiter-Sicht
horse.onFootfall = (gait) => {}                      // für Hufschlag
```

### Audio (`src/adapters/audio/`)

```js
const audio = createAudio({ settings });   // startet erst nach erster Interaktion (unlock)
audio.setVolumes({ musicVolume, musicMuted, sfxVolume, sfxMuted })
audio.setMusicWanted(bool)                 // Screen-abhängig
audio.setHidden(bool)                      // Hintergrund → stumm
audio.sfx.hoof(gait) / takeoff() / landing() / railDown() / startSignal() / finishSignal()
audio.setPaused(bool)                      // Pause → keine Effekte
```

## Grafikstufen

`low`: pixelRatio 1, keine Schatten, Lambert-Materialien, wenig Umgebung.
`medium`: pixelRatio ≤ 1,5, Schatten 1024 (nur Pferd/Hindernisse), Standard-Materialien.
`high`: pixelRatio ≤ 2, Schatten 2048, mehr Umgebung (Bäume, Gras-Instanzen), Nebel.
Automatik: `src/adapters/view3d/quality.js` (`createQualityGovernor`), misst nur beim Reiten.

## Arbeitsweise

TDD für `domain` und `application` (Test zuerst). Adapter: reine Hilfsfunktionen mit Tests,
Verhalten im Browser per Smoke-Test (`tests/smoke/`).
