---
id: SRT-012
title: Bedienungs-Tipps – beim ersten Start und jederzeit wieder aufrufbar
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-001, SRT-007]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (Tastatur und Touch).

Spieltest 2026-10-04: „Am Anfang des Spiels, insbesondere für Desktop, braucht es eine Erklärung,
welche Tasten was tun. Soll beim ersten Start immer angezeigt werden und mit einem
Bedienungs-Tipp-Button auch danach noch öffnebar sein." Stand: Es gibt nur den Hinweis
„Esc = Pause" im Ritt.

Recherche (Game Accessibility Guidelines „allow a reminder of controls during gameplay",
„instructions replayable"; Android-Spiele-Leitfaden: nur die Bedienung des aktuellen Geräts zeigen):
einmal beim ersten Start, schließbar, aus Menü und Pause wieder aufrufbar, Tasten als
Tastenkappen, für Kinder kurz und mit Symbolen.

## Ziel
Jedes Kind weiß von Anfang an, wie es das Pferd steuert, und kann es jederzeit nachschauen.

## Scope
- In: Regel 56, Regel 53 (neuer Menü-Eintrag), Regel 44 (gespeichert), Pausenmenü-Button.
- Out: interaktives Tutorial; Änderungen der Bedienung selbst.

## Design (SSoT)
Kein Design-Werkzeug. Ein Bildschirm „Bedienungs-Tipps" im Stil der bestehenden Karten:
Überschrift, Umschalter „Tastatur | Touch" (aktuelle Eingabeart vorgewählt), eine Liste mit Zeilen
„Tastenkappe(n) – kurzer Text" bzw. „Symbol – kurzer Text", unten „Verstanden" (grün).

| Tastatur | Text (DE) |
| --- | --- |
| W / ↑ | schneller |
| S / ↓ | langsamer, im Stand rückwärts |
| A D / ← → | lenken |
| Shift (halten) | Galopp |
| Leertaste | springen |
| C | Kamera wechseln |
| Esc | Pause |

| Touch | Text (DE) |
| --- | --- |
| Joystick hoch / runter | schneller / langsamer, im Stand rückwärts |
| Joystick links / rechts | lenken |
| Galopp-Button | Galopp an / aus |
| Springen-Button | springen |
| 🎥 / ❚❚ | Kamera / Pause |

## Akzeptanzkriterien
- [ ] Beim ersten Start erscheinen die Bedienungs-Tipps nach der Namensfrage und vor dem
  Hauptmenü (Regel 56).
- [ ] Ein bestehender Spielstand, der die Tipps noch nie geschlossen hat, sieht sie beim nächsten
  Start einmal vor dem Hauptmenü (Regel 56).
- [ ] Nach „Verstanden" ist das gespeichert; beim nächsten Start kommen sie nicht mehr von selbst
  (Regeln 44, 56).
- [ ] Vorgewählt ist die aktuelle Eingabeart; der Umschalter zeigt die andere (Regel 56).
- [ ] Tastatur: alle Tasten aus Regel 8 als Tastenkappen mit kurzem Text; Touch: Joystick,
  Galopp, Springen, Kamera, Pause (Regel 56).
- [ ] „Bedienungs-Tipps" im Hauptmenü öffnet die Übersicht, „Verstanden" führt zurück ins Menü
  (Regeln 53, 56).
- [ ] „Bedienungs-Tipps" im Pausenmenü öffnet die Übersicht, „Verstanden" führt zurück in die
  Pause; der Ritt läuft nicht weiter (Regel 56).
- [ ] Alle Texte auf Deutsch und Englisch, kurz (Regel 6).
- [ ] Bedienbar mit Tastatur (Fokus, Enter) und Touch (Ziele ≥ 44 px) (Regel 10).

## Links
Konzept Regeln 8, 10, 44, 53, 56; SRT-010 (Button-Farben).

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
