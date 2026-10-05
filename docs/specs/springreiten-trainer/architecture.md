# Springreiten-Trainer – Architektur-Vertrag (technische Spec)

Gilt für SRT-001 bis SRT-014. Fachliches „Was": `docs/features/springreiten-trainer/concept.md`
(Regeln Rn) und die Tickets in `docs/features/springreiten-trainer/tasks/`. Dieses Dokument regelt
das „Wie": Module, Schnittstellen, Koordinaten, Datei-Ownership. Abweichungen erst hier ändern.

## Stack

- Vite 8 (statisch, `base: './'`), Vanilla-JS ES-Module (kein TypeScript, keine Typprüfung als Gate),
  three.js 0.186 (`import * as THREE from 'three'`).
- Tests: Vitest (`src/**/*.test.js` und `tests/**/*.test.js`, Umgebung `node`; DOM-Tests per `// @vitest-environment jsdom`).
- Lint: ESLint 10 flat config, Format: Prettier (singleQuote, printWidth 100).
- Smoke: Playwright gegen `vite preview` (Port 4173), nur in Chromium (lokal und in CI; `SMOKE_BROWSERS`
  erlaubt Einzelversuche in anderen Browsern, CI nutzt das nicht). Die Specs sind echte Smoke-Tests: Sie
  zeigen das Zusammenspiel im Browser; Regeln und Grenzwerte sind Unit-Tests (Vitest).
- PWA: vite-plugin-pwa (generateSW, kein skipWaiting/clientsClaim → neue Version erst nach
  Schließen aller Tabs).
- Version (SRT-015, Regel 58): `vite.config.js` setzt per `define` die Konstante `__APP_VERSION__`
  (siehe „Versionsanzeige“).
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
| `src/shared/` | reine Helfer ohne Seiteneffekte (Event-Emitter, `math.js`: `clamp`, `isPlainObject`; `spring.js`: gedämpfte Feder; `reach.js`: weiche Reichweiten-Grenze einer Zwei-Knochen-Gliedmaße; siehe „Weiche Bewegung“) | nichts außer `shared` |
| `src/domain/sim/` | Reit-/Sprung-Simulation, Spielwerte (`tuning.js`), Geometrie, seedbarer Zufall | `domain`, `shared` |
| `src/domain/course/` | Parcours-Layouts, freie Aufstellung, Ritt-Zustandsautomat, Wertung (der geometrische Layout-Prüfer `layout-check.js` ist reines Test-Orakel und liegt in `tests/support/`) | `domain`, `shared` |
| `src/domain/progress/` | Fortschritt, Bestleistung, Freischaltung, Auszeichnungen | `domain`, `shared` |
| `src/domain/horse/` | Pferdename, Fellfarben, Abzeichen (Werte und Regeln) | `domain`, `shared` |
| `src/application/save-schema.js` | Spielstand-Bereiche mit Bereinigung (Regel 47), Einstellungsfelder | `domain`, `shared`, `application/languages.js` |
| `src/application/languages.js` | `LANGS` – die angebotenen Sprachen (einzige Quelle für Schema und UI) | – |
| `src/application/graphics-levels.js` | `GRAPHICS_LEVELS` – die wählbaren Grafikstufen (einzige Quelle für Schema, Einstellungs-Dienst, Grafik-Presets und Pferd); `AUTO_START_LEVEL` (`'low'`) – die Stufe, bei der „Automatisch“ beginnt | – |
| `src/application/crash-guard.js` | Absturzwächter (SRT-013): Bereich `crashGuard` im Spielstand, Leases, Herzschlag, gesperrte Stufen (siehe „Absturzwächter“) | `application`, `shared` |
| `src/application/settings-service.js` | Anwendungsfälle für Einstellungen: der **einzige Schreiber** des Bereichs `settings` (siehe „Einstellungs-Dienst“) | `domain`, `application`, `shared` |
| `src/application/settings-schema.js` | Registriert Einstellungsfelder (Grafik, Kamera, Hilfe, Klang, `controlsHelpSeen`) und die Bereiche `horse`/`progress` im Spielstand-Schema | `domain`, `application`, `shared` |
| `src/application/start-flow.js` | Startablauf: welche Bildschirme vor dem Hauptmenü kommen (`startSequence`, `nextStartScreen`; siehe „Startablauf und Bedienungs-Tipps“) | `application` |
| `src/application/ride-session.js` | Anwendungsfall „Ritt": Sim-Schritt, Ereignisse, Stangen-Wiederaufbau, Sprungzähler + Sofort-Auszeichnungen, Absprung-Hilfe, Rückmeldungen | `domain`, `application`, `shared` |
| `src/application/modes/` | Modus-Strategien `free-mode.js`, `course-mode.js` (Uhr, HUD-Modell, Rittende-Ergebnis). **Modi speichern nichts**: nur die Ritt-Sitzung schreibt, und zwar über den Fortschritts-Dienst | `domain`, `application`, `shared` |
| `src/application/progress-service.js` | Sprung zählen, Ritt abschließen, Fortschritt löschen (über Port `store`) | `domain`, `shared` |
| `src/adapters/storage/` | localStorage-Store (implementiert Port `store`) | innen |
| `src/adapters/platform/` | WebGL-Prüfung, Touch-Modus, Hochformat, PWA, Seiten-Lebenszyklus (`page-lifecycle.js`) und Tab-Kennung (`tab-id.js`) für den Absturzwächter | innen |
| `src/adapters/input/` | Tastatur, Touch-Bedienung (nipplejs) → `InputState` | innen |
| `src/adapters/view3d/` | Renderer, Grafikstufen, Welt, Hindernisse, Pferd/Reiter, Kamera, Engine | innen |
| `src/adapters/audio/` | WebAudio-Synthese | innen |
| `src/adapters/ui/` | App-Rahmen, Bildschirme (`screens/`, z. B. `screens/ride-screen.js`), Einstellungs-Abschnitte (`settings-sections.js`, `audio-wiring.js`), Bedienungs-Tipps (`screens/controls-help.js`, `screens/help-content.js`), i18n + Texte, Styles (`styles/main.css` mit den Design-Tokens, `help.css`) | innen |
| `src/main.js` | Composition Root (verdrahtet Store, Einstellungs-Dienst, Absturzwächter, App, Bildschirme; Startfehler → allgemeine Fehlermeldung) | alles |
| `tests/support/` | Test-Hilfen, die nie in den Produktions-Build gelangen: `sim-utils.js`, `test-ports.js` (Fake-Store/-Uhr), `test-host.js`, `autopilot.js` (+ Fahrbarkeits-Test), `layout-check.js` (+ Test; Layout-Orakel für `courses.test.js`), `color.js` (+ Test; WCAG-Kontrast), `scene-stats.js` (+ Test; Draw Calls und Dreiecke einer three.js-Szene ohne GL), `gpu-tracker.js` (+ Test; Stellvertreter für das, was three.js auf der GPU hält: Shader-Programme, Geometrien, Instanz-Puffer samt Spitzenwert, siehe „Gestufter Stufenwechsel“), `fake-canvas.js` (Canvas-Attrappe für Texturen in Node), `sequence-helper.js` (geskriptete Ritte für die Kontinuitätstests). Vitest-Include und ESLint-Override sind dafür eingerichtet | alles |

### Ports (als Parameter injiziert)

- `store`: `{ get(section), update(section, fn), updateThrough(section, fn), onChange(section, fn) }` – Adapter:
  `adapters/storage`. `update` gibt den **bereinigten** neuen Bereich zurück (nicht, was die Funktion geliefert hat).
  `updateThrough` ändert nur einen Bereich gegen den gespeicherten Stand (siehe unten).
- `clock`: `{ nowIso(), nowMs() }` für Auszeichnungs-Datum und Zeitspannen (Absturzwächter); Zeit im Spiel
  kommt als `dt`.
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
              //   jumping (bool: ein Sprung läuft: Absprung, Flug, Landung; die Grafik-Automatik steigt dann nicht),
              //   approaching (bool: ein Hindernis wird angeritten, `sim.approach` ≠ null; ebenso),
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
Der Test-Hook bietet außer Lesefunktionen `go(name, params)` sowie `loseContext()` / `restoreContext()`
(simulierter WebGL-Kontextverlust über `WEBGL_lose_context`, Rückgabe `false` ohne Engine oder
Erweiterung); der Schnappschuss enthält `contextLost` und `graphicsSettling` (ein Stufenwechsel
läuft noch: Schritte ausstehend, Pixel-Ratio vorgemerkt oder Shader werden kompiliert) und
`graphicsPixelRatio` (Pixel-Ratio des Renderers). `setFrameFeed({ dt, repeat })` ersetzt die Bildzeiten,
die die Grafik-Automatik misst (jedes echte Bild zählt als `repeat` Bilder zu je `dt` Sekunden; `null`:
wieder die echten), damit Browser-Tests nicht durch Schonzeit und Fenster (3 s, 10 s, 20 s) des
langsamen Software-Renderers warten müssen; `frameFeed()` liefert `{ dt, repeat, fedSeconds }` (die
Engine zählt die gemessene Reitzeit in `fedSeconds`; Ablage `app.services.frameFeed`, ohne `?testhooks`
nie gesetzt). `?testhooks&gpubudget=<MB>` ersetzt das
GPU-Speicher-Budget der Engine (`gpuBudgetOverride` in `test-hooks.js`; `main.js` legt es als
`app.services.gpuBudgetOverrideMB` ab; ohne `?testhooks` wirkungslos).
Die Smoke-Hilfen (`tests/smoke/helpers.js`) kennen den Startablauf: `openMenu(page)` überspringt
Namensfrage und Bedienungs-Tipps („Verstanden“, `[data-action="help-done"]`) bis zum Hauptmenü;
`openGame(page, { save, lang, helpSeen = true })` schreibt einen übergebenen Spielstand mit
`controlsHelpSeen: helpSeen` (ein Spielstand gilt als „Hilfe gesehen“, ohne Spielstand beginnt die
App von vorn). Lokal braucht der Smoke-Test ein Chromium (`PW_CHROMIUM_PATH`, siehe README).

### Einstellungs-Dienst (application/settings-service.js)

```js
const settings = createSettingsService(store);   // in main.js einmal erzeugt, als ctx.settings verteilt
settings.get()                    // gespeicherter Stand (bereinigte Kopie)
settings.setLang('de'|'en')
settings.setGraphicsAuto()                  // „Automatisch“ an, Stufe = AUTO_START_LEVEL (low), ruft onAutoSelected
settings.onAutoSelected(fn) → unsubscribe   // nach „Automatisch“ neu gewählt (Absturzwächter, Engine)
settings.setGraphicsLevel(level)            // manuelle Wahl, Automatik aus
settings.setAutoLevel(level)                // Governor ändert die Stufe (runter oder hoch), Automatik bleibt an
settings.setCamera('follow'|'rider')        // CAMERA_MODES in settings-schema.js
settings.setAid('free'|'course', on)
settings.setShowFps(on)                     // fps-Anzeige im Ritt (Regel 4, 44); Standard aus
settings.markControlsHelpSeen()             // Bedienungs-Tipps mit „Verstanden“ geschlossen (Regel 56, 44)
settings.setVolume('music'|'sfx', v) ; settings.setMuted('music'|'sfx', m)
settings.onChange(fn) → unsubscribe
```
Kein Adapter ruft `store.update('settings', …)` direkt auf (Einstellungs-Bildschirm, Audio-
Verdrahtung, Ritt-Bildschirm/Kamera, Vorstart-Hilfe-Schalter, Bedienungs-Tipps, Engine/Grafik-Governor,
Absturzwächter). Eine Geräte-Wahl der Startstufe gibt es nicht: „Automatisch“ beginnt immer bei
`AUTO_START_LEVEL`.

