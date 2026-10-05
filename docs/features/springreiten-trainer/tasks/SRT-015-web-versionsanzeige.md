---
id: SRT-015
title: Versionsanzeige in den Einstellungen und in der Debug-Anzeige
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p2
depends_on: [SRT-001, SRT-008]
effort: S
---
## Kontext
Betroffene Produkt-Plattformen: web.

Spieltest 2026-10-05: Auf dem Tablet waren nach dem Update noch die orange-roten Buttons zu sehen,
obwohl die neue Version online war. Grund: Die App lädt eine neue Version im Hintergrund und nutzt
sie erst beim nächsten Start (Regel 5). Ohne sichtbare Versionsangabe lässt sich nicht prüfen,
welche Version läuft.

## Ziel
Man sieht auf einen Blick, welche Spielversion läuft.

## Scope
- In: Regel 58; Versionsangabe beim Build erzeugt (Datum des Stands + kurze Kennung), angezeigt in
  den Einstellungen und in `?debug`.
- Out: Hinweis „neue Version verfügbar", Änderungen am Update-Verhalten (Regel 5).

## Design (SSoT)
Kein Design-Werkzeug. Unten in den Einstellungen eine kleine, dezente Zeile
„Version 2026-10-05 · 3fdf19e" (EN „Version …"); in der Debug-Anzeige eine Zeile „Version …".

## Akzeptanzkriterien
- [ ] Die Einstellungen zeigen unten die Version aus Datum und Kennung des Stands (Regel 58).
- [ ] Die Debug-Anzeige zeigt dieselbe Version (Regel 58).
- [ ] Die Version wird beim Build erzeugt: im GitHub-Build aus dem Commit, lokal aus dem
  Git-Stand bzw. „dev", wenn keiner vorhanden ist (Regel 58).
- [ ] Texte auf Deutsch und Englisch (Regel 6).

## Links
Konzept Regeln 5, 58; SRT-008 (Debug-Anzeige).

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
