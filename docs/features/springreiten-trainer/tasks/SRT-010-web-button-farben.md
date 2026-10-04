---
id: SRT-010
title: Button-Farben – Grün für Weiter, Rot nur für Löschen
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-001]
effort: S
---
## Kontext
Betroffene Produkt-Plattformen: web.

Spieltest 2026-10-04: „Die Farbcodierung mit roten Buttons für positive Buttons ist sehr
irritierend." Stand: Der Standard-Button ist rot-orange (alle Menü-Einträge, „Los", „Weiter",
„Nochmal"), zurückhaltende Buttons („Zurück", „Abbrechen") sind grün, „Fortschritt löschen" ist
nur ein etwas dunkleres Rot, und der Lösch-Bestätigungs-Button ist im Standard-Rot.

Recherche (Material 3, Apple HIG, WCAG 2.2): Positiv = Primärfarbe, Rot nur für zerstörende
Aktionen, zurückhaltend = neutral/umrandet; Text auf Buttons mindestens 4,5 : 1; Farbe nie als
einziges Unterscheidungsmerkmal (Rot-Grün-Schwäche).

| Rolle | Fläche | Schrift | Kontrast |
| --- | --- | --- | --- |
| Weiter (positiv) | Wiesengrün #2E7D32 | weiß | 5,1 : 1 |
| Zurückhaltend | Creme #FFF4E0 mit braunem Rahmen #6B4423 | #6B4423 | 7,8 : 1 |
| Löschen | Scheunenrot #B3261E | weiß | 6,5 : 1 |
| Auswahl aktiv | Bernstein #F2A900 | dunkelbraun | 8,1 : 1 |

## Ziel
Die Farbe eines Buttons passt zu seiner Bedeutung: Grün heißt weiter, Rot nur löschen.

## Scope
- In: Regel 57; alle Buttons in Menüs, Pause, Vorstart, Ergebnis, Einstellungen, Namensfrage,
  Hinweisen; Touch-Buttons im Ritt („Springen", „Galopp"); Farben als Design-Tokens.
- Out: Dunkler Modus; Änderungen an Texten oder Abläufen.

## Design (SSoT)
Kein Design-Werkzeug; Farben und Rollen laut Tabelle oben.

## Akzeptanzkriterien
- [ ] Alle Buttons zum Weitermachen (Hauptmenü-Einträge, „Los", „Weiter", „Nochmal", „Nächster
  Parcours", „Los geht's", „Okay") sind grün mit weißer Schrift (Regel 57).
- [ ] Zurückhaltende Buttons („Zurück", „Abbrechen", „Überspringen", „Neu starten", „Zur Auswahl",
  „Einstellungen" in der Pause, Abbruch des Ritts) sind hell mit Rahmen (Regel 57).
- [ ] Nur „Fortschritt löschen" und dessen Bestätigung „Löschen" sind rot (Regel 57).
- [ ] Kein Button-Text unterschreitet 4,5 : 1 Kontrast, geprüft per Test gegen die Farb-Tokens
  (Regel 57).
- [ ] Die Button-Arten unterscheiden sich auch ohne Farbe (Form: gefüllt/umrandet) (Regel 57).
- [ ] Touch-Button „Springen" ist nicht mehr rot; „Galopp an" bleibt klar erkennbar (Regeln 10, 57).

## Links
Konzept Regel 57; Recherche Material 3 „Color roles", Apple HIG „Buttons", WCAG 2.2 SC 1.4.3/1.4.11.

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