### Startablauf und Bedienungs-Tipps (SRT-012, Regeln 43, 53, 56)

```js
startSequence(store)    // z. B. ['namePrompt', 'controlsHelp', 'menu']; das Hauptmenü ist immer der letzte Schritt
nextStartScreen(store)  // erster noch offener Schritt der Folge
```
`application/start-flow.js` ist rein (nur Port `store`). Jeder Schritt, der erledigt ist, fällt aus
der Folge: `namePrompt` solange die Namensfrage unbeantwortet ist (`needsNamePrompt`), `controlsHelp`
solange `settings.controlsHelpSeen` falsch ist (auch bei einem bestehenden Spielstand, der die Hilfe
nie geschlossen hat; ein alter Spielstand ohne das Feld bekommt `false`). Deshalb fragt jeder Bildschirm
beim Schließen nur nach „dem nächsten“ (`app.go(nextStartScreen(store))`); `main.js` startet mit
`app.go(nextStartScreen(store))`, die Namensfrage hat keinen `params.next` mehr.

Bildschirm `controlsHelp` (`adapters/ui/screens/controls-help.js`, registriert über
`registerControlsHelp(app)`):
- Inhalt als reine Daten in `help-content.js` (`KEYBOARD_ROWS`, `TOUCH_ROWS`, `HELP_MODES`,
  `defaultHelpMode(touch)`, `rowsFor(mode)`); der Bildschirm zeigt nur an. Tastatur: Tastenkappen
  (`<kbd class="keycap">`, Alternativen mit „/“), Touch: Symbole, die den Ritt-Buttons gleichen
  (`help-glyph-stick|gallop|jump|small`, Farben aus den Touch-Tokens). Beim Öffnen gilt die aktuelle
  Eingabeart (`inputMode.touch`), eine Auswahlgruppe (`choiceGroup`) schaltet auf die andere um.
  Texte in `ui/i18n/help.js` (`help.*`, `menu.help`, `pause.help`, DE + EN), Styles in `styles/help.css`.
- „Verstanden“ (`[data-action="help-done"]`) ruft `settings.markControlsHelpSeen()`. Mit
  `params.fromPause` (aus der Pause über `app.push('controlsHelp', { fromPause: true })` geöffnet)
  folgt `app.pop()` zurück ins noch offene Pausenmenü, sonst `app.go(nextStartScreen(store))`.
  Aus der Pause hat der Bildschirm keine Musik (`music: !params.fromPause`).
- Menü-Eintrag `help` (`registerMenuEntry`, `order: 45`, Text `menu.help`) im Hauptmenü; im Pausenmenü
  steht der Knopf `[data-action="help"]` (`pause.help`, `btn-secondary`) unter „Einstellungen“.
  Auf niedrigen Querformat-Bildschirmen ordnet `ride.css` die Pause-Knöpfe in zwei Spalten.

### Farbsprache der Buttons (SRT-010, Regel 57)

Alle Farben sind Design-Tokens im ersten `:root`-Block von `ui/styles/main.css`; Bildschirm-Styles
nutzen nur diese Tokens, keine eigenen Hex-Werte für Buttons. Rollen:

| Rolle | Klasse | Tokens |
| --- | --- | --- |
| Weitermachen (Standard) | `.btn` | `--c-positive` / `--c-positive-ink` / `--c-positive-dark` (3D-Kante) |
| Zurückhaltend | `.btn-secondary` | `--c-neutral-bg` / `--c-neutral-ink` (Schrift und Rahmen) / `--c-neutral-press` |
| Löschen | `.btn-danger` (`profile.css`) | `--c-danger` / `--c-danger-ink` / `--c-danger-dark` |
| Auswahl | `.btn-choice` | `--c-choice-bg` / `--c-choice-ink` / `--c-choice-edge`; aktiv: `--c-choice-on` / `-ink` / `-edge` |
| Touch im Ritt | `.touch-btn` | `--c-touch-jump`, `--c-touch-ink`, `--c-touch-gallop-led`, `--c-touch-small` |
| Überschriften | – | `--c-brand` (nie ein Button oder etwas darin) |

