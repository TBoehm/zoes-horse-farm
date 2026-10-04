---
id: SRT-007
title: Spieltest-Feedback – Rückwärtsrichten, Lenkung, Grafikwechsel, fps-Anzeige
status: in-progress
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
- [ ] Bei einer gefallenen Stange erscheint „Stange gefallen!" (EN „Pole down!"); Ergebnis und
  Auszeichnungen sprechen ebenso von gefallenen Stangen (Regel 23).
- [ ] Im Halt mit gehaltenem S geht das Pferd nach < 0,5 s langsam rückwärts (langsamer als
  Schritt), lenkbar; Loslassen hält an; W/Galopp beenden es (Regel 9).
- [ ] Per Touch: Joystick aus dem Halt nach unten gehalten = rückwärts wie S (Regel 10).
- [ ] Rückwärts: Space springt nicht, Hindernisse und Zaun halten auf, Start-/Ziellinie zählen
  nicht (Regel 9).
- [ ] Das Pferd tritt beim Rückwärtsrichten erkennbar rückwärts (Regel 24).
- [ ] Joystick: volle Lenkung spätestens bei ~2/3 seitlicher Auslenkung; schräg nach vorn
  gehalten lenkt deutlich; Tastatur-Lenkung bleibt gleich direkt oder wird direkter (Regel 10).
- [ ] Grafikstufe im Ritt (Pause → Einstellungen) wechseln: danach weiter steuerbar und spielbar,
  ohne Konsolenfehler, in jeder Richtung (Regel 4).
- [ ] WebGL-Kontextverlust pausiert das Spiel; nach der Wiederherstellung geht es weiter (Regel 4).
- [ ] Manuelle Stufe < 30 fps über 5 s → einmal je Ritt ein Hinweis; Stufe bleibt (Regel 4).
- [ ] Einstellungen: fps-Anzeige ein/aus (Standard aus), gespeichert; an = fps beim Reiten
  sichtbar, ~2×/s aktualisiert, verdeckt keine Bedienelemente (Regeln 4, 44).
- [ ] Die fps-Anzeige zeigt zusätzlich die aktuelle Grafikstufe, bei automatisch gewählter Stufe
  mit „(Auto)" (z. B. „58 fps · Mittel (Auto)", EN „58 fps · Medium (auto)"); sie folgt einem
  Stufenwechsel (Automatik oder Einstellungen) sofort (Regel 4).

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
