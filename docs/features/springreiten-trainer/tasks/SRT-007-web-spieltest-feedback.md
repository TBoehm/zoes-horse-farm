---
id: SRT-007
title: Spieltest-Feedback – Rückwärtsrichten, Lenkung, Grafikwechsel, fps-Anzeige
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-002, SRT-003]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (Ausprägungen nach Eingabeart Tastatur/Touch).

Erster Spieltest nach dem Deploy (2026-10-04):

| Beobachtung | Ursache | Soll |
| --- | --- | --- |
| „Abwurf"-Meldung, aber der Reiter bleibt oben | „Abwurf" ist der Fachbegriff für eine gefallene Stange, wird als Sturz gelesen | Meldung „Stange gefallen!" (Regel 23) |
| Rückwärtsgang geht per Touch nicht | Es gibt kein Rückwärtsgehen (auch nicht per Tastatur) | Rückwärtsrichten aus dem Halt (Regeln 8, 9, 10, 24) |
| Lenkung reagiert erst bei großer Auslenkung | Achsweise tote Zone + lineare Kennlinie; schräge Auslenkung lenkt kaum | engste Kurve vor dem Anschlag, schräg lenkt deutlich (Regel 10) |
| Nach Grafikwechsel nicht mehr spielbar | Im Test steuerbar, aber „Hoch" fällt auf schwacher Hardware auf wenige fps; alle Shader und Texturen werden beim Wechsel neu aufgebaut; WebGL-Kontextverlust wird nicht behandelt | Wechsel bleibt spielbar, Kontextverlust pausiert, Hinweis bei zu hoher manueller Stufe (Regel 4) |
| Keine fps-Anzeige | fehlte | ausschaltbare fps-Anzeige (Regeln 4, 44) |

## Ziel
Die Rückmeldungen aus dem ersten Spieltest sind behoben: verständliche Meldung, Rückwärtsrichten
per Tastatur und Touch, direktere Lenkung, ein Grafikwechsel, nach dem man weiterspielen kann, und
eine ausschaltbare fps-Anzeige.

## Scope
- In: Regeln 4 (neue Punkte), 8, 9 (Rückwärtsrichten), 10 (Lenk-Kennlinie), 23 (Meldungstext),
  24 (Animation rückwärts), 44 (Einstellung gespeichert); Texte DE/EN.
- Out: Gamepad; Hochstufen durch die Automatik; neue Grafikstufen.

## Design (SSoT)
Kein Design-Werkzeug. fps-Anzeige: kleine Zeile „58 fps · Mittel (Auto)" in der oberen linken Ecke
(nicht unter Pause/Kamera oben rechts, nicht über dem Joystick unten links; im Parcours über den
HUD-Chips), im Stil der HUD-Texte. Einstellung als
Ein/Aus-Umschalter im Abschnitt „Grafik". Hinweis bei zu hoher Stufe als Toast wie die
Sprung-Meldungen.