Regeln: Weitermachen ist gefüllt, zurückhaltend flach mit Rahmen (die Art ist auch ohne Farbe an der
Form erkennbar), rot nur für Löschen (`reset-section.js`: „Fortschritt löschen“ und dessen
Bestätigung). Jedes Schrift-/Flächen-Paar der Tokens erreicht 4,5 : 1: `ui/styles/palette.test.js`
liest die Tokens aus `main.css` und prüft sie mit `tests/support/color.js` (`contrastRatio`,
WCAG 2.x); Rahmen und Flächen gegen die Panel-Farbe brauchen 3 : 1. Der Smoke-Test
`tests/smoke/colors.spec.js` liest die berechneten Farben auf allen Bildschirmen mit Buttons.

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
  settings: { lang, graphicsAuto, graphicsLevel (low|medium|high, Standard low), camera, aidFree,
              aidCourse, showFps, controlsHelpSeen, musicVolume, musicMuted, sfxVolume, sfxMuted },
  horse:    { name: null|string, nameAnswered: bool, coat, marking },
  progress: { unlocked: 1..5, courses: { '1': { faults, timeCs, stars } }, jumps,
              finishedRides, badges: { [badgeId]: ISO-Datum } },
  crashGuard: { rendering, level, auto, since, lastSeen, hintPending, blockedLevels, lastCrash },
  // unbekannte Schlüssel (spätere Bereiche/Versionen) bleiben unverändert erhalten
}
```
API:
```js
const store = createStore({ backend = localStorage, sessionBackend = sessionStorage });
store.get('settings')                 // bereinigte Kopie (Defaults für Fehlendes/Ungültiges)
store.update('settings', s => ({...s, lang: 'en'}))  // speichert sofort, gibt den bereinigten Bereich zurück
store.flush()                         // aktuellen Stand schreiben (erster Start)
store.updateThrough('crashGuard', fn)  // nur diesen Bereich gegen den gespeicherten Stand ändern und durchschreiben
store.canSave                         // false, wenn Schreiben scheitert
store.shouldShowSaveNotice()          // true höchstens einmal je Sitzung (sessionStorage)
store.onChange(section, fn)
store.onSaveFailed(fn)
```
`createStore({ backend, sessionBackend, env, noticeMarker })`: `env.defaultLang` = Startsprache;
`noticeMarker` = Ersatz-Merker in `history.state`, falls auch sessionStorage fehlt.
`update` schreibt immer die **ganze** In-Memory-Kopie; ein zweiter Tab würde mit veralteten Bereichen den
Fortschritt des ersten überschreiben. Wer ohne Nutzeraktion schreibt (Absturzwächter: Herzschlag, Übergänge),
nutzt darum `updateThrough(bereich, fn)`: `fn` bekommt den **gespeicherten** Bereich (bereinigt; ohne lesbaren
Spielstand den Wert im Speicher), im gespeicherten JSON wird nur dieser Bereich ersetzt (andere und unbekannte
Bereiche bleiben, wie sie dort stehen), im Speicher ändert sich nur dieser Bereich. Der In-Memory-Stand der
anderen Bereiche bleibt unangetastet (nicht gespeicherter Fortschritt dieses Tabs geht nach einem
fehlgeschlagenen Speichern nicht verloren, ein veralteter anderer Tab wird nicht übernommen) und es gibt kein
`change:<anderer Bereich>`, also auch keine Einstellungsänderung mitten im Ritt. Schlägt das Schreiben fehl
oder gibt es keinen Speicher, bleibt der neue Wert nur im Speicher (Regel 46, `saveFailed`).
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
`TUNING.control.stickSteerFull` (derzeit 0,5 der seitlichen Auslenkung, Obergrenze 2/3; vorher
0,6, SRT-009), schräg nach vorn gehalten lenkt damit deutlich (45° nach vorn = volle Lenkung). Tempo = umgerechnete Vertikalkomponente (ganz nach unten = −1).

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

**Lenkung (SRT-009, Kinder-Feedback „Kurven viel zu schwer, ca. 50 % besser“):** Drehrate
`ω(v) = control.turnInPlace / (1 + v / control.turnSpeedRef)`, Wenderadius `r = v / ω`. Das Pferd
lenkt bewusst **deutlich agiler als in der Realität** (Spielspaß vor Realismus; Referenz echte
Pferde: 10-m-Volte r = 5 m im Schritt/Trab, 20-m-Zirkel r = 10 m im Galopp, Sprung-Wendungen
r ≈ 6–8 m, Seitenbeschleunigung v·ω ≈ 2,5 m/s² auf dem Zirkel, 6–8 m/s² in engen Wendungen).
Alle Radien liegen bei 1/1,5 der Werte vor SRT-009:

| Gangart (Tempo) | Radius vorher | Radius jetzt |
| --- | --- | --- |
| Schritt (1,5 m/s) | 1,0 m | 0,7 m |
| Arbeitstrab (3,2 m/s) | 2,7 m | 1,8 m |
| Sprunggalopp (5,8 m/s) | 6,3 m | 4,2 m |
| voller Galopp (8 m/s) | 10,4 m | 6,9 m |

`turnInPlace` 1,8 → 2,7 rad/s (Drehen auf der Stelle ≈ 155°/s), `turnSpeedRef` unverändert 6,0,
`turnResponse` 12 → 18 (Drehrate erreicht 90 % des Ziels in ≈ 0,13 s statt 0,19 s; Filter erster
Ordnung, daher kein Überschwingen; nach dem Loslassen ist die Drehung in < 0,3 s ausgelaufen).
Die Seitenbeschleunigung v·ω erreicht ≈ 9,3 m/s² (voller Galopp) und bleibt bewusst unter 1 g.
Beherrschbar bleibt das über die hybride Stick-Totzone (Fingerwackeln ≈ ±7° um die Senkrechte
lenkt nicht). Folgen: Pferdeneigung/-biegung (`view3d/horse/motion.js`, `turnLean`/`turnBend`) sind
auf die engeren Kurven abgestimmt (Neigung ≈ ⅓ der physikalischen, max. 0,3 rad; Biegung gedeckelt
bei 0,35 rad), und die Verfolgerkamera (`view3d/camera.js`, reine Hilfe `camera-math.js`) folgt der
Pferderichtung geglättet (`FOLLOW_HEADING_STIFFNESS` in `camera-math.js`, Nachlauf ≈ 34° bei der
schnellsten Drehung), damit der Blick bei schnellen Kurven ruhig bleibt; die Reiteransicht schaut
nur leicht verzögert. Die Schwenkrate der Kamera ist gedeckelt (`FOLLOW_HEADING_MAX_RATE`), aber
immer sicher über der schnellsten Spielerdrehung (`turnInPlace` × 1,2, aus `TUNING` abgeleitet):
ein Deckel darunter ließe den Nachlauf bei Dauerdrehung auf der Stelle unbegrenzt wachsen.
Ausweich-/Zaunrichtungen (`refusal.maneuverTurnRate` 5,0, `fence.slideTurnRate` 6,0 rad/s) sind
unverändert und bleiben schneller als jede Spielerlenkung.

Spielwerte stehen im eigenen Block `TUNING.reinBack` (`delayS`, `maxSpeed`, `accel`, `decel`,
`blockedShare`, `rearClearance`; kein Messwert veröffentlicht: Fußfolge Zweitakt-Diagonale wie der
Trab rückwärts, Tempo geschätzt aus den wenigen klaren Tritten der Dressur-Aufgabe).
Spielwerte (Tempi, Abstände, Toleranzen, Risiko-Kurven, Parcours-Bau `TUNING.course`: Galoppsprung, Landung/Absprung, freie Strecke, Oxer-Tiefen, Wiederaufbau-Verzögerung `rebuildDelayS`,
Hinweis-Dauer `missingHintS`, `control.stickDeadZone`, `control.stickSteerFull`,
`control.stickAxial*`, Lenkraten `control.turn*`) nur in `src/domain/sim/tuning.js`;
Regel-Konstanten (Fehlerpunkte, Zeitfehler-Schritt, Sterne, Auszeichnungs-Schwellen) bleiben in
ihren Domain-Modulen. Die Governor-Defaults (`GOVERNOR_DEFAULTS` in `view3d/quality.js`, exportiert, weil
das Hochstufen dieselbe Schonzeit und Frame-Grenze nutzt) und `UPGRADE_DEFAULTS`
(`view3d/quality-upgrade.js`) sind Regel-4-Werte und bleiben dort; ebenso die Werte des Hinweises
„Stufe zu hoch“ (`LOW_FPS_HINT_DEFAULTS`: 5 s Fenster, 3 s Schonzeit, Grenze 30 fps). Technische Werte
der Grafik stehen im Adapter-Modul, nicht in `tuning.js`: `STAGE_GAP_FRAMES`
(`view3d/quality-stages.js`), `COMPILE_HOLD_MAX_MS` (`engine.js`), `CONTEXT_RESTORE_TIMEOUT_MS`
(`resilience.js`); `HEARTBEAT_INTERVAL_MS` (`application/crash-guard.js`).

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
                                                    // Schatten-Map, PMREM-Ziel und die Shader-Programme der
                                                    // Materialien frei, bevor Neues gebaut wird (baut
                                                    // sie später neu); ändert
                                                    // NICHT die Pixel-Ratio (Sache der Engine)
world.applyQualityStage('shadows'|'materials'|'shadowsAndMaterials'|'density', level | preset)
                                                    // ein Schritt des gestuften Wechsels (siehe
                                                    // „Gestufter Stufenwechsel“); jeder Schritt liest den
                                                    // echten Zustand und ist wiederholbar;
                                                    // 'shadowsAndMaterials' = MERGED_STAGE_ID, beides in einem
world.compileRoot                                   // was die Engine vorkompiliert: nur die SICHTBAREN Objekte
                                                    // (plus der Hufstaub, falls die Stufe ihn hat):
                                                    // renderer.compileAsync(world.compileRoot, camera, world.scene)
world.textureSizes()                                // [{ width, height, normal }] der hochgeladenen Texturen
                                                    // (für die GPU-Speicher-Schätzung)
world.syncAnisotropy(level | preset)                // Anisotropie der Texturen; NUR aufrufen, solange nichts
                                                    // gezeichnet wird (Textur-Neuupload)
world.restoreAfterContextLoss()                     // nach wiederhergestelltem WebGL-Kontext: PMREM-
                                                    // Umgebungslicht neu rendern (altes Ziel und alte
                                                    // Schatten-Map nur vergessen, nicht disposen);
                                                    // die Schatten-Map baut three.js neu
world.gpuObjects()                                  // alles mit GPU-Ressourcen (für createGpuEpoch)
world.emitHoofDust(x, y, z, strength)               // Hufstaub an einem Aufsetzpunkt (Weltkoordinaten); nur wenn
                                                    // strength >= 0.3 (kein Schritt), der Punkt auf dem Sand des
                                                    // Reitplatzes liegt (isOnArenaSand, 0,2 m vom Zaun) und die
                                                    // Stufe Staub hat (Preset hoofDust)
world.update(dt, camera)                            // Himmel, Umgebung, Wind, Tiere, Staub, Ringe, Linien (je
                                                    // Frame); die grasenden Pferde laufen nur, solange die
                                                    // Koppel im Blickfeld ist
world.dispose()
const horse = createHorse({ coat, marking, quality, rider = true, tack = true, castShadow = null, rng });
                                                     // → { object, earAnchor, ... }; rider/tack = false und
                                                     // castShadow = false: das grasende Pferd der Koppel
horse.object                                         // THREE.Group, schaut nach +Z
horse.update(dt, sim.horse)                          // Gangart-/Sprung-Animation (Gangart 'back':
                                                     // Zweitakt-Diagonale rückwärts, Huf läuft im Stand nach vorn);
                                                     // state.graze 0..1 senkt Hals und Kopf zum Gras (Koppelpferde);
                                                     // gibt horse.footfalls zurück
horse.setAppearance({ coat, marking })
horse.setQuality(level)
horse.earAnchor                                      // Object3D für Reiter-Sicht
horse.onFootfall = (gait, leg) => {}                 // für Hufschlag (nur 'step'-Ereignisse)
horse.footfalls                                      // Aufsetzer des letzten update(): [{ kind: 'step'|'landing',
                                                     // leg 0..3 (LF, RF, LH, RH), gait, strength 0..1, x, y, z }]
                                                     // im lokalen Raum von horse.object; Array und Ereignisse werden
                                                     // vom nächsten update() wiederverwendet (nicht aufbewahren)
horse.footfallWorld(event, out)                      // Aufsetzpunkt in Weltkoordinaten (mit der AKTUELLEN Lage von
                                                     // horse.object: erst nach dem Platzieren aufrufen)
engine.emitHoofDust()                                // je Footfall world.emitHoofDust; der Ritt-Bildschirm ruft es
                                                     // nach horse.update und placeHorse
horse.dispose()
```
Aufsetzer: `step` je Hufschlag mit Stärke je Gangart (`STEP_STRENGTH`: Schritt 0,12, Trab 0,5, Galopp 0,8,
Rückwärts 0,1), `landing` paarweise je Sprung (Vorderhand Stärke 1, Hinterhand 0,65).
Die öffentliche Pferd-API enthält keine interne Bewegungs-Zustandsstruktur (nur über Tests der
reinen Module `motion.js`, `gaits.js`, `legs.js` erreichbar). Die Geschwindigkeitsschwellen der Gangarten
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
`medium`: pixelRatio ≤ 1,5, Schatten 1024 (nur Pferd/Hindernisse), Standard-Materialien, nur die
billigen Details (siehe Tabelle: kein Wind, keine Blumen, Tiere und kein Staub).
`high`: pixelRatio ≤ 2, Schatten 2048, mehr Umgebung (Bäume, Gras-Büschel), Nebel, alle Details.
Jede Stufe steht in `QUALITY_PRESETS` (`view3d/quality.js`, mit `level`-Name); `characterDetail` (`low|medium|high`)
bestimmt Geometrie und Material von Pferd und Reiter.
Die Details von SRT-011 (Regel 3) sind eigene Preset-Schlüssel, die Stufen unterscheiden sich so
(SRT-013: „Mittel“ bekommt nur, was billig ist und kein eigenes Shader-Programm mitbringt; mit dem
vollen Satz verlor das Tablet seinen Kontext):

| Schlüssel | low | medium | high |
| --- | --- | --- | --- |
| `grassTufts` (Anteil der 11 000 Büschel; der größte Dreieckskosten-Posten) | 0 | 0 | 1 |
| `flowers` (Anteil der Wiesenblumen) | 0 | 0 | 1 |
| `decor` (Wimpelkette, Blumenkübel am Tor, Koppelzaun und -Requisiten; < 1: jeder zweite Wimpel) | 0 | 0,5 | 1 |
| `planters` (Blumenkästen an den Hindernisständern; eigenes Shader-Programm) | aus | aus | an |
| `grazingHorses` (Pferde auf der Koppel) | 0 | 0 | 2 |
| `hoofDust` | aus | aus | an |
| `birds` / `butterflies` (Anteil) | 0 / 0 | 0 / 0 | 1 / 1 |
| `wind` (Bäume, Büsche, Gras, Blumen wiegen sich; Wind-Code im Shader) | aus | aus | an |

`low` behält Draw Calls und Dreiecke von vor SRT-011 (nichts Neues wird gezeichnet oder hochgeladen),
`medium` nur geringfügig mehr als vor SRT-011 (Commit `5e240fc`): ein paar Draw Calls, ein
Shader-Programm (die Wimpel sind doppelseitig), wenige Prozent Dreiecke, unter 1 MB Speicher.
`windyGrass` entfällt, `wind` ersetzt es. Die Zahlen prüft `view3d/world-budget.test.js`.

**Grafik-Automatik (Regel 4, SRT-013, SRT-014):** Beim ersten Start ist „Automatisch“ an und beginnt bei
`AUTO_START_LEVEL` (`low`, `application/graphics-levels.js`); `graphicsLevel` hat im Schema den Standard
`low`, es gibt keine Geräte-Wahl der Startstufe mehr (`pickInitialLevel` ist entfernt, die Engine
liest nur noch die gespeicherte Stufe). Die Automatik arbeitet sich hoch und geht bei Last wieder
herunter; die erreichte Stufe wird über `settings.setAutoLevel` gespeichert und gilt beim nächsten Start.
„Automatisch“ neu zu wählen (`settings.setGraphicsAuto()`) beginnt wieder bei `low` und gibt gesperrte und
verlassene Stufen frei (`onAutoSelected`). Eine manuell gewählte Stufe wird nie angehoben.
- **Runter** (`createQualityGovernor` in `view3d/quality.js`, `GOVERNOR_DEFAULTS`: 5 s Mittel unter
  50 fps, 3 s Schonzeit, 10 s Pause zwischen zwei Anpassungen): misst nur beim Reiten. Eine Stufe, von
  der er wegen niedriger Bildrate herunterging, merkt sich die Engine für die laufende Sitzung
  (`leftByFps`) und steigt nicht zurück.
