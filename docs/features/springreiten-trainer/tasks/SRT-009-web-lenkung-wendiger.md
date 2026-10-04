---
id: SRT-009
title: Lenkung wendiger – Kurven etwa 50 % enger, schneller einlenken
status: in-progress
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-007]
effort: S
---
## Kontext
Betroffene Produkt-Plattformen: web (Touch und Tastatur).

Spieltest 2026-10-04 (Kind, Tablet): „Kurvenkriegen ist viel zu schwierig. Das Pferd sollte etwa
50 % besser die Kurve nehmen, eher einlenken, besser zu steuern sein. Das würde mehr Spaß machen."
Stand nach SRT-007: Wendekreis Trab ≈ 2,7 m, Galopp ≈ 6,3 m, volle Lenkung bei 60 % Auslenkung.

## Ziel
Das Pferd lässt sich spürbar leichter lenken: engere Kurven und schnelleres Einlenken.

## Scope
- In: Regel 10 (Wendigkeit), Lenkwerte in den Spielwerten, Kamera und Neigung passend dazu.
- Out: Änderungen an Sprung- und Wertungsregeln.

## Design (SSoT)
Kein UI.

## Akzeptanzkriterien
- [ ] Wendekreis in Trab und Galopp etwa 1/1,5 des Stands nach SRT-007 (Trab ≈ 1,8 m, Galopp
  ≈ 4,2 m) (Regel 10).
- [ ] Das Pferd lenkt schneller ein (Lenkrate erreicht das Ziel deutlich schneller als bisher) und
  pendelt nicht (Regel 10).
- [ ] Geradeaus mit leicht wackelndem Daumen bleibt das Pferd geradeaus (Totzone bleibt) (Regel 10).
- [ ] Kamera und Neigung des Pferdes bleiben in engen Kurven ruhig und plausibel.
- [ ] Anreiten, Springen, Verweigern und Ausweichen funktionieren weiter (Regeln 15–22).
- [?] Das Kind findet das Lenken jetzt gut (Nachweis nur im Spieltest).

## Links
Konzept Regel 10; SRT-007.

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
