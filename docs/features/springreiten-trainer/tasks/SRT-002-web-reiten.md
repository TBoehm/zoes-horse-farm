---
id: SRT-002
title: Reitplatz, Pferd und Reiten – Szene, Gangarten, Steuerung, Kamera, Grafikstufen
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-001]
effort: L
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Baut auf dem Grundgerüst (SRT-001) auf. Liefert die 3D-Welt und das Reiten ohne Springen: einen
prozedural erzeugten Reitplatz, ein prozedural modelliertes und animiertes Pferd mit Reiter, die
Steuerung per Tastatur und Touch, die Kamera und die Grafikstufen mit Automatik. Zugang ist der
Hauptmenü-Eintrag „Freier Modus", der in diesem Ticket einen leeren Reitplatz (ohne Hindernisse)
zeigt; SRT-003 stellt dort die Hindernisse auf und ergänzt das Springen.

## Ziel
Das Kind wählt im Hauptmenü „Freier Modus" und reitet flüssig (Ziel 60 fps) in allen Gangarten über
einen realistisch wirkenden Reitplatz, per Tastatur oder Touch, mit umschaltbarer Kamera, Pause und
automatisch angepasster Grafikqualität.

## Scope
- In:
  - Hauptmenü-Eintrag „Freier Modus" (Regel 53) mit eingezäuntem Reitplatz und Umgebung, alles zur
    Laufzeit erzeugt (Regeln 2, 3).
  - Pferd (Vorgabe-Aussehen: Brauner mit Stern, Regel 43) und Reiter, prozedural, mit Animation je
    Gangart (Regel 24, Teil Gangarten); Umzäunung als Grenze (Regel 24).
  - Tempo und Gangarten inkl. Galopp-Logik (Regeln 8, 9).
  - Touch-Bedienelemente: Joystick, Galopp-Umschalter, Springen-Button (ohne Sprungfunktion, siehe
    Out), Pause, Kamera (Regel 10); sichtbar im Touch-Modus (Regel 11).
  - Kamera: Standard und Reiter-Sicht, umschaltbar, gespeichert (Regeln 13, 14, 44).
  - Grafikstufen Niedrig/Mittel/Hoch und „Automatisch" mit Abwärts-Automatik, Einstellung im
    Einstellungen-Bildschirm, gespeichert (Regeln 3, 4, 44).
  - Pause im freien Modus: Auslöser, Pausemenü, „Weiter"-Verhalten, „Neu starten" (Pferd an den
    Startpunkt), „Zum Menü" (Regeln 12, 38, 39 Teil freier Modus).
- Out:
  - Hindernisse, Springen, Hopser, Verweigerung, Absprung-Hilfe (SRT-003). Space bzw. „Springen"
    haben in diesem Ticket noch keine Wirkung.
  - Sprung-Animation (Absprung, Flug, Landung) (SRT-003).
  - Parcours, Vorstart, Zeitmessung (SRT-004).
  - Fellfarben, Kopfabzeichen, Name (SRT-005).
  - Klang (SRT-006).
  - Einschätzbarkeit von Hindernis und Distanz aus der Standard-Kamera (Regel 13, zweiter Teil; prüfbar erst mit Hindernissen in SRT-003).

## Design (SSoT)
Kein Design-Entwurf (bewusst ohne Design freigegeben).

| Bildschirm / Zustand | Inhalt |
| --- | --- |
| Reiten (Tastatur) | 3D-Ansicht, keine Bedienelemente eingeblendet außer einem kleinen Pause-Hinweis (Esc). |
| Reiten (Touch-Modus) | Unten links Joystick, unten rechts große Buttons „Galopp" (zeigt an/aus) und „Springen", oben rechts „Pause" und „Kamera". Alle Elemente ≥ 44×44 px, halbtransparent, verdecken die Bildmitte nicht. |
| Pausemenü | Abgedunkelte, angehaltene Szene; „Weiter", „Neu starten", „Zum Menü", „Einstellungen". |
| Einstellungen | ergänzt um „Grafik: Automatisch / Niedrig / Mittel / Hoch". Keine Kamera-Auswahl (Kamera nur per C bzw. Button). |