- **Hoch** (`view3d/quality-upgrade.js`, siehe „Hochstufen der Automatik“).
- Die Engine fasst beides hinter einer Fassade zusammen (`governor = { frame, interrupt }`, vom
  Ritt-Bildschirm genutzt): `governor.frame(rawDt, measuring, busy)` füttert erst den Herunterstufer und,
  wenn der in diesem Frame nichts geändert hat, den Hochstufer; `measuring` heißt Ritt, Vorstart oder
  freier Modus bei sichtbarer Seite, `busy` ist `view.jumping || view.approaching`. Mit einem Test-Feed (`setFrameFeed`) ersetzen
  die eingespeisten Bildzeiten die echten.

Bei **manueller** Stufe über `low` (`canHintLowerLevel`) meldet `createLowFpsHint` (gleiche
Messregeln, eine Instanz je Ritt bzw. freiem Modus) einmal „Grafik zu hoch“ (< 30 fps im 5-s-Mittel);
auf `low` wird weder gemessen noch gezeigt (es gibt nichts Niedrigeres). „Neu starten“ im Parcours
ist ein neuer Ritt (Konzept-Regel 39): `reset()` erlaubt den Hinweis erneut. Der Ritt-Bildschirm
zeigt ihn als Toast (`feedback`-Element, 5 s Echtzeit, auch bei wenigen fps), die Stufe bleibt.

### Hochstufen der Automatik (SRT-014, `view3d/quality-upgrade.js`)

Gegenstück des Herunterstufers: gleiche Messweise (nur beim Reiten, nicht in den ersten 3 s nach einer
Unterbrechung, ein Frame über `maxFrameS` ist eine Unterbrechung), reine Logik ohne three.js. Technische
Werte in `UPGRADE_DEFAULTS` (nicht in `tuning.js`): Fenster 10 s (`windowS`), Mittel mindestens 57 fps
(`minFps`), ein Frame über 25 ms (`slowFrameS`) ist ein Ruckler, höchstens 2 % Ruckler im Fenster
(`maxSlowShare`), Schonzeit und `maxFrameS` wie `GOVERNOR_DEFAULTS`, mindestens 20 s Reitzeit nach
**jeder** Stufenänderung (`cooldownS`, hoch wie runter; Pause und Menü verkürzen sie nicht).

```js
const upgrade = createUpgradeGovernor({ chooseTarget, options });  // options überschreibt UPGRADE_DEFAULTS
upgrade.frame(dt, measuring, busy)  // → { level, fps } wenn jetzt hochgestuft werden soll, sonst null
upgrade.interrupt()                 // Messung verwerfen (Stufenschritt, Pause, Menü)
upgrade.noteChange()                // Stufe hat sich geändert (runter, hoch, Verlust): Cooldown neu
nextUpgradeLevel({ level, blocked, left, fits })  // die Stufe darüber oder null
```
- `frame` läuft nur, wenn das Fenster voll ist, der Cooldown vorbei ist, der Mittelwert ≥ `minFps` und der
  Ruckler-Anteil ≤ `maxSlowShare` ist. `busy` (ein Sprung läuft oder ein Hindernis wird angeritten, `view.jumping || view.approaching`) hält
  den Schritt an, das Fenster bleibt: der Schritt folgt gleich nach dem Sprung. Ein Stufenschritt kann die
  Frames für Sekunden bremsen (Shader-Kompilierung), darum beginnt er auch nicht kurz vor dem Absprung. Erst dann ruft er `chooseTarget()`; gibt
  das null, beginnt das Fenster neu (nächster Versuch nach vollen 10 s).
- `nextUpgradeLevel` kennt nur die **nächste** Stufe (nie über `high`, eine gesperrte wird nicht
  übersprungen) und lässt sie aus, wenn sie in `blocked` steht (Absturzwächter: dort ging die 3D-Darstellung
  verloren oder das Spiel stürzte ab), in `left` (die Automatik hat im laufenden Spiel wegen zu niedriger
  Bildrate von dort heruntergestuft) oder `fits(level)` falsch ist.
- Die Engine übergibt `fitsBudget(level)`: die **ungekürzte** Speicher-Schätzung der Stufe
  (`estimateGpuMemoryMB(QUALITY_PRESETS[level], …)`, mit dem echten Antialiasing des Kontexts) ist
  höchstens das Budget des Geräts (siehe „GPU-Speicher-Budget“). Eine Stufe, die nur mit gekappter Auflösung
  oder Szenerie passen würde, wird nicht angestrebt.
- Hochgestuft wird über `applyQuality` (der gestufte, speicherschonende Wechsel, siehe unten) und
  `settings.setAutoLevel`; die Engine hält den Herunterstufer mit `downgrade.setLevel` im Gleichschritt.
  Das Diagnose-Feld `lastChange` (`{ kind: 'up'|'down'|'loss'|'crash', fps? }`) nennt den Grund der
  letzten automatischen Änderung (`'crash'` schon beim Start, aber nur wenn der Absturzwächter die automatische Stufe wirklich senkte, also
  nicht bei einem Absturz auf `low`: `startupCrashChange(startupCrash)` in `quality.js`).

### Umgebung, Tiere und Details (SRT-011, Regel 3)

Alles prozedural (keine Assets). Reine Planung (`*-plan.js`, `flight-paths.js`, `decor-plan.js`,
`world-layout.js`) liegt getrennt von den three.js-Meshes und hat eigene Tests; Zufall kommt aus einem
seedbaren `rng` (`createRng` aus `textures.js`), also bleibt das Bild bei jedem Start gleich.
- **Wind** (`plant-shaders.js`): `createWind()` → `{ time, strength }` (Uniforms `windTime`,
  `windStrength`). `createWorld` erzeugt **einen** Wind und reicht ihn an Umgebung, Arena und Hindernisse;
  `environment.update` zählt `wind.time`, `environment.setDensity(…, { wind })` setzt `strength` auf 1 oder 0.
  Wind gibt es nur auf `high` (Preset `wind`). Die Patches erweitern three.js-Materialien per
  `onBeforeCompile` im Vertex-Shader (keine CPU-Arbeit) und merken sich ihren Code in
  `material.userData.windPatch`; `setWindPatch(material, on)` schaltet ihn ab (zurück auf das schlichte
  three.js-Programm, das andere schlichte Materialien mitbenutzen) oder wieder an (der Zustand steht in
  `material.userData.windPatch.on`). Ändert sich das Programm, gibt `setWindPatch` `true` zurück und die Welt gibt das Material
  frei (`dispose`), damit das alte Programm vor dem neuen weg ist (siehe „Gestufter Stufenwechsel“):
  `patchTreeWind`, `patchBushWind` (nur die Standard-Materialien; das Lambert-Material von `low` bleibt
  frei vom Wind-Code), `patchTuftWind`, `patchBlossoms(material, wind, { base })` (Blumen biegen sich
  oberhalb von `base`, der Kübel nicht), `patchBunting`, `patchWings(material, wind, { rate, amplitude, glide })`.
- **Wiesenblumen** (`meadow-plan.js` `planMeadow`, `FLOWER_COLORS`; `flower-geometry.js`; `meadow.js`
  `createMeadow`): ein `InstancedMesh` in Flecken abseits von allem, was `isBlocked` meldet (Sand, Wege,
  Gebäude, Bänke, Koppel); `setDensity(anteil)` zeichnet die ersten Instanzen (über alle Flecken verteilt).
  Geometrie und Blumenkasten tragen das Attribut `petal` für `patchBlossoms`.
- **Vögel und Schmetterlinge** (`wildlife.js` `createWildlife`, `flight-paths.js`): wenige
  Instanzen, Flügelschlag im Shader; die Flugbahnen sind geschlossene Funktionen der Zeit
  (`planFlocks`, `flockPose`, `birdPose`, `planButterflies`, `butterflyPose`, Grenzen `BIRD_LIMITS`),
  pro Frame werden nur die Instanzmatrizen geschrieben. Schmetterlinge schweben über Blumenflecken
  (`butterflyAnchors`).
- **Dekoration** (`decor-plan.js`: `planBunting`, `planPots`, `planPaddockProps`, `planPaddockKeepOut`;
  `arena-decor.js` `createArenaDecor` mit zwei Meshes; `arena.js`: `createArena({ …, wind })`,
  `setDetail(decor)`): Wimpelkette am Reitplatzzaun, Blumenkübel am Tor, Koppelzaun (Stil `paddock`) und
  Requisiten der Koppel (Unterstand, Tränke, Raufe). Blumenkästen an den Hindernisständern liegen in
  `obstacles.js` (`setDecor(on)`, ein `InstancedMesh` `planters`, je Hindernis eine Blütenfarbe); sie hängen
  am eigenen Preset-Schlüssel `planters` (nur `high`, eigenes Shader-Programm), nicht mehr an `decor`.
- **Koppel** (`world-layout.js`): `PADDOCK` (Mitte, `width`, `depth`, `rotation`) westlich des Reitplatzes,
  `paddockPoint(u, v)` (Anteile der halben Breite/Tiefe → Weltpunkt), `paddockContains(x, z, margin)`,
  `planPaddockFence()`; der Bereich steht in `BLOCKED`, damit dort keine Pflanzen wachsen.
  `isOnArenaSand(x, z, margin)` entscheidet, wo Hufe stauben (nur innerhalb des Reitplatzzauns).
- **Grasende Pferde** (`horse/grazing.js` `createGrazingHorses({ quality, area, count, coats, rng,
  release })`, Verhalten rein in `horse/grazing-logic.js`): `createHorse` ohne Reiter, Sattelzeug und
  Schatten; Zustände `graze`/`look`/`turn`/`walk` (`stepGrazer`), gesteuert über `state.graze` und die
  normale Gangart-Animation. Geplant wird die **Körpermitte**: das ganze Pferd liegt in jeder Pose
  innerhalb `GRAZING.bodyRadius` darum (`HORSE_EXTENT` ist am Modell gemessen und in `grazing.test.js`
  gegengeprüft), also bleibt es im Zaun und aus den Requisiten (`area.avoid`, Kreise aus
  `planPaddockKeepOut`); der Objekt-Ursprung liegt `GRAZING.bodyOffset` vor der Mitte. Die Koppelpferde
  gibt es nur auf `high` (Modell `medium`; die Stufen darunter haben `grazingHorses: 0`). `world.update`
  bewegt sie nur, solange die Koppel im Blickfeld ist.
- **Hufstaub** (`dust.js`): `createDust({ quality, release, rng })` → ein `THREE.Points` (ein Draw Call),
  Pool als einfache Arrays (`createDustPool`, rein getestet), Größe nach Stufe (`low` 0, `high` 90 Teilchen;
  `medium` hat seit SRT-013 keinen Staub, `hoofDust: false`). Verdrahtung: `engine.emitHoofDust()` → `world.emitHoofDust` (siehe View).
