---
id: SRT-014
title: Automatik startet auf „Niedrig" und stuft dynamisch hoch
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-008, SRT-013]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (alle Geräte).

Spieltest 2026-10-04 nach dem Absturz auf „Mittel" (SRT-013): „Macht es Sinn, erstmal im niedrigen
Modus zu starten und nur wenn genug Ressourcen verfügbar sind hochzuschalten?" – Entscheid: ja,
dynamisch während des Spiels, nicht nur zwischen Ritten.

Stand: Die Automatik wählt beim ersten Start eine Stufe nach Gerät (Grafikchip, Speicher-Grenze) und
stuft nur herunter. Den wirklich freien Grafikspeicher verrät der Browser nicht; er lässt sich nur
schätzen (SRT-008). Sicherheit beim Hochstufen geben darum: Bildraten-Reserve, die Speicher-Grenze,
kleine Schritte und gesperrte Stufen nach einem Absturz oder Kontextverlust (SRT-013).

## Ziel
Jedes Gerät startet sicher auf „Niedrig" und erreicht von selbst die höchste Stufe, die es flüssig
und ohne Absturz schafft.

## Scope
- In: Regel 4 (Start bei Niedrig, dynamisches Hochstufen, gesperrte Stufen, Pingpong-Schutz),
  Anzeige der Stufe in der fps-Anzeige und `?debug`.
- Out: Hochstufen bei manuell gewählter Stufe; neue Grafikstufen.

## Design (SSoT)
Kein neues UI. Die fps-Anzeige zeigt wie bisher die aktuelle Stufe mit „(Auto)"; `?debug` zeigt
zusätzlich gesperrte Stufen und den Grund des letzten Wechsels.

## Akzeptanzkriterien
- [x] Erster Start (und neues Wählen von „Automatisch"): Stufe Niedrig (Regel 4). (Nachweis:
  tests/smoke/graphics-upgrade.spec.js › a first start without a saved level is automatic at low /
  selecting „Automatic" …; settings-service.test.js)
- [x] Bei ≥ 57 fps im Mittel über 10 s mit kaum langsamen Bildern stuft das Spiel in kleinen
  Schritten hoch, mit mindestens 20 s Abstand nach jeder Anpassung; kein Schritt beginnt in einem
  Sprung (Regel 4). (Nachweis: src/adapters/view3d/quality-upgrade.test.js › 57 fps, 2 % slow
  frames, warm-up, 20 s cooldown, never starts a step while busy; ride-session.test.js (jumping,
  approaching); graphics-upgrade.spec.js › fast frames: low → medium … / slow frames: it stays at low)
- [x] Das Hochstufen ist in kleine Schritte geteilt und belegt nie mehr Grafikspeicher als die Ziel-
  stufe selbst (keine doppelten Programme oder Objekte während des Wechsels) (Regel 4). (Nachweis:
  world-stages.test.js › steps up without ever holding more than the target level needs / survives
  a round trip high → low → high …)
- [x] Nie auf eine gesperrte Stufe (Kontextverlust oder Absturz auf diesem Gerät, gespeichert), nie
  über die Speicher-Grenze, nie zurück auf eine Stufe, von der im laufenden Spiel wegen Ruckelns
  heruntergestuft wurde (Regel 4). (Nachweis: quality-upgrade.test.js › never goes to a blocked
  level … / … left because of a low frame rate … / … does not fit the memory budget …;
  graphics-upgrade.spec.js › a blocked level (crash guard) is not climbed to / a lost WebGL context
  blocks the level … / a level left in this session … is not climbed back to / a level that does not
  fit the memory budget …)
- [x] Die erreichte Stufe gilt beim nächsten Start (Regel 4). (Nachweis: graphics-upgrade.spec.js ›
  a saved automatic level applies at the next start / fast frames: … saved)
- [x] Manuelle Stufe: keine Automatik, kein Hochstufen (Regel 4). (Nachweis: graphics-upgrade.spec.js
  › a manually chosen level is never raised)
- [x] Die fps-Anzeige folgt jedem Wechsel sofort; `?debug` zeigt gesperrte Stufen (Regel 4). (Nachweis:
  debug-display.test.js; graphics-upgrade.spec.js › fast frames: … the debug box says why;
  tests/smoke/graphics.spec.js (fps-Anzeige folgt dem Stufenwechsel))
- [?] Auf dem Tablet pendelt sich die Automatik stabil ein (Nachweis nur am Gerät).

## Links
Konzept Regel 4; SRT-008, SRT-013.

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
