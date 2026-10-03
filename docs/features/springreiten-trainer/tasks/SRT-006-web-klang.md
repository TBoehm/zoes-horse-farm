---
id: SRT-006
title: Klang – synthetisierte Effekte und Menü-Melodie
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p2
depends_on: [SRT-002, SRT-004, SRT-005]
effort: S
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Ergänzt den Ton. Alle Klänge entstehen zur Laufzeit im Spiel (keine Audiodateien). Hufschlag braucht
die Gangarten aus SRT-002, Absprung/Landung/fallende Stange die Sprungmechanik aus SRT-003, Start-
und Zielsignal sowie Vorstart-Karte und Ergebnisanzeige den Parcours aus SRT-004.

## Ziel
Das Spiel klingt lebendig: Hufschlag im Rhythmus der Gangart, hörbare Sprünge und fallende Stangen,
Start- und Zielsignal im Parcours und eine einfache Melodie in den Menüs, alles getrennt regelbar
und stumm schaltbar.

## Scope
- In:
  - Effekte: Hufschlag je Gangart, Absprung, Landung, fallende Stange, Startsignal bei „Los",
    Zielsignal (Regel 51).
  - Menü-Melodie in Hauptmenü und Untermenüs, auf der Vorstart-Karte und in der Ergebnisanzeige;
    keine Musik im Vorstart, im Ritt, im freien Modus und im Pausemenü (Regel 51).
  - Einstellungen: Lautstärke Musik und Effekte getrennt, jeweils stumm schaltbar, gespeichert
    (Regeln 44, 45, 52).
  - Ton erst nach der ersten Interaktion (Regel 52).
- Out:
  - Klänge für Verweigerung, Hopser, Auszeichnungen, Buttons (nicht im Konzept gefordert).
  - Musik während des Reitens (Konzept: keine).

## Design (SSoT)
Kein UI außer zwei Einstellungen: „Musik" und „Effekte", je ein Lautstärkeregler mit Stumm-Schalter
(im Touch-Modus ≥ 44×44 px).

## Akzeptanzkriterien
- [ ] Beim Laden der App wird keine Audiodatei angefragt; alle Klänge entstehen zur Laufzeit (Regel 2).
- [ ] Vor der ersten Interaktion (Klick, Berührung, Taste) ist kein Ton zu hören; danach startet der Ton ohne weiteren Schritt (Regel 52).
- [ ] Der Hufschlag ist in Schritt, Trab und Galopp hörbar unterschiedlich (Rhythmus/Tempo) und folgt dem Gangartwechsel; im Halt ist kein Hufschlag zu hören (Regel 51).
- [ ] Absprung und Landung sind jeweils hörbar (Regel 51).
- [ ] Eine fallende Stange ist hörbar (Regel 51).
- [ ] Bei „Los" auf der Vorstart-Karte ertönt das Startsignal (Regeln 26, 51).
- [ ] Beim Überqueren der Ziellinie am Ende eines Ritts ertönt das Zielsignal; wird die Ziellinie überquert, bevor alle Hindernisse gesprungen sind, ertönt kein Zielsignal (Regeln 30, 51).
- [ ] Die Melodie läuft in Hauptmenü, Untermenüs (Parcours-Auswahl, Mein Pferd, Auszeichnungen, Einstellungen aus dem Hauptmenü), auf der Vorstart-Karte und in der Ergebnisanzeige (Regel 51).
- [ ] Im Vorstart, während eines Ritts, im freien Modus und im Pausemenü läuft keine Musik, auch nicht in den Einstellungen, wenn sie aus dem Pausemenü geöffnet wurden (Regel 51).
- [ ] Ist die App im Hintergrund (Tab gewechselt, Fenster minimiert), ist kein Ton zu hören; zurück im Vordergrund läuft die Musik dort weiter, wo sie laufen soll (Regel 51).
- [ ] „Neu starten" aus dem Pausemenü löst kein Startsignal aus; „Nochmal" führt zur Vorstart-Karte, das Signal ertönt dort erst bei „Los" (Regeln 26, 35).
- [ ] Beim ersten Start sind Musik und Effekte an, mit mittlerer Lautstärke (Regel 52).
- [ ] In den Einstellungen lassen sich Musik und Effekte getrennt in der Lautstärke regeln und je Kanal mit eigenem Schalter stumm schalten; nach dem Aufheben von „Stumm" gilt die vorher eingestellte Lautstärke; Lautstärken und Stumm-Zustände gelten sofort und nach Neuladen (Regeln 44, 45, 52).
- [ ] Lautstärke und Stumm je Kanal bleiben nach „Fortschritt löschen" erhalten (Regel 48).
- [ ] Ist ein Kanal stumm, sind dessen Klänge nicht zu hören; der andere Kanal bleibt unverändert (Regel 52).
- [ ] Während der Pause sind keine Effekte zu hören (Regel 38: Szene steht).

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (Regeln 2, 26, 30, 35, 38, 44, 45, 48, 51, 52)
- Voraussetzungen: SRT-002 (Gangarten), SRT-004 (Vorstart, Ziel, Ergebnis; enthält SRT-003), SRT-005 (Untermenüs Mein Pferd, Auszeichnungen)

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