- **Details zurückhalten** (`detail-hold.js` `createDetailHold` → `{ sync, restore, has, size }`,
  `quality-stages.js` `sameMaterialStage`):
  Zwischen dem Schritt `materials` und dem Schritt `density` eines Stufenwechsels ändern sich die
  Shader aller sichtbaren Meshes. Die optionalen Detail-Meshes (Eintrag `detail: true` in
  `environment`/`obstacles`) werden dann ausgeblendet (`sync(meshes, ready)`), damit nichts mit Shadern
  kompiliert wird, die der nächste Schritt wegwirft; `restore()` zeigt sie wieder und läuft vor Code,
  der die Sichtbarkeit selbst bestimmt (`density`-Schritt, `setObstacles`); `has(mesh)` fragt, ob ein Mesh
  gerade nur kurz zurückgehalten wird.
- **Dauerhaft versteckte Details freigeben** (`world.js` `freeHiddenDetails`, nach jedem `applyMeshes`):
  Ein ausgeblendetes Mesh behält seine GPU-Puffer (Geometrie, bei `InstancedMesh` auch die Instanz-
  Puffer). Detail-Meshes, die die Stufe nicht zeigt und die nicht nur zurückgehalten werden, gibt die
  Welt darum über `release` frei (Blumen, Vögel, Büschel, Blumenkästen ...); zeigt eine höhere Stufe sie
  wieder, lädt three.js sie neu hoch. Dasselbe tut `environment.setDensity` mit der Geometrie der verlassenen
  Detailstufe der Bäume (`release(m.geometry)`).
- **Budget** (`world-budget.test.js`, `tests/support/scene-stats.js`, `tests/support/gpu-tracker.js`):
  baut die Welt je Stufe ohne WebGL (`tests/support/fake-canvas.js` für die Texturen) und prüft Draw
  Calls, Dreiecke und Shader-Programme (der Tracker kompiliert `world.compileRoot`): `low` höchstens so
  viel wie vor SRT-011, **gepinnt an den Stand vor SRT-011 (Commit `5e240fc`)** in `BEFORE` (Calls,
  Dreiecke, `programs`, `memoryMB` = `estimateGpuMemoryMB` auf dem Tablet von `quality.test.js`); `medium`
  höchstens `MEDIUM_EXTRA` darüber (2 Draw Calls, 1 Programm, 10 % Dreiecke, 1 MB); `high` bleibt unter
  dem Budget, kostet aber mehr Programme und Speicher als `medium`; nur eine Handvoll Draw Calls mehr auf
  `high`, Budgets je Stufe (low 60 Calls / 60 000 Dreiecke, medium 100 / 90 000, high 150 / 250 000),
  Details erscheinen und verschwinden sauber (`medium` zeigt nur Wimpel und Koppel-Requisiten), Wind nur auf
  `high` (die Materialien von Bäumen und Büschen haben auf `medium` kein `wind-`-Programm), `dispose()` und
  der Kontextverlust-Pfad (`gpuObjects`) kennen alle neuen Objekte.

### Weiche Bewegung von Pferd und Reiter (SRT-011, Regel 24)

Ziel: kein sichtbares Springen von Frame zu Frame bei Gangwechsel, Galoppwechsel, Sprung, Verweigerung,
Rückwärtsrichten und Halt. Bausteine, alle rein (ohne three.js) und einzeln getestet:
- **Federn** (`src/shared/spring.js`): `createSpring`, `stepSpring(s, ziel, omega, zeta, dt)` ist die
  geschlossene Lösung der gedämpften Schwingung für ein konstantes Ziel, also für jedes `dt` stabil und
  bildratenunabhängig (ein Schritt wird auf `MAX_SPRING_DT` begrenzt); `snapSpring` setzt ohne Geschwindigkeit.
  Genutzt für Haar, Glättung von Parametern und für Reiter (Hände, Kopf, Pferdeschwanz, Sitz).
- **Haar-Ketten und Lebenszeichen des Pferdes** (`horse/spring.js`: `createHairChain`, `stepHairChain`,
  `kickChain`, `smoothTo`, Beschleunigungsschätzer `createAccelEstimator`/`stepAccelEstimator`;
  `horse/life.js`: `createLife`, `stepLife`, `gestureHead`): Schweif, Mähne und Schopf folgen als
  unterdämpfte Federketten der echten Beschleunigung des Körpers (Anfahren, Bremsen, Kurven, Auf und Ab,
  Landung) und dem Wind; dazu Atmung mit Nüstern (`uFlare`), Lidschlag (`uBlink`, Lid-Knochen) und
  Schweifschlagen. `index.js` begrenzt die Mähne (`MANE_PRESS`, `MANE_SWING`), damit sie nicht in den
  Hals kippt.
- **Zeitplaner** (`horse/schedule.js`: `blinkCurve`, `createBlinkScheduler`, `createGestureScheduler`,
  `IDLE_GESTURES`, `createRandomTimer`): zufällige Abstände für Lidschlag (nach Messwerten ca. 8–19 pro
  Minute, bei Aufmerksamkeit seltener), Leerlauf-Gesten im Halt (Kopfschütteln, Werfen, Scharren) und
  Schweifschläge; der `rng` wird injiziert (`createHorse({ rng })`), Tests sind deterministisch.
- **Hufmodell** (`horse/legs.js`: `createLegModel`, `blendGait`, `stepLegs`, `allPlanted`; `horse/gaits.js`
  `swingEnds`): jedes Bein hat **einen** Hufpfad, dessen Phase sich beim Gangwechsel allmählich
  angleicht (keine Pose-Überblendung zweier Gangarten); im Stand ist der Huf am Boden verankert (rutscht
  nie), im Schwung läuft der Pfad stetig vom Abheben zum Aufsetzen; beim Halt treten die Beine einzeln
  gerade. Die Aufsetzer (`falls`) speisen `horse.footfalls` (Staub, Klang).
- **Weiche Reichweite** (`src/shared/reach.js` `softReach(d, dMax, soft)`; `horse/ik.js`: `solveFront`/
  `solveHind` mit Parameter `soft`, `scapulaSlide`; `view3d/rider-reach.js` `limbReach` für die Arme):
  statt einer harten Grenze am vollen Strecken (Knie springt um 40° in einem Frame) wird das letzte
  Stück der Reichweite komprimiert. Am Boden eine kleine Zone (`GROUND_SOFT_REACH`), in der Luft eine
  große (`AIRBORNE_SOFT_REACH`), geglättet über `AIRBORNE_RAMP`.
- **Gelenk-Begrenzer** (`horse/joint-limit.js`: `createJointLimiter`, `snapJoint`, `limitJoint`):
  begrenzt Winkelgeschwindigkeit und -beschleunigung der Beingelenke; glatte Bewegung läuft unverändert
  und ohne Verzögerung durch, nur ein Knick der IK wird über wenige Frames verteilt (`LEG_JOINT_LIMIT`,
  auf dem Boden doppelt so viel Spielraum, damit der Huf nicht rutscht).
- **Hals und Mähne** (`horse/neck.js`: Halsprofil `NECK`, `neckCurve`, `maneAnchors`; gemeinsam für
  Geometrie und Skelett).
- **Reiter** (`rider.js`, `createRider({ quality, release })`; `lateUpdate(dt)` wird vom Pferd nach dem
  Aktualisieren der Matrizen aufgerufen):
  - Details je Stufe (`rider-details.js`: `addFace` mit Augen, Brauen, Nase, Lächeln, Ohren, Wangen;
    `addChinStrap`; `addPonytail` mit Haarschleife; `addJacketDetails` mit Knöpfen, Plastron,
    Kragenspitzen, Paspel). `low` bekommt nur das Nötigste, `medium`/`high` mehr und feiner; alles ist an
    vorhandene Knochen gewichtet und im einen Rider-Mesh zusammengeführt (kein zusätzlicher Draw Call).
  - Pferdeschwanz-Kette (`rider-ponytail.js`: `createPonytail`, `stepPonytail`, `ponytailTarget`,
    `PONY_SEGMENTS` = 3): Federn hinter der Kopfbewegung im Kopfraum (Auf und Ab, Beschleunigung, Kurven).
  - Blick (`rider-look.js`: `createHeadLook`, `stepHeadLook`, `lookTarget`): in die Kurve, beim Absprung
    zum Hindernis, im Flug nach vorn, im Halt ein ruhiger Blick umher; gefedert, springt nie.
  - Leben (`rider-life.js`: `breathing`, `createPat`, `stepPat`): Atmung mit leichter Schulterbewegung
    und ein Klopfen auf den Pferdehals, wenn das Pferd nach einem Sprung steht.
  - Sitz: `horse/seat.js` `createSeatFilter()` glättet den ruhigen Teil von `riderSeatParts` mit
    kritisch gedämpften Federn (jeder Sitzwechsel ist sprungfrei, der unmittelbare Teil kommt ungefiltert
    dazu); `crestRelease(J, jumpWeight)`: die Hände gleiten im Flug am Hals vor und kommen bei der
    Landung zurück.
- **Tests** (Regel 24): `horse/continuity.test.js` und `rider-continuity.test.js` fahren geskriptete Ritte
  (`tests/support/sequence-helper.js`: `runScript`, `createDeltaTracker`, `SEQUENCES`) mit 60 fps durch
  das echte Pferd bzw. den Reiter und prüfen die ersten und zweiten Differenzen (Winkel, Positionen,
  Hufe am Boden) auf einen sinnvollen Höchstwert. Dazu je Baustein ein eigener Test
  (`spring`, `reach`, `joint-limit`, `legs`, `life`, `schedule`, `grazing(-logic)`, `rider-*`).

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
  reine Funktion `levelAfterContextLoss({ auto, level, visible, sinceVisibilityChangeS })`
  (`view3d/quality.js`) → `{ level, persist, hint, counted }`: Mit Automatik geht die Stufe auf `low` und
  wird über `settings.setAutoLevel` gespeichert (Automatik bleibt an); bei manueller Stufe über
  `low` bleibt die Stufe und `hint` ist wahr. `counted` sagt, dass der Verlust ein überlastetes Gerät
  zeigt (Vordergrund, nicht kurz nach einem Sichtbarkeitswechsel), unabhängig davon, was er mit der Stufe
  macht: Die Engine sperrt dann die Stufe, auf der er geschah, über `crashGuard.blockLevel(level)`
  (gespeichert), damit die Automatik nicht wieder dorthin steigt, und merkt sich `lastChange = { kind: 'loss' }`,
  wenn sich die Stufe änderte. Derselbe Entscheider (`decide: levelAfterContextLoss`) wird dem
  Absturzwächter in `main.js` übergeben (siehe „Absturzwächter“). Ein Verlust im Hintergrund (`visible` falsch, die
  Engine liest `document.visibilityState`) oder weniger als `CONTEXT_LOSS_GRACE_S` (3 s, technische
  Konstante) nach dem letzten Sichtbarkeitswechsel (`visibilitychange`) sagt nichts über die Last
  des Spiels (Android verwirft Kontexte oft beim App-Wechsel): dann bleibt alles, wie es ist
  (`persist` und `hint` falsch). Die Engine setzt die Stufe **schon beim Verlust** um
  (`applyAllNow({ gpu: false })`: nichts wird gezeichnet, Aufrufe am verlorenen Kontext ignoriert
  der Browser, kein PMREM-Render), damit die wiederhergestellte Szene gleich auf `low` zurückkommt
  und es später keinen zweiten Wechsel gibt. Dabei wird auch die Pixel-Ratio **sofort** gesetzt
  (`setMaxPixelRatio` + `resize(true)`, statt sie für den Frame-Loop vorzumerken): ein
  wiederhergestellter Kontext legt den Zeichenpuffer in der Größe an, die die Zeichenfläche dann
  hat, und das war sonst die alte, große Größe samt MSAA, die den Verlust ausgelöst hatte. Ein noch
  laufender gestufter Wechsel wird dabei abgeschlossen. Der Hinweis wartet in der Engine
  (`engine.takeGraphicsHint()` liest und löscht ihn); der Ritt-Bildschirm zeigt ihn als Toast
  `ride.graphicsContextLost`, sobald das Kind nach der Wiederherstellung fortsetzt (oder ein neuer
  Ritt beginnt).