## Akzeptanzkriterien
- [x] Bei einer gefallenen Stange erscheint „Stange gefallen!" (EN „Pole down!"); Ergebnis und
  Auszeichnungen sprechen ebenso von gefallenen Stangen (Regel 23). (Nachweis:
  src/adapters/ui/i18n/strings.test.js › say "fallen pole" instead of "Abwurf" (Feedback DE/EN,
  results.knockdowns, kein „Abwurf"/„Abwürfe" in allen DE-Texten); tests/smoke/profile.spec.js
  (Auszeichnungstext „Komm ins Ziel"))
- [x] Im Halt mit gehaltenem S geht das Pferd nach < 0,5 s langsam rückwärts (langsamer als
  Schritt), lenkbar; Loslassen hält an; W/Galopp beenden es (Regel 9). (Nachweis:
  src/domain/sim/rein-back.test.js (Pause 0,45 s, maxSpeed < walkMax/2, Loslassen, W, Galopp);
  src/domain/sim/riding-sim.test.js › Rein-back (rules 8, 9, 24) › waits a short pause in halt /
  backs along the reverse of the heading, slower than the walk / stops when S is released / can
  be steered while backing; tests/smoke/ride.spec.js › S from halt reins the horse back after a
  short pause; releasing S stops it)
- [x] Per Touch: Joystick aus dem Halt nach unten gehalten = rückwärts wie S (Regel 10).
  (Nachweis: src/adapters/input/joystick-mapping.test.js › pulling the stick fully down gives
  throttle -1 / a nearly straight down hold gives steer 0; tests/smoke/ride.spec.js › dragging
  the joystick straight down from halt reins the horse back)
- [x] Rückwärts: Space springt nicht, Hindernisse und Zaun halten auf, Start-/Ziellinie zählen
  nicht (Regel 9). (Nachweis: src/domain/sim/riding-sim.test.js › Space does not jump or hop
  while backing / the fence holds the hindquarters / an obstacle behind the horse stops it /
  rear clearance from the pole; src/domain/course/course-run.test.js › crossing the start/finish
  line backwards does not count; src/application/modes/course-mode.test.js; src/application/
  ride-session.test.js › backing over the start line does not start a course ride)
- [?] Das Pferd tritt beim Rückwärtsrichten erkennbar rückwärts (Regel 24). (Nicht hier
  prüfbar: ob es „erkennbar" aussieht, muss ein Mensch beurteilen. Vorhandener Nachweis:
  src/adapters/view3d/horse/gaits.test.js › rein-back: diagonal two-beat / stance hoof travels
  forward; motion.test.js › rein-back: diagonal footfalls, no sliding)
- [x] Joystick: volle Lenkung spätestens bei ~2/3 seitlicher Auslenkung; schräg nach vorn
  gehalten lenkt deutlich; Tastatur-Lenkung bleibt gleich direkt oder wird direkter (Regel 10).
  (Nachweis: src/adapters/input/joystick-mapping.test.js › full steering lock is reached at 2/3
  sideways deflection at the latest / holding the stick 45 degrees forward-right steers clearly /
  near-horizontal hold gives throttle 0; src/domain/sim/movement.test.js › Turn agility (Wendekreis
  Trab ≤ 3 m statt 3,3 m, Galopp ≤ 6,5 m statt 7,8 m))
- [x] Grafikstufe im Ritt (Pause → Einstellungen) wechseln: danach weiter steuerbar und spielbar,
  ohne Konsolenfehler, in jeder Richtung (Regel 4). (Nachweis: tests/smoke/graphics.spec.js ›
  after changing the level in both directions the ride stays steerable / low → high → medium →
  low by tapping: the joystick still speeds the horse up; Hinweis: auf einem echten Handy erneut
  testen, der ursprüngliche Fehler war nur dort sichtbar)
- [x] WebGL-Kontextverlust pausiert das Spiel; nach der Wiederherstellung geht es weiter (Regel 4).
  (Nachweis: tests/smoke/graphics.spec.js › the ride pauses on loss and can go on after the
  restore / a context that does not come back asks for a reload after a few seconds / a loss
  before the ride starts: the ride begins paused until the restore)
- [x] Manuelle Stufe < 30 fps über 5 s → einmal je Ritt ein Hinweis; Stufe bleibt (Regel 4).
  (Nachweis: src/adapters/view3d/quality.test.js › createLowFpsHint / canHintLowerLevel;
  tests/smoke/graphics.spec.js › a slow device at a manual level gets the hint once, the level
  stays)
- [x] Einstellungen: fps-Anzeige ein/aus (Standard aus), gespeichert; an = fps beim Reiten
  sichtbar, ~2×/s aktualisiert, verdeckt keine Bedienelemente (Regeln 4, 44). (Nachweis:
  src/application/settings-schema.test.js, settings-service.test.js › fps display;
  src/adapters/ui/fps-display.test.js; tests/smoke/graphics.spec.js › off by default; the toggle
  shows fps and level in the ride, updates live and is saved / the value updates about twice per
  second / in free mode, pre-start and course ride it covers no control)
- [x] Die fps-Anzeige zeigt zusätzlich die aktuelle Grafikstufe, bei automatisch gewählter Stufe
  mit „(Auto)" (z. B. „58 fps · Mittel (Auto)", EN „58 fps · Medium (auto)"); sie folgt einem
  Stufenwechsel (Automatik oder Einstellungen) sofort (Regel 4). (Nachweis:
  src/adapters/ui/fps-display.test.js › formatFpsText; tests/smoke/graphics.spec.js › off by
  default; the toggle shows fps and level … (Format mit „(auto)", sofortiger Wechsel nach
  manueller Stufe))

## Links
Konzept Regeln 4, 8, 9, 10, 23, 24, 44; SRT-002, SRT-003.

## Definition of Ready
- [x] Ziel klar, nutzersichtbares Ergebnis benannt
- [x] Fachlichkeit geplant; UI designt oder "kein UI"
- [x] Zuordnung zu Feature/Konzept gesetzt und auflösbar
- [x] Ziel-Plattform gesetzt
- [x] Betroffene Produkt-Plattformen benannt
- [x] Konzept verlinkt, kein Widerspruch zum dokumentierten Stand
- [x] Akzeptanzkriterien einzeln prüfbar
- [x] Abhängigkeiten benannt, passend zu depends_on
- [x] Aufwand geschätzt

## Definition of Done
- [ ] Alle Akzeptanzkriterien erfüllt, mit Nachweis
- [ ] Quality Gates grün, Review ohne blocker/major
- [ ] Konzept/Doku nachgezogen
- [ ] PR/MR gemerged
