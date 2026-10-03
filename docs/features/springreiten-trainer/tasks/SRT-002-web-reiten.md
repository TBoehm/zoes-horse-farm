---
id: SRT-002
title: Reitplatz, Pferd und Reiten – Szene, Gangarten, Steuerung, Kamera, Grafikstufen
status: ready-for-dev
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
- [ ] Im Hauptmenü erscheint „Freier Modus" an seiner Position laut Regel 53 (vor „Einstellungen"); er ist ab dem ersten Start verfügbar und öffnet den Reitplatz mit Pferd und Reiter; bestehende Einträge bleiben unverändert (Regeln 41 Verfügbarkeit, 53, 54).
- [ ] Beim Betreten des freien Modus steht das Pferd am Startpunkt im Halt, der Touch-Galopp-Umschalter steht auf aus (Regel 39).
- [ ] Reitplatz (Sandboden, Umzäunung), Umgebung, Pferd und Reiter entstehen ohne geladene Bild-, Audio-, Modell- oder Schriftdateien (Regel 2).
- [ ] Das Pferd hat beim ersten Start das Aussehen Brauner mit Stern (Regel 43, Vorgabe).
- [ ] A/D lenken links/rechts; W erhöht und S verringert das Tempo stufenlos, solange gedrückt (Regel 8).
- [ ] Auch im Halt wenden A/D bzw. der Joystick das Pferd auf der Stelle (Regel 22).
- [ ] Ohne Galopp liegt das Tempo stufenlos zwischen Halt, Schritt und Trab; die Gangart des Pferdes (erkennbar an der Bewegung, ohne eigene Anzeige) wechselt passend zum Tempo (Regel 9).
- [ ] Solange Shift gehalten wird, galoppiert das Pferd und W/S regeln das Galopptempo stufenlos; nach Loslassen fällt es in den Trab (Regel 9).
- [ ] Halt, Schritt, Trab und Galopp sind am Bewegungsablauf des Pferdes erkennbar unterscheidbar (Regel 24).
- [ ] Das Pferd kann den Reitplatz nicht verlassen. Frontal auf die Umzäunung: Es stoppt und ist im Halt, Galopp ist aus (auch der Touch-Umschalter; Tastatur: Shift neu drücken). Schräg auf die Umzäunung: Es gleitet mit unverändertem Tempo daran entlang (Regeln 9, 24).
- [ ] Im Touch-Modus sind Joystick, „Galopp", „Springen", „Pause" und „Kamera" sichtbar, jeweils mindestens 44×44 px; ohne Touch-Modus sind sie ausgeblendet (Regeln 10, 11).
- [ ] Joystick hoch/runter ändert das Tempo, solange ausgelenkt; stärkere Auslenkung ändert es schneller (Regel 10).
- [ ] Joystick links/rechts lenkt stufenlos; stärkere Auslenkung ergibt eine engere Kurve (Regel 10).
- [ ] „Galopp" schaltet per Tippen Galopp an bzw. aus und zeigt sichtbar, ob Galopp an ist; bei „Aus" fällt das Pferd in den Trab (Regeln 9, 10).
- [ ] Wechselt auf einem Gerät mit Tastatur und Touchscreen der Touch-Modus (an oder aus), endet ein aktiver Galopp (Trab) und der Touch-Umschalter steht auf aus (Regeln 9, 11).
- [ ] Die Standard-Kamera läuft schräg hinter und über Pferd und Reiter mit (Regel 13).
- [ ] C bzw. der Kamera-Button schaltet zwischen Standard-Kamera und Reiter-Sicht zwischen den Pferdeohren um; die zuletzt gewählte Kamera gilt nach Neuladen wieder; in den Einstellungen gibt es keine Kamera-Auswahl (Regeln 14, 44).
- [ ] Beim ersten Start ist „Automatisch" aktiv und eine zum Gerät passende Stufe gewählt (Regel 4).
- [ ] Bei aktivem „Automatisch": Liegt die durchschnittliche Bildrate 5 Sekunden lang unter 50 fps, sinkt die Stufe um eins (nie unter Niedrig); danach vergehen mindestens 10 Sekunden bis zur nächsten Anpassung; die Automatik stuft nie hoch (Regel 4).
- [ ] Die Automatik misst nur während des Reitens; Pause, Menüs, ein versteckter Tab oder ein minimiertes Fenster sowie die ersten 3 Sekunden danach senken die Stufe nicht (Regel 4).
- [ ] Eine automatisch gesenkte Stufe gilt nach Neuladen weiter (Regeln 4, 44).
- [ ] Wählt das Kind eine Stufe manuell, gilt sie und die Automatik ist aus; wählt es wieder „Automatisch", wird neu passend zum Gerät gewählt (Regel 4).
- [ ] Auf der automatisch gewählten Stufe erreicht der Reitplatz auf einem aktuellen PC und einem aktuellen Tablet beim Reiten über 30 Sekunden im Mittel mindestens 50 fps (Ziel 60 fps); Stufe „Niedrig" ist sichtbar einfacher als „Hoch" (Regel 3).
- [ ] Der Reitplatz läuft in aktuellen Versionen von Chrome, Safari (inkl. iPad/iPhone), Firefox und Edge (Regel 3).
- [ ] Esc bzw. der Pause-Button pausiert: Pferd und Szene stehen still, das Pausemenü erscheint mit „Weiter", „Neu starten", „Zum Menü", „Einstellungen" (Regel 38).
- [ ] Bei Tab-/App-Wechsel, minimiertem Fenster oder Drehen ins Hochformat (im Touch-Modus) pausiert das Spiel automatisch und läuft erst mit „Weiter" weiter (Regeln 12, 38).
- [ ] Nach „Weiter" läuft das Pferd mit vorherigem Tempo und vorheriger Gangart weiter; Tastatur-Galopp bleibt nur, wenn Shift noch gehalten wird; der Touch-Galopp-Umschalter behält seinen Zustand (Regel 38).
- [ ] „Neu starten" im freien Modus setzt das Pferd im Halt an den Startpunkt; der Touch-Galopp-Umschalter steht danach auf aus (Regeln 38, 39).
- [ ] „Zum Menü" führt zurück ins Hauptmenü (Regel 38).
- [ ] Die Einstellungen sind auch aus dem Pausemenü erreichbar und enthalten die Grafikstufe (Regeln 4, 38).
- [ ] Alle neuen Texte (Pausemenü, Touch-Buttons, Grafik-Einstellung) liegen auf Deutsch und Englisch vor (Regel 6).
- [ ] Fehlt ein gespeicherter Wert für Grafikstufe oder Kamera oder ist er ungültig (z. B. Spielstand aus der Version von SRT-001), gelten „Automatisch" bzw. die Standard-Kamera, ohne Absturz (Regel 47).
- [ ] Nach dem ersten vollständigen Laden startet der freie Modus auch offline (Regel 5).
- [ ] Der Browser-Smoke-Test aus SRT-001 prüft zusätzlich, dass der freie Modus startet und die 3D-Szene ohne Konsolenfehler rendert.

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
- [ ] Alle Akzeptanzkriterien erfüllt, mit Nachweis
- [ ] Quality Gates grün, Review ohne blocker/major
- [ ] Konzept/Doku nachgezogen
- [ ] PR/MR gemerged