- **GPU-Objekte vor einem Kontextverlust werden nie mit GL-Aufrufen freigegeben
  (`createGpuEpoch`, `collectGpuObjects` in `view3d/resilience.js`):** three.js (r186) hängt die
  `dispose`-Listener an Objekte, sobald sie hochgeladen werden, und zwar in die damaligen
  Verwaltungsinstanzen (`WebGLTextures`, `WebGLGeometries`, `WebGLAttributes`). Nach der
  Wiederherstellung (`initGLContext`) gibt es neue Instanzen, die alten Listener bleiben. Wird ein
  Objekt von vor dem Verlust danach verworfen (z. B. `obstacles.setObstacles` beim nächsten Ritt),
  löschen sie Handles des verlorenen Kontexts: `INVALID_OPERATION: delete: object does not belong
  to this context` (WebKit: error, Chromium: warning). Regel: Die Engine ruft beim Verlust
  `gpuEpoch.contextLost(world.gpuObjects())` (Geometrien, Materialien samt Texturen, Skelett-
  Texturen, Schatten-Maps, Umgebungs-Ziel – alles, was gezeichnet und damit hochgeladen wurde) und
  beim Wiederherstellen `contextRestored()`. Jede Stelle, die zur Laufzeit GPU-Objekte ersetzt
  (Pferd/Reiter bei Stufenwechsel, Hindernisse, Linien, Hervorhebung, Schatten-Map,
  Umgebungs-Ziel), gibt sie über `release(objekt)` statt `objekt.dispose()` frei (Option
  `release`, Standard `releaseNow`). `release` ruft `dispose()` nur auf, wenn das Objekt nicht
  markiert und der Kontext nicht gerade verloren ist; sonst wird es nur vergessen (der GPU-Speicher
  ging mit dem Kontext). Ohne Verlust ändert sich nichts. Nach einem Verlust sind neu gebaute
  Objekte unmarkiert und werden normal freigegeben. Bekannte Grenze: Ein Objekt, das den Verlust
  überlebt und neu hochgeladen wird, bleibt beim späteren `release` auf der GPU (eine
  Generation pro Verlust); Schatten-Map und Umgebungs-Ziel sind davon ausgenommen, weil
  `restoreAfterContextLoss` sie verwirft und three.js sie neu baut. Der Smoke-Test zählt
  `WebGL: INVALID_*`-Meldungen aller Typen (`watchPage`), damit Chromium findet, was WebKit als
  Fehler meldet.
- **Gestufter Stufenwechsel (`view3d/quality-stages.js`):** Ein Wechsel mitten im Ritt (Governor
  oder manuell) geschieht nicht in einem Frame, sondern in kleinen Schritten, weil ein Frame mit
  allen neuen Shadern, neuer Schatten-Map, neuem Zeichenpuffer und neu hochgeladenen Texturen den
  Grafikprozess eines Tablets überlasten und so den Kontext kosten kann (Khronos
  „HandlingContextLost“). Die reine Funktion `planQualityStagesFromState(applied, to)` liefert aus dem
  Stand je Schritt (`applied`: Schritt-Id → Stufe/Preset; ein unterbrochener Wechsel lässt Schritte
  zurück) die noch nötigen geordneten Schritte `{ id, compile }`, jeder nur, wenn sich seine Werte
  unterscheiden:
  `pixelRatio` (zuerst: Auflösung ist der größte Hebel und braucht keinen Shader) → `shadows`
  (Schattenpass und -Map) → `materials` (Material-Typ, Normal-Maps, Nebel an/aus, Umgebungskarte und der
  Wind-Code der Szenerie: alles Shader-Änderungen, ein Schritt mit einem Kompilieren) → `characters`
  (Pferd und Reiter) → `density` (Instanzen, Geometrie-Detail der Umgebung, die Details der Stufe
  (Blumen, Wimpel, Kübel, Blumenkästen `planters`, Koppelpferde, Staub, Vögel, Schmetterlinge) und
  Nebel-Distanzen als Uniforms; `compile: true`, weil neu erscheinende Details eigene Shader mitbringen;
  der Wind steckt im Schritt `materials`). Beim Hochstufen gilt die umgekehrte Reihenfolge (Auflösung
  zuletzt). **Sind `shadows` und `materials` beide nötig, ersetzt sie ein einziger Schritt
  `shadowsAndMaterials`** (`MERGED_STAGE_ID`, steht an der Stelle des ersten von beiden): beide ändern
  die Programme von allem Sichtbaren, zwei Schritte würden Programme kompilieren, die der zweite sofort
  wegwirft, und das auf dem Höhepunkt des GPU-Speichers. Jeder Schritt hat `covers` (die Schritt-Ids, für
  die die Engine die angewandte Stufe in `applied` einträgt; beim zusammengelegten `['shadows',
  'materials']`), sodass ein unterbrochener Wechsel richtig weiterplant.
  `planQualityStagesFromState(applied, to)` plant ab dem Stand je Schritt (ein unterbrochener
  Wechsel setzt fort; ein neues Ziel mitten im Wechsel erreicht nur die fehlenden Schritte).
  `createStageQueue({ gapFrames: STAGE_GAP_FRAMES = 6 })` taktet: `tick()` je gezeichnetem Frame
  liefert den nächsten Schritt frühestens nach 6 Frames (technischer Wert, nicht in `tuning.js`).
  Die Engine wendet einen Schritt an (`applyStage(stage, target)`: `pixelRatio` → vorgemerkt, `characters` →
  `horse.setQuality`, sonst `world.applyQualityStage(stage.id, …)`), startet bei `compile: true` die
  Vorkompilierung (`precompile()`: `renderer.compileAsync(world.compileRoot, camera, world.scene)`,
  KHR_parallel_shader_compile, nur das Sichtbare, siehe unten) und wartet danach wieder die Lücke ab; währenddessen hält der `RenderGate` (höchstens 2,5 s) Simulation und Zeichnen an,
  das letzte Bild bleibt stehen. Jeder Schritt unterbricht die Governor-Messung. Ohne laufende
  Schleife (Menü, Einstellungen vor dem Ritt) oder bei verlorenem Kontext wird alles auf einmal
  angewendet (`applyAllNow`): es ist nichts sichtbar. `engine.settling` ist wahr, solange Schritte
  ausstehen, eine Pixel-Ratio auf das Anwenden wartet oder das Gate zu ist. Jede Stufenänderung, ob
  hoch oder runter, startet über `upgrade.noteChange()` den Cooldown des Hochstufers.
- **Speicher zuerst (SRT-013, Regel 4):** Ein Wechsel darf auf der GPU nie mehr halten als die größere der
  beiden Stufen; was die Zielstufe nicht mehr braucht, wird freigegeben, **bevor** etwas Neues kompiliert
  oder hochgeladen wird. three.js behält jedes Programm, mit dem ein Material je gezeichnet wurde, bis das
  Material `dispose`d wird (ein bloßes `needsUpdate` hielte altes und neues zugleich). Darum gilt in der
  Welt (`world.js`, jeder Schritt liest den echten Zustand): `freePrograms(materials)` gibt Materialien
  über `release` frei (ein freigegebenes Material baut sein Programm beim nächsten Kompilieren neu);
  `applyShadowStage` gibt vor einer Änderung des Schattenpasses die Programme der ganzen Szene
  (`sceneMaterials()`: auch Pferd, Reiter, Staub) frei und die Schatten-Map, wenn sie nicht mehr gebraucht
  wird oder ihre Größe wechselt; `applyMaterialStage` sammelt zuerst, was sich ändert (Material-Typ, Nebel,
  Umgebungskarte, Wind-Code via `setWindPatch`, Normal-Maps), hängt die Umgebungskarte ab und gibt ihr
  PMREM-Ziel frei (`disposeEnvironmentMap`), gibt die betroffenen Materialien frei und baut erst dann den
  neuen Zustand (neues `Fog`, neue Umgebungskarte); danach geben `freeHiddenDetails` und
  `environment.setDensity` die GPU-Puffer der nicht mehr gezeigten Details und Baummodelle frei.
  Der Beweis ist `view3d/world-stages.test.js` (Node, mit `tests/support/gpu-tracker.js`, der die
  Buchführung von three.js nachbildet: Programme je Programm-Schlüssel mit Zähler, Materialien behalten
  alle bis `dispose`, Geometrien und Instanz-Puffer; `peak` seit `resetPeak()`): Abstieg und Aufstieg
  halten nie mehr als vor dem Wechsel bzw. als die Zielstufe braucht, das Ende gleicht einer von Anfang
  an auf der Zielstufe gebauten Welt (nichts leckt, auch nicht nach `high → low → high`), der Schritt
  mit den neuen Materialien gibt die alten Programme frei, versteckte Details geben ihre Puffer zurück
  und die Kompilier-Wurzel liefert keine versteckten Meshes. Einen Browser-Test dafür
  gibt es nicht mehr (der Smoke-Test `graphics.spec.js` prüft nur noch, dass der Stufenwechsel im Ritt
  lenkbar bleibt und das Bild steht).
- **Nur Sichtbares kompilieren (`world.compileRoot`):** `renderer.compileAsync(scene)` durchläuft den
  ganzen Graphen (r186 `WebGLRenderer.compile`), also auch die versteckten Meshes (Blumen, Vögel, Kästen
  einer Stufe, die sie nicht zeigt), und baute so Programme, die nie gezeichnet werden. `compileRoot` ist
  ein Objekt, dessen `traverse` nur `scene.traverseVisible` liefert (plus die Staub-Punkte der Stufe, damit
  die erste Wolke keinen Ruckler macht); sein `traverseVisible` ist leer, denn `compile` sammelt sonst die
  Lichter ein zweites Mal ein, wenn die Wurzel nicht die Ziel-Szene ist, und baute Programme für zwei
  Lichtsätze, die das echte Rendern nie nutzt. Aufruf: `renderer.compileAsync(world.compileRoot, camera,
  world.scene)`.
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
  sie nutzt), Szenerie und ihre Details (Instanzen/LOD, Büschel, Blumen, Dekoration, Tiere nach den
  Preset-Schlüsseln `envDensity`, `grassTufts`, `flowers`, `decor`, `birds`/`butterflies`), Koppelpferde
  (`grazingHorses`, je nach `characterDetail`), Hufstaub, Pferd-/Reiter-Vertices (`characterDetail`) und
  eine Grundlast (Geometrie, Programme, Pferd, Compositor). `antialias` ist das Attribut des **echten** Kontexts.