## Akzeptanzkriterien
- [x] Im Hauptmenü erscheint „Freier Modus" an seiner Position laut Regel 53 (vor „Einstellungen"); er ist ab dem ersten Start verfügbar und öffnet den Reitplatz mit Pferd und Reiter; bestehende Einträge bleiben unverändert (Regeln 41 Verfügbarkeit, 53, 54). (Nachweis: tests/smoke/profile.spec.js › main menu entries › all entries of rule 53 in order, with the horse name in the greeting; tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files; src/adapters/ui/menu.js (Registry; Eintrag „free“ ohne Änderung der anderen))
- [x] Beim Betreten des freien Modus steht das Pferd am Startpunkt im Halt, der Touch-Galopp-Umschalter steht auf aus (Regel 39). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files (gait halt, speed 0, gallop false); tests/smoke/ride.spec.js › touch controls (SRT-002) › joystick, gallop, jump, pause and camera are visible and at least 44x44 px (Galopp aria-pressed=false); src/application/ride-session.test.js › view › starts at the mode start pose with all rails up and nothing highlighted (free mode))
- [x] Reitplatz (Sandboden, Umzäunung), Umgebung, Pferd und Reiter entstehen ohne geladene Bild-, Audio-, Modell- oder Schriftdateien (Regel 2). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files (watch.forbidden = []); src/adapters/view3d/arena.js, environment.js, textures.js (prozedural))
- [x] Das Pferd hat beim ersten Start das Aussehen Brauner mit Stern (Regel 43, Vorgabe). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene ... (Spielstand coat bay, marking star); src/adapters/view3d/horse/coats.test.js › coat colours and markings › default is bay with star; invalid values fall back to the default)
- [x] A/D lenken links/rechts; W erhöht und S verringert das Tempo stufenlos, solange gedrückt (Regel 8). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › W raises the speed, Shift gives canter, the horse moves; src/domain/sim/movement.test.js › Speed (rules 8–10) › W increases speed continuously through walk up to trot and never beyond trotMax / S brakes to a halt (speed 0) / speed is kept without input; src/adapters/input/keyboard.test.js › keyboard input (rule 8) › maps WASD and arrow keys to steer/throttle)
- [x] Auch im Halt wenden A/D bzw. der Joystick das Pferd auf der Stelle (Regel 22). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › A and D turn the horse on the spot; src/domain/sim/movement.test.js › Steering (rules 8, 10, 22) › turns on the spot at halt; right turns to the right)
- [x] Ohne Galopp liegt das Tempo stufenlos zwischen Halt, Schritt und Trab; die Gangart des Pferdes (erkennbar an der Bewegung, ohne eigene Anzeige) wechselt passend zum Tempo (Regel 9). (Nachweis: src/domain/sim/movement.test.js › Gait from speed (rule 9) › maps halt, walk and trot by speed, gallop is always canter; src/domain/sim/movement.test.js › Speed (rules 8–10) › W increases speed continuously through walk up to trot and never beyond trotMax / a walking horse without gallop stays at its pace (no automatic trot))
- [x] Solange Shift gehalten wird, galoppiert das Pferd und W/S regeln das Galopptempo stufenlos; nach Loslassen fällt es in den Trab (Regel 9). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › W raises the speed, Shift gives canter, the horse moves; src/domain/sim/movement.test.js › Speed (rules 8–10) › W/S control the canter speed within [canterMin, canterMax] / gallop off: trot, speed drops gently to working trot; src/adapters/input/keyboard.test.js › gallop latch (rule 9) › Shift held gives gallop; a latch needs release and a new press)
- [?] Halt, Schritt, Trab und Galopp sind am Bewegungsablauf des Pferdes erkennbar unterscheidbar (Regel 24). (Nicht hier prüfbar: Ob die Gangarten im Bild gut unterscheidbar wirken, ist ein visueller Eindruck. Vorhandener Nachweis: src/adapters/view3d/horse/gaits.test.js › gaits: cadence and stride length (riding-theory values) › walk ≈ 55/min, trot ≈ 80/min, canter ≈ 100/min at typical speed / duty factor: walk ≈ 0.6 (no suspension), trot 0.35–0.45, canter 0.3–0.4; src/adapters/view3d/horse/motion.test.js › footfall › walk: four-beat LH → LF → RH → RF, ¼ cycle apart / trot: diagonal pairs land together / canter (left lead): RH → LH+RF → LF, then suspension)
- [x] Das Pferd kann den Reitplatz nicht verlassen. Frontal auf die Umzäunung: Es stoppt und ist im Halt, Galopp ist aus (auch der Touch-Umschalter; Tastatur: Shift neu drücken). Schräg auf die Umzäunung: Es gleitet mit unverändertem Tempo daran entlang (Regeln 9, 24). (Nachweis: src/domain/sim/movement.test.js › Fencing (rule 24) › frontal: stop, halt, gallop off with events / oblique: slides along the wall in parallel with unchanged speed / the horse never leaves the arena (random riding); tests/smoke/ride.spec.js › free riding (SRT-002) › the fence stops the horse head-on: halt, gallop off; src/application/ride-session.test.js › end of a gallop › asks the input to end the gallop when the horse is stopped at the fence; src/adapters/input/input.test.js › input rules › the game ending the gallop switches touch off and needs a new Shift press)
- [x] Im Touch-Modus sind Joystick, „Galopp", „Springen", „Pause" und „Kamera" sichtbar, jeweils mindestens 44×44 px; ohne Touch-Modus sind sie ausgeblendet (Regeln 10, 11). (Nachweis: tests/smoke/ride.spec.js › touch controls (SRT-002) › joystick, gallop, jump, pause and camera are visible and at least 44x44 px; tests/smoke/ride.spec.js › no touch controls without touch mode › the desktop ride has no touch controls, only the pause hint; src/adapters/input/input.test.js › input rules › shows the touch controls according to the mode)
- [x] Joystick hoch/runter ändert das Tempo, solange ausgelenkt; stärkere Auslenkung ändert es schneller (Regel 10). (Nachweis: src/adapters/input/joystick-mapping.test.js › mapStick (nipplejs force/angle to steer/throttle) › pushing right steers right (+1), up gives full throttle / pushing left steers left, down brakes; src/domain/sim/movement.test.js › Speed (rules 8–10) › rate is proportional to the deflection (joystick); tests/smoke/ride.spec.js › touch controls (SRT-002) › dragging the joystick rides: speed rises, the horse turns, no page errors (SRT-002 B1))
- [x] Joystick links/rechts lenkt stufenlos; stärkere Auslenkung ergibt eine engere Kurve (Regel 10). (Nachweis: src/adapters/input/joystick-mapping.test.js › mapStick (nipplejs force/angle to steer/throttle) › the dead zone applies per axis and the range above it is rescaled to 0..1; src/domain/sim/movement.test.js › Steering (rules 8, 10, 22) › steering strength is proportional to |steer| / the turn radius grows with speed; tests/smoke/ride.spec.js › touch controls (SRT-002) › dragging the joystick rides: speed rises, the horse turns, no page errors (SRT-002 B1))
- [x] „Galopp" schaltet per Tippen Galopp an bzw. aus und zeigt sichtbar, ob Galopp an ist; bei „Aus" fällt das Pferd in den Trab (Regeln 9, 10). (Nachweis: tests/smoke/ride.spec.js › touch controls (SRT-002) › joystick, gallop, jump, pause and camera are visible and at least 44x44 px (aria-pressed false → true nach Tippen); src/domain/sim/movement.test.js › Speed (rules 8–10) › gallop off: trot, speed drops gently to working trot; src/adapters/input/input.test.js › input rules › the game ending the gallop switches touch off and needs a new Shift press)
- [x] Wechselt auf einem Gerät mit Tastatur und Touchscreen der Touch-Modus (an oder aus), endet ein aktiver Galopp (Trab) und der Touch-Umschalter steht auf aus (Regeln 9, 11). (Nachweis: src/adapters/input/input.test.js › input rules › a touch-mode switch ends the touch gallop (rules 9/11) / a touch-mode switch caused by pressing Shift does not start a gallop until Shift is pressed anew / ... caused by an arrow key also counts (shared game keys))
- [?] Die Standard-Kamera läuft schräg hinter und über Pferd und Reiter mit (Regel 13). (Nicht hier prüfbar: Ob die Kameraführung gut aussieht und einschätzbar ist, ist ein visueller Eindruck. Vorhandener Nachweis: src/adapters/view3d/camera.js (FOLLOW: 7,5 m hinter, 3,6 m über dem Pferd, Blick voraus); tests/smoke/ride.spec.js › free riding (SRT-002) › C toggles the camera, the choice survives a reload, settings have no camera choice (Standard follow))
- [x] C bzw. der Kamera-Button schaltet zwischen Standard-Kamera und Reiter-Sicht zwischen den Pferdeohren um; die zuletzt gewählte Kamera gilt nach Neuladen wieder; in den Einstellungen gibt es keine Kamera-Auswahl (Regeln 14, 44). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › C toggles the camera, the choice survives a reload, settings have no camera choice; tests/smoke/ride.spec.js › touch controls (SRT-002) › joystick, gallop, jump, pause and camera are visible and at least 44x44 px (Kamera-Button schaltet um); src/application/settings-service.test.js › camera › saves follow and rider, ignores anything else)
- [x] Beim ersten Start ist „Automatisch" aktiv und eine zum Gerät passende Stufe gewählt (Regel 4). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › the graphics setting offers automatic and three levels and applies the choice (graphicsAuto beim ersten Start true); tests/smoke/ride.spec.js › free riding (SRT-002) › a first start without a graphics level in the save picks one; src/adapters/view3d/quality.test.js › pickInitialLevel › picks low for software renderers / picks high for a strong desktop / touch devices get at most medium, weak ones low)
- [x] Bei aktivem „Automatisch": Liegt die durchschnittliche Bildrate 5 Sekunden lang unter 50 fps, sinkt die Stufe um eins (nie unter Niedrig); danach vergehen mindestens 10 Sekunden bis zur nächsten Anpassung; die Automatik stuft nie hoch (Regel 4). (Nachweis: src/adapters/view3d/quality.test.js › createQualityGovernor › downgrades one level after 3 s grace + 5 s below 50 fps / stays at 50 fps or more / waits at least 10 s after an adjustment / never below low / never upgrades)
- [x] Die Automatik misst nur während des Reitens; Pause, Menüs, ein versteckter Tab oder ein minimiertes Fenster sowie die ersten 3 Sekunden danach senken die Stufe nicht (Regel 4). (Nachweis: src/adapters/view3d/quality.test.js › createQualityGovernor › does not measure without measuring and needs 3 s grace afterwards / an interruption resets the window and the grace period; src/adapters/ui/screens/ride-screen.js (governor.frame(rawDt, false) bei Pause/Menü, !document.hidden; governor.interrupt() bei Pause))
- [x] Eine automatisch gesenkte Stufe gilt nach Neuladen weiter (Regeln 4, 44). (Nachweis: src/application/settings-service.test.js › graphics › governor downgrade keeps auto on and only changes the level; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › saves immediately on change; tests/smoke/ride.spec.js › free riding (SRT-002) › the graphics setting offers automatic and three levels and applies the choice (Wert nach Reload))
- [x] Wählt das Kind eine Stufe manuell, gilt sie und die Automatik ist aus; wählt es wieder „Automatisch", wird neu passend zum Gerät gewählt (Regel 4). (Nachweis: src/application/settings-service.test.js › graphics › manual level: auto off, level saved / automatic: auto on, level is the device level; src/adapters/view3d/quality.test.js › createQualityGovernor › setAuto(true) starts with grace, setLevel sets manually; tests/smoke/ride.spec.js › free riding (SRT-002) › the graphics setting offers automatic and three levels and applies the choice)
- [?] Auf der automatisch gewählten Stufe erreicht der Reitplatz auf einem aktuellen PC und einem aktuellen Tablet beim Reiten über 30 Sekunden im Mittel mindestens 50 fps (Ziel 60 fps); Stufe „Niedrig" ist sichtbar einfacher als „Hoch" (Regel 3). (Nicht hier prüfbar: Echte Bildrate (mindestens 50 fps auf aktuellem PC und Tablet) lässt sich nur auf Geräten messen. Vorhandener Nachweis: src/adapters/view3d/quality.test.js › QUALITY_PRESETS › has all levels with pixel ratio and shadow values / antialiasing is off on low and on above (SRT-002 m4))
- [?] Der Reitplatz läuft in aktuellen Versionen von Chrome, Safari (inkl. iPad/iPhone), Firefox und Edge (Regel 3). (Nicht hier prüfbar: Firefox/Edge/WebKit-Läufe der CI-Matrix haben auf GitHub noch nicht stattgefunden; Safari auf iPad/iPhone nur auf echten Geräten. Vorhandener Nachweis: .github/workflows/ci.yml (matrix chromium, firefox, webkit, msedge); lokal läuft tests/smoke/ride.spec.js in Chromium)
- [x] Esc bzw. der Pause-Button pausiert: Pferd und Szene stehen still, das Pausemenü erscheint mit „Weiter", „Neu starten", „Zum Menü", „Einstellungen" (Regel 38). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › Esc pauses: the horse stands still, the pause menu offers four actions; tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: Esc continues, Tab stays inside, Space presses the focused button; tests/smoke/ride.spec.js › touch controls (SRT-002) › joystick, gallop, jump, pause and camera are visible and at least 44x44 px (Pause-Button))
- [x] Bei Tab-/App-Wechsel, minimiertem Fenster oder Drehen ins Hochformat (im Touch-Modus) pausiert das Spiel automatisch und läuft erst mit „Weiter" weiter (Regeln 12, 38). (Nachweis: tests/smoke/ride.spec.js › touch controls (SRT-002) › turning to portrait pauses the ride and shows the rotate notice; src/adapters/ui/screens/ride-screen.js (onHidden/onBlur → setPaused(true); rotateBlocked → setPaused(true)); Hinweis: Tab-/Fensterwechsel nur im Code, dafür gibt es keinen eigenen Test; Hochformat im Touch-Modus ist per Smoke-Test belegt)
- [x] Nach „Weiter" läuft das Pferd mit vorherigem Tempo und vorheriger Gangart weiter; Tastatur-Galopp bleibt nur, wenn Shift noch gehalten wird; der Touch-Galopp-Umschalter behält seinen Zustand (Regel 38). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › Esc pauses: the horse stands still, the pause menu offers four actions (Weiter: Tempo bleibt); src/adapters/input/input.test.js › input rules › keeps the touch gallop across a pause (clearEdges does not touch it) / clearEdges (after "Continue") drops pending jump/pause/camera of both sources; src/adapters/input/keyboard.test.js › gallop latch (rule 9) › a window blur releases everything)
- [x] „Neu starten" im freien Modus setzt das Pferd im Halt an den Startpunkt; der Touch-Galopp-Umschalter steht danach auf aus (Regeln 38, 39). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: restart puts the horse back, "to menu" leaves, settings keep the pause; src/application/ride-session.test.js › restart › puts the horse back, resets the mode and asks to reset the touch gallop)
- [x] „Zum Menü" führt zurück ins Hauptmenü (Regel 38). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: restart puts the horse back, "to menu" leaves, settings keep the pause)
- [x] Die Einstellungen sind auch aus dem Pausemenü erreichbar und enthalten die Grafikstufe (Regeln 4, 38). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: restart puts the horse back, "to menu" leaves, settings keep the pause; tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: Esc continues, Tab stays inside, Space presses the focused button; src/adapters/ui/settings-screen.js (gleiche Sektionen inkl. Grafik, fromPause blendet nur „Fortschritt löschen“ aus))
- [x] Alle neuen Texte (Pausemenü, Touch-Buttons, Grafik-Einstellung) liegen auf Deutsch und Englisch vor (Regel 6). (Nachweis: src/adapters/ui/i18n/strings.test.js › texts (rule 6) › have the same keys in German and English / are not empty and have the same placeholders)
- [x] Fehlt ein gespeicherter Wert für Grafikstufe oder Kamera oder ist er ungültig (z. B. Spielstand aus der Version von SRT-001), gelten „Automatisch" bzw. die Standard-Kamera, ohne Absturz (Regel 47). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › invalid saved graphics and camera values fall back without a crash; src/adapters/storage/local-store.test.js › loading save data (rule 47) › keeps readable values and resets invalid ones; src/application/save-schema.test.js › objectSection › replaces invalid fields with the env-dependent fallback and keeps valid ones)
- [x] Nach dem ersten vollständigen Laden startet der freie Modus auch offline (Regel 5). (Nachweis: tests/smoke/pwa.spec.js › PWA › starts offline after the first load (startet einen freien Ritt offline))
- [x] Der Browser-Smoke-Test aus SRT-001 prüft zusätzlich, dass der freie Modus startet und die 3D-Szene ohne Konsolenfehler rendert. (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files)

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (Regeln 2, 3, 4, 8–14, 24, 38, 39, 43 Vorgabe-Aussehen, 44, 53, 54; §Plattform-Ausprägungen; §Grenzfälle „Pferd an der Umzäunung", „Fokusverlust", „Schwaches Gerät")
- Voraussetzung: SRT-001
- Folge: SRT-003 (Springen im freien Modus), SRT-005 (Aussehen anpassen), SRT-006 (Hufschlag)

## Definition of Ready
- [x] Ziel klar, nutzersichtbares Ergebnis benannt
- [x] Fachlichkeit geplant; UI designt oder "kein UI" (bewusst ohne Design freigegeben, Zustände im Ticket)
- [x] Zuordnung zu Feature/Konzept gesetzt und auflösbar
- [x] Ziel-Plattform gesetzt
- [x] Betroffene Produkt-Plattformen benannt
- [x] Konzept verlinkt, kein Widerspruch zum dokumentierten Stand
- [x] Akzeptanzkriterien einzeln prüfbar
- [x] Abhängigkeiten benannt, passend zu depends_on
- [x] Aufwand geschätzt
## Definition of Done
- [x] Alle Akzeptanzkriterien erfüllt, mit Nachweis (außer [?]-Zeilen: Gerät/Mensch/nach Merge; Nachweis je Zeile siehe oben)
- [x] Quality Gates grün, Review ohne blocker/major (Nachweis: lokal lint, format, 790+ Unit-Tests, Build, 64 Smoke-Tests grün; Opus-Reviews je Ticket Runde 2 ohne blocker/major; Standards-QA)
- [x] Konzept/Doku nachgezogen (Nachweis: docs/specs/springreiten-trainer/architecture.md, README.md, CLAUDE.md)
- [ ] PR/MR gemerged
