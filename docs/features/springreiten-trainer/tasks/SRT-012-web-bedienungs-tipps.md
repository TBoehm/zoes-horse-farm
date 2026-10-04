---
id: SRT-012
title: Bedienungs-Tipps – beim ersten Start und jederzeit wieder aufrufbar
status: in-review
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
- In: Regel 56, Regel 53 (neuer Menü-Eintrag), Regel 44 (gespeichert), Pausemenü-Button.
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
- [x] Beim ersten Start erscheinen die Bedienungs-Tipps nach der Namensfrage und vor dem
  Hauptmenü (Regel 56). (Nachweis: src/application/start-flow.test.js › first start: name
  question, controls help, then the menu; tests/smoke/help.spec.js › name question, then the
  controls help, then the menu; not again after a reload)
- [x] Ein bestehender Spielstand, der die Tipps noch nie geschlossen hat, sieht sie beim nächsten
  Start einmal vor dem Hauptmenü (Regel 56). (Nachweis: start-flow.test.js › existing save that
  never closed the help: help once, then the menu; help.spec.js › an existing save that never
  closed the help sees it once before the menu)
- [x] Nach „Verstanden" ist das gespeichert; beim nächsten Start kommen sie nicht mehr von selbst
  (Regeln 44, 56). (Nachweis: settings-service-Tests zu `markControlsHelpSeen`; help.spec.js ›
  … not again after a reload)
- [x] Vorgewählt ist die aktuelle Eingabeart; der Umschalter zeigt die andere (Regel 56).
  (Nachweis: help.spec.js › desktop: key caps first, the switch shows the touch controls and back
  / touch device › starts on the touch controls)
- [x] Tastatur: alle Tasten aus Regel 8 als Tastenkappen mit kurzem Text; Touch: Joystick,
  Galopp, Springen, Kamera, Pause (Regel 56). (Nachweis: src/adapters/ui/screens/help-content.test.js;
  help.spec.js › desktop: key caps first …)
- [x] „Bedienungs-Tipps" im Hauptmenü öffnet die Übersicht, „Verstanden" führt zurück ins Menü
  (Regeln 53, 56). (Nachweis: help.spec.js › main menu entry opens the help, "Got it" leads back
  to the menu)
- [x] „Bedienungs-Tipps" im Pausemenü öffnet die Übersicht, „Verstanden" führt zurück in die
  Pause; der Ritt läuft nicht weiter (Regel 56). (Nachweis: help.spec.js › pause menu: the help
  opens on top, "Got it" returns to the open pause menu)
- [x] Alle Texte auf Deutsch und Englisch, kurz (Regel 6). (Nachweis: src/adapters/ui/i18n/help.js,
  strings.test.js prüft gleiche Schlüssel DE/EN; help.spec.js › shows the texts of the chosen
  language …)
- [x] Bedienbar mit Tastatur (Fokus, Enter) und Touch (Ziele ≥ 44 px) (Regel 10). (Nachweis:
  help.spec.js › every control is at least 44 px and the help fits a low phone screen; Fokus auf
  dem Umschalter, Enter löst aus)

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