- Der GPU-Name für das Budget kommt aus einem Wegwerf-Kontext (`probeRendererString`, vor dem
  Renderer, weil dessen Attribute von der Stufe abhängen). Er nutzt dieselbe `powerPreference`
  (`'high-performance'`) wie der echte Kontext, damit ein Laptop mit zwei GPUs dieselbe liefert
  (sonst schlank: `antialias: false`, `depth: false`).
- `gpuBudgetMB({ deviceMemory, isTouch, rendererString })` (MiB): Touch 40 MiB je GiB
  `deviceMemory`, begrenzt auf 96…320, ohne Angabe 160; Desktop 64 je GiB, 256…1024, ohne Angabe
  512; schwache GPU (`WEAK_GPU`) × 0,75. `navigator.deviceMemory` ist auf 0,25…8 GiB gerundet und
  nur in Chrome/Edge vorhanden (MDN), sagt also über starke Desktops und Firefox/Safari wenig;
  darum die vorsichtigen Standardwerte.
- `fitPresetToBudget(preset, ctx, budgetMB)` (rein) passt eine Stufe an, solange die Schätzung das
  Budget übersteigt, in dieser Reihenfolge: 1. Pixel-Ratio (größte noch passende, in 0,05-Schritten,
  nicht unter 1), 2. Schatten-Map 2048 → 1024, 3. „Gras und Umgebung“ (`SCENERY_STEPS`) in dieser Reihenfolge, je
  Schritt nur wenn die Schätzung noch zu hoch ist: Gras-Büschel aus, Blumen (mit den Schmetterlingen
  darüber) aus, Koppelpferde aus, Vögel aus, Dekoration (`decor`, mit den Blumenkästen `planters`) aus,
  dann Umgebungsdichte auf 0,55.
  Der Look der Stufe bleibt sonst („Hoch“ behält seine Effekte bei kleinerer Auflösung). Ergebnis:
  `{ preset (dasselbe Objekt, wenn nichts zu ändern ist; sonst eingefrorene Kopie mit `level`),
  estimateMB, requestedMB, budgetMB, fits, capped }`; passt auch nach allen Stufen nichts, läuft
  die Stufe trotzdem (`fits: false`).
- `chooseAntialias(preset, ctx, budgetMB)`: Antialiasing ist ein Kontext-Attribut und steht beim
  Erstellen des Renderers fest. Es bleibt nur, wenn die Stufe es will und das Budget die
  MSAA-Puffer trägt, auch wenn alle anderen Hebel schon benutzt sind; sonst entsteht der Kontext
  ohne (z. B. gespeichertes „Hoch“ auf einem schwachen Tablet). Die Debug-Box zeigt das.
- Auch das Hochstufen fragt das Budget (`fitsBudget`, ungekürzt, siehe „Hochstufen der Automatik“): die
  Automatik steigt nur auf Stufen, die ohne Kappen passen.
- Die Engine prüft **jede** Stufe: beim Start, bei jedem Wechsel (Governor und manuell, auch
  „Hoch“) und beim Start jedes Ritts (die Fenstergröße kann sich geändert haben). Sie arbeitet
  überall mit dem angepassten Preset (`fitFor(level)`); der gestufte Wechsel plant gegen dieses
  Preset (eine gekappte Pixel-Ratio ist ein eigener `pixelRatio`-Schritt). `engine.level` bleibt
  der Name der Stufe. `?testhooks&gpubudget=<MB>` erzwingt in Tests ein kleines Budget.

### Absturzwächter (SRT-013, Regel 4: `application/crash-guard.js`, `adapters/platform/page-lifecycle.js`, `tab-id.js`)

Schließt der Browser einen überlasteten Tab, läuft die Behandlung eines Kontextverlusts nie, denn die
ganze Seite ist weg. Der Absturzwächter merkt sich darum im Spielstand, dass gerade die 3D-Darstellung
gezeichnet wird, und wertet eine übrig gebliebene Markierung beim nächsten Start wie einen Verlust im
Vordergrund. Er ist reine Anwendungslogik über den Ports `store` und `clock` (`nowMs`, `nowIso`) und
dem Einstellungs-Dienst; die Entscheidung über die Stufe bekommt er als Parameter. Der `store` muss
`updateThrough()` haben (siehe „Ports“).

```js
const guard = createCrashGuard({ store, settings, clock, decide, tabId });  // decide = levelAfterContextLoss
// tabId: Kennung dieses Tabs (getTabId()) oder null; optional: foregroundGraceMs (Standard FOREGROUND_GRACE_S · 1000)
const previous = guard.checkPreviousRun();   // beim Start, VOR dem ersten Bildschirm und der Engine
// → { crashed: false } | { crashed: true, level: string|null, auto: bool, seconds }
const lease = guard.markRendering({ level, auto });  // ein Bildschirm beginnt, die Szene zu zeichnen
lease.frame({ level, auto })       // jeden Frame (billig, schreibt nur bei Bedarf), auch in der Pause
lease.release()                    // der Bildschirm zeichnet nicht mehr
guard.markBackground() / guard.resume()   // Seite versteckt oder schließt / wieder sichtbar (resume: Schonfrist)
guard.takeHint()                   // true genau einmal nach einem Absturz mit manueller Stufe über low
guard.blockedLevels()              // Stufen, auf denen die 3D-Darstellung verloren ging oder das Spiel abstürzte
guard.blockLevel(level)            // z. B. nach einem regulären Kontextverlust (Engine)
guard.clearBlockedLevels()         // „Automatisch“ neu gewählt
guard.lastCrash()                  // { level, auto, seconds, at } oder null (Diagnose-Box)
addBlockedLevel(blocked, level)    // rein: eindeutig, von low nach high geordnet, ungültiges ignoriert
```
- **Spielstand-Bereich `crashGuard`** (registriert in `crash-guard.js` über `registerSection`, mit Bereinigung
  Feld für Feld; ein alter Spielstand ohne Bereich zählt als „kein Absturz“): `rendering` (bool, Markierung),
  `level` (`low|medium|high|null`) und `auto` (bool) zur Zeit der Markierung, `since` und `lastSeen`
  (ms; Beginn und letzter Herzschlag, daraus die Dauer der Sitzung), `hintPending` (bool: bei der nächsten
  Fahrt einmal „Grafik niedriger stellen“ zeigen), `tabId` (Kennung des Tabs, der die Markierung setzte, oder `null`), `blockedLevels` (Stufen ohne Duplikate, die die Automatik
  nicht mehr anstrebt, bis „Automatisch“ neu gewählt wird) und `lastCrash` (`null` oder alle Felder
  `{ level, auto, seconds, at }`).
- **Leases:** Bildschirme, die die Szene zeichnen, halten einen Lease (`markRendering` → `frame` /
  `release`): Ritt-Bildschirm (Vorstart, Parcours, freier Modus; `ride-screen.js` ruft `lease.frame` in
  jedem Frame, auch in der Pause, `release` in `destroy`) und der Pferde-Vorschau-Bildschirm
  (`my-horse-screen.js`). Bildschirme dürfen sich überlappen (ein neuer wird vor dem Abbau des alten
  erzeugt): die Markierung bleibt, bis **alle** Leases frei sind. Der Pferde-Vorschau-Bildschirm liest
  `settings.graphicsAuto` über `settings.onChange`.
- **Markierung:** `rendering` ist wahr, solange ein Lease besteht und die Seite nicht im Hintergrund ist
  (`sync()`). Geschrieben wird nur bei Übergängen (an/aus), bei einer Änderung von Stufe oder Automatik und
  als langsamer **Herzschlag** (`lastSeen` alle `HEARTBEAT_INTERVAL_MS` = 5 s, technischer Wert), nie pro Frame.
  Ein im Hintergrund beendeter Tab zählt nie als Absturz. Jeder Schreibzugriff geht über
  `store.updateThrough('crashGuard', …)` und ändert nur den Bereich `crashGuard`: der Fortschritt eines zweiten
  Tabs wird nicht überschrieben, der eigene In-Memory-Stand anderer Bereiche bleibt unberührt. Der
  Herzschlag bestätigt auch `rendering`, `level` und `auto` neu (ein anderer Tab kann die Markierung
  gelöscht haben).
- **Schonfrist nach der Rückkehr:** `resume()` markiert nicht sofort wieder, sondern erst `FOREGROUND_GRACE_S`
  (3 s, `application/graphics-levels.js`, technischer Wert; `CONTEXT_LOSS_GRACE_S` in `quality.js` ist
  dieselbe Konstante) nach dem Wechsel in den Vordergrund (der nächste `lease.frame` setzt die Markierung):
  Android beendet oder lädt einen Tab oft direkt nach dem App-Wechsel neu, das ist kein Absturz des Spiels.
  Ein zweites `resume()` (`visibilitychange` und `pageshow`) startet die Frist nicht neu; der erste
  Seitenstart hat keine Schonfrist.
- **Seiten-Lebenszyklus** (`installPageLifecycle(guard, { doc, win })` → entfernt die Listener):
  `visibilitychange` (versteckt → `markBackground`, sichtbar → `resume`), `pagehide` (auch bei Neuladen und
  normalem Schließen → `markBackground`) und `pageshow` (sichtbar → `resume`).
- **Start** (`checkPreviousRun`, in `main.js` vor `createApp`): Eine übrig gebliebene Markierung ist ein
  Absturz, es sei denn, sie gehört einem anderen, noch zeichnenden Tab. Das entscheidet die **Tab-Kennung**
  (`adapters/platform/tab-id.js`, `getTabId()`: einmal je Tab in `sessionStorage` unter
  `zoes-horse-farm.tabId`, übersteht ein Neuladen desselben Tabs, auch das Neuladen, das der Browser sofort nach
  dem Beenden eines überlasteten Tabs macht; ohne `sessionStorage` `null`). `main.js` übergibt sie als `tabId`
  an den Wächter (Schichten: der Zugriff bleibt im Adapter), der sie bei jedem Setzen und jedem Herzschlag in
  die Markierung schreibt (Feld `tabId`, `null` = unbekannt):
  - Markierung mit **eigener** Kennung → Absturz, egal wie frisch der Herzschlag ist (derselbe Tab kam ohne
    sauberes `pagehide` zurück);
  - **andere** Kennung (oder keine in der Markierung) und `lastSeen` jünger als zwei Intervalle
    (`2 · HEARTBEAT_INTERVAL_MS`, 10 s) → ein anderer Tab zeichnet noch: kein Absturz, Markierung unverändert;
  - andere Kennung und alter Herzschlag → Absturz (z. B. der ganze Browser wurde beendet und neu geöffnet);
  - eigene Kennung unbekannt (kein `sessionStorage`) → Absturz (lieber erkennen).
  Mit Stufe wird `decide({ auto, level })` angewandt (dieselbe Regel wie beim Kontextverlust:
  Automatik → `settings.setAutoLevel('low')`; manuell über `low` → `hintPending`); jede abgestürzte Stufe
  kommt in `blockedLevels` (auch auf `low`, auch bei manueller Wahl), `lastCrash` wird mit `clock.nowIso()`
  gesetzt. `takeHint()` löscht den Hinweis; der Ritt-Bildschirm zeigt ihn (`showCrashHint`) als
  `ride.graphicsContextLost`-Toast nur, wenn die Stufe dann immer noch manuell über `low` steht
  (`canHintLowerLevel`). `main.js` legt `app.services.crashGuard` und `app.services.startupCrash` ab (die
  Engine nimmt daraus `lastChange = { kind: 'crash' }` für die Diagnose).
- **Bekannte Grenzen der Tab-Erkennung:** Chrome „Tab duplizieren“ kopiert `sessionStorage`, das Duplikat hat also
  dieselbe Tab-Kennung wie das Original; ein Duplikat eines noch zeichnenden Tabs kann dessen frische
  Markierung darum als Absturz zählen. Eine Markierung ohne Tab-Kennung aus der Vorversion mit frischem
  Herzschlag gilt einmalig als zeichnender Tab (kein Absturz).
- **Verdrahtung in `main.js`:** `createCrashGuard({ store, settings, clock: systemClock, decide:
  levelAfterContextLoss, tabId: getTabId() })`, `settings.onAutoSelected(() => crashGuard.clearBlockedLevels())` und
  `installPageLifecycle(crashGuard)`. Die Engine fragt `crashGuard.blockedLevels()` für das Hochstufen und
  ruft `crashGuard.blockLevel(level)` bei einem gezählten Kontextverlust; ohne Wächter (nacktes Test-Setup)
  läuft sie wie zuvor.
- **Tests:** `crash-guard.test.js`, `page-lifecycle.test.js`, `tab-id.test.js`, `test-hooks.test.js` (Frame-Feed)
  und `quality-upgrade.test.js` tragen die Regeln (eigene und fremde Tab-Markierung, Schonzeiten, gesperrte, über dem
  Budget liegende und wegen Ruckelns verlassene Stufen, Hinweis einmal, alter Spielstand; `debug-display.test.js` für die Diagnose-Zeile). Im Browser
  sind es nur Smoke-Tests: `tests/smoke/graphics-crash.spec.js` (Absturz bei Automatik → `low` und Automatik bleibt;
  manuell über `low` → Hinweis; die Markierung folgt dem Ritt; ein normales Neuladen im Ritt ist kein Absturz) und
  `tests/smoke/graphics-upgrade.spec.js` (erster Start bei `low`; mit schnellen Frames `low → medium`, gespeichert, die
  Diagnose-Box nennt den Grund, der Ritt bleibt lenkbar und die Stufe gilt beim nächsten Start). Der Aufstieg treibt
  die Messung über `__zhfTest.setFrameFeed` statt über echte Wartezeiten. Dass eine manuelle Stufe nie hochgestuft
  wird, sichert die reine Torfunktion `upgradeMeasuring({ measuring, auto })` (`quality-upgrade.js`), die die Engine vor
  jedem Messschritt des Aufstiegs fragt (Test in `quality-upgrade.test.js`).

### Versionsanzeige (SRT-015, Regel 58)

Beim Build entsteht die Version `"<Commit-Datum> · <7 Zeichen der Kennung>"`, z. B. `2026-10-05 · 3fdf19e`
(Datum des Commits, nicht der Bauzeit), oder `dev`, wenn kein Git-Stand vorhanden ist. Berechnet wird sie
vom reinen, getesteten Node-Helfer `scripts/app-version.mjs` (`formatAppVersion`, `resolveAppVersion`
mit injiziertem `git`; Test `tests/build/app-version.test.js`): die Kennung kommt aus `GITHUB_SHA`
(GitHub-Build), sonst aus `git rev-parse HEAD`; das Datum immer aus `git log -1 --format=%cs` (der
Standard-Checkout mit Tiefe 1 genügt); fehlt eines von beiden oder ist es ungültig, ist das Ergebnis `dev`
(der Helfer wirft nie). `vite.config.js` reicht das Ergebnis als `define: { __APP_VERSION__ }` in den Build
(ESLint kennt sie als globale Konstante). Gelesen wird die Konstante nur in
`src/adapters/platform/app-version.js` (`APP_VERSION`, Rückfall `dev`, wenn sie fehlt, z. B. in Vitest).
Angezeigt wird `APP_VERSION` an zwei Stellen: als dezente Zeile `[data-field="version"]`
(`settings.version`: „Version {version}“) im Einstellungs-Abschnitt `version` (`order: 99`, also ganz unten,
registriert in `ui/settings-sections.js`; aus Hauptmenü und Pausenmenü) und als erste Zeile der
Diagnose-Box (`debug.version`).

### Diagnose-Box (`?debug`)

Nur mit `?debug` in der Adresse (Erkennung `debugRequested` in `adapters/platform/debug-info.js`,
wie `?testhooks`); ohne den Parameter wird nichts installiert und kein Element erzeugt. `main.js`
legt dann `app.services.debug = { errorLog }` an und installiert `installErrorCapture` (schreibt
`console.error`, `window`-`error` und `unhandledrejection` in `createErrorLog`, die letzten 5, je
höchstens 160 Zeichen; `console.error` druckt weiter). Der Ritt-Bildschirm hängt unter die
fps-Zeile (`.ride-hud`) die Box `[data-hud="debug"]` (`ui/debug-display.js`, `createDebugBox`): sie
aktualisiert sich etwa zweimal pro Sekunde (eigener `createFpsMeter`-Takt, kein Objekt pro Frame
im Ritt), auch im Pausenmenü und bei verlorenem Kontext. Der Text kommt aus der reinen Funktion
`formatDebugText(info, errors, t, lastCrash, version)` (erste Zeile: die Version, Regel 58); alle Wörter stehen in `ui/i18n/debug.js` (`debug.*`, DE + EN),
eingesetzt werden nur Zahlen und technische Zeichenketten (GPU-Name, Fehlertexte). Die Zahlen
liefert `engine.diagnostics()` (immer dasselbe Objekt): GPU (`WEBGL_debug_renderer_info`, sonst
`RENDERER`), Stufe und Automatik, `devicePixelRatio`, Pixel-Ratio des Renderers, Zeichenpuffer,
größte Textur, Antialiasing (an/aus, oder „aus: zu wenig Grafikspeicher“, wenn `chooseAntialias`
das Budget-Urteil „nein“ gespeichert hat; fehlt MSAA nur, weil der Browser es nicht gibt, steht dort
„aus“), GPU-Name des Budgets (eigene Zeile, nur wenn der Probe-Kontext einen anderen Namen als der
Renderer lieferte; bei verlorenem Kontext ersatzweise in der GPU-Zeile), GPU-Speicher-Schätzung
gegen Budget (z. B. „GPU est. 180 / 256 MB, ratio capped 2 → 1.25“, plus Zeilen für gekappte
Schatten-Map und verringerte Szenerie), Anzahl Kontextverluste/-wiederherstellungen mit Sekunden seit Seitenstart, die Zeilen der
Grafik-Automatik und ausstehende Stufenwechsel-Schritte. Die Automatik-Zeilen (SRT-013, SRT-014):
- „Letzter Absturz“: Stufe, Auto/manuell, Sekunden bis zum Absturz und Zeit (UTC) aus dem `lastCrash`
  des Absturzwächters, den der Ritt-Bildschirm beim Erzeugen der Box übergibt
  (`createDebugBox({ …, lastCrash })`, `formatDebugText(info, errors, t, lastCrash)`), sonst „keiner“;
- „Gesperrte Stufen“: `blockedLevels` (Absturzwächter), sonst „keine“;
- „Wegen Ruckeln verlassen“: `leftLevels` (`leftByFps`), nur wenn nicht leer;
- „Letzter Stufenwechsel (Auto)“: `lastChange` als Grund: `hoch bei {fps} fps`, `runter bei {fps} fps`,
  `Grafik verloren` oder `Absturz`, sonst „keiner“.
`engine.diagnostics()` liefert dafür `blockedLevels`, `leftLevels` und `lastChange`; alle Wörter stehen
in `ui/i18n/debug.js` (`debug.crash`, `debug.blocked`, `debug.left`, `debug.lastChange`, `debug.reason*`).

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
Verhalten im Browser per Smoke-Test (`tests/smoke/`, nur Chromium, u. a. `colors.spec.js` für die Button-Farben und
`help.spec.js` für die Bedienungs-Tipps; die Grafik-Specs `graphics*.spec.js` bleiben klein: je ein Ablauf pro
Funktion, Grenzwerte und Sonderfälle gehören in Unit-Tests, und "das Bild steht" prüft `expectPicture` über eine
kleine Kopie der Zeichenfläche statt über einen Screenshot; sie zählt Farben im ganzen Bild und in der unteren
Hälfte, damit der Himmel allein nicht als Szene gilt: Himmel 8 bzw. 1 Farbe, Reit-Szene 88 bis 178 bzw. 27 bis 47,
Grenzen 40 bzw. 12 in `tests/smoke/helpers.js`). Adapter-Tests ohne Browser laufen in Node: three.js-Szenen
über `tests/support/scene-stats.js` und `fake-canvas.js` (Draw Calls, Dreiecke, Texturen ohne GL),
Bewegung über `tests/support/sequence-helper.js` (Kontinuität von Frame zu Frame), Farben über
`tests/support/color.js`.

**Bewusst nicht mehr abgedeckt (Entscheidung des Nutzers, die Grafik-Smoke-Tests klein zu halten):** Die
folgende Verdrahtung hat weder einen Smoke- noch einen Unit-Test, nur die Regeln dahinter sind unit-getestet
(`quality.test.js`, `resilience.test.js`, `crash-guard.test.js`, `debug-display.test.js`). Wer sie anfasst, prüft sie
von Hand im Browser.

- Ritt-Bildschirm: Neuladen-Angebot nach dem Wächter der Wiederherstellung (Hinweistext, Schaltfläche
  „Neu laden“ sichtbar und fokussiert; späte Wiederherstellung führt zurück ins normale Menü).
- Ein Kontextverlust vor dem Start des Rittes: der Ritt beginnt pausiert.
- Engine bei Kontextverlust: der Zeichenpuffer schrumpft, solange der Kontext fehlt; ein gezählter Verlust sperrt
  die Stufe (`crashGuard.blockLevel`).
- Manueller Wechsel auf `high`: die Prüfung gegen das Budget (`applyQuality` → `fitFor`).
- Verdrahtung des Hinweises bei niedriger fps (`canHintLowerLevel` → Toast).
- Die Diagnose-Box existiert ohne `?debug` nicht (Verdrahtung in `main.js` und im Ritt-Bildschirm).
