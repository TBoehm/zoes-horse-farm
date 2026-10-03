---
id: SRT-004
title: Gewertete Parcours – Auswahl, Vorstart, Wertung, Ergebnis, Sterne, Freischaltung
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-003]
effort: L
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Baut auf der Sprungmechanik und den Hindernissen aus SRT-003 auf. Liefert den gewerteten Teil des
Spiels: fünf Parcours mit steigender Schwierigkeit, Parcours-Auswahl, Vorstart-Karte, Reihenfolge
mit Sprungrichtung, kindgerechte Turnier-Wertung (Abwurf, Verweigerung, Zeitfehler, kein
Ausschluss), Ergebnisanzeige, Sterne, Bestleistung und Freischaltung. Die Auszeichnungen im Ergebnis
ergänzt SRT-005; dieses Ticket speichert aber bereits den Zähler beendeter Ritte, den SRT-005 nutzt.

## Ziel
Das Kind wählt einen freigeschalteten Parcours, reitet ihn in der vorgegebenen Reihenfolge gegen die
Uhr, sieht danach Zeit, Fehler und Sterne und schaltet mit jedem beendeten Ritt den nächsten
Parcours frei; Bestleistungen und Sterne bleiben gespeichert.

## Scope
- In:
  - Hauptmenü-Eintrag „Parcours" und Parcours-Auswahl (Regeln 53, 55).
  - Die 5 Parcours mit Anzahl, Arten und Höhen laut Tabelle (Regel 25).
  - Vorstart-Karte und Vorstart-Phase (Regel 26).
  - Ritt: Start/Ziel, Zeit in Hundertstelsekunden, Reihenfolge, Hervorhebung, Sprungrichtung (Fahnen),
    falsches Hindernis, Ziel vor allen Hindernissen, Kombination (Regeln 27–31; §Begriffe
    Sprungrichtung, Vor, Ritt-Zeit).
  - Verhalten an Hindernissen ohne Wertung: nur Sprung auf Space, sonst Ausweichen ohne Fehler mit
    unveränderter Gangart (Regel 22, Teil Parcours/Vorstart).
  - Fehlerpunkte und Zeitfehler, erlaubte Zeit (Regeln 32, 33).
  - HUD während des Ritts (Regel 34).
  - Ergebnisanzeige (Regel 35, ohne den Auszeichnungs-Abschnitt), Sterne, Bestleistung,
    Freischaltung (Regeln 36, 37; §Begriffe Bestleistung).
  - Pause und Abbruch im Parcours (Regeln 38, 39, 40).
  - Absprung-Hilfe im Parcours und Schalter auf der Vorstart-Karte (Regel 42).
  - Speicherung: freigeschaltete Parcours, Bestleistung und beste Sterne je Parcours, Zähler
    beendeter Parcours-Ritte, sofort bei Rittende (Regeln 44, 45).
- Out:
  - Auszeichnungen (Vergabe, Anzeige im Ergebnis) (SRT-005).
  - Pferdename-Abfrage und -Änderung (SRT-005); das Ergebnis zeigt den gespeicherten bzw.
    Vorgabe-Namen.
  - Start- und Zielsignal als Klang (SRT-006).

## Design (SSoT)
Kein Design-Entwurf (bewusst ohne Design freigegeben).

| Bildschirm / Zustand | Inhalt |
| --- | --- |
| Parcours-Auswahl | 5 Karten: Nummer, Anzahl Hindernisse, Schloss bei gesperrt, beste Sterne (0–3), Bestleistung (Fehler, Zeit mm:ss,hh) falls vorhanden; „Zurück". Gesperrte Karten nicht wählbar. |
| Vorstart-Karte | Draufsicht-Plan des Parcours mit Nummern, Reihenfolge und Sprungrichtung, Start- und Ziellinie; erlaubte Zeit; Schalter „Absprung-Hilfe"; „Los"; „Zurück". |
| Vorstart | 3D, Pferd im Halt vor der Startlinie, Hinweis „Reite über die Startlinie". |
| Ritt-HUD | Laufende Zeit, erlaubte Zeit, Fehlerpunkte, Nummer des nächsten Hindernisses; Hervorhebung des nächsten Hindernisses in der Szene; Hinweis bei fehlendem Hindernis vor dem Ziel. |
| Ergebnis | Pferdename, Zeit, Abwürfe, Verweigerungen, Zeitfehler, Summe, Sterne, ggf. „Neue Bestleistung!", Buttons „Nochmal", „Nächster Parcours" (nur wenn offen), „Zur Auswahl". |
| Pausemenü | „Weiter", „Neu starten", „Zur Auswahl", „Einstellungen". |

## Akzeptanzkriterien
### Auswahl und Inhalt
- [ ] Im Hauptmenü erscheint „Parcours" als erster Eintrag (Position laut Regel 53); bestehende Einträge bleiben unverändert (Regeln 53, 54).
- [ ] Die Parcours-Auswahl zeigt je Parcours Nummer, Anzahl Hindernisse (eine Kombination zählt als eins), gesperrt/offen, beste Sterne und Bestleistung (Fehler und Zeit), falls vorhanden (Regel 55).
- [ ] Beim ersten Start ist nur Parcours 1 offen (Regel 37).
- [ ] Ein gesperrter Parcours lässt sich nicht öffnen oder starten (Regel 37).
- [ ] Die 5 Parcours entsprechen der Tabelle in Regel 25 (Anzahl, Arten, Höhen); eine Zweifach-Kombination kommt nur in Parcours 5 vor (Regel 25).
### Vorstart
- [ ] Nach Wahl eines Parcours erscheint die Vorstart-Karte mit Plan (Lage, Nummern, Reihenfolge, Sprungrichtung), Schalter für die Absprung-Hilfe und „Los" (Regeln 26, 28).
- [ ] Nach „Los" steht das Pferd im Halt vor der Startlinie, das Kind kann frei zur Startlinie reiten; die Zeit läuft noch nicht (Regel 26).
- [ ] Der Touch-Galopp-Umschalter steht nach „Los" auf aus (Regel 38).
- [ ] Im Vorstart springt das Pferd an Parcours-Hindernissen nur auf Space und nie selbst; ohne Sprung weicht es ohne Fehler aus und behält Gangart, Tempo und Galopp (Regel 22).
- [ ] Im Vorstart ist Hindernis 1 hervorgehoben (bei eingeschalteter Absprung-Hilfe mit Zone), dort entsteht aber keine Verweigerung (Regeln 22, 26).
- [ ] Die Ziellinie im Vorstart und die Startlinie während des Ritts bewirken nichts; beide Linien zählen nur beim Überqueren in Ritt-Richtung (Regel 26).
- [ ] Ein Sprung im Vorstart zählt nicht für die Wertung; eine dabei gefallene Stange wird nach etwa 3 Sekunden ohne Wertung wieder aufgebaut (Regel 26).
### Ritt
- [ ] Die Zeit startet beim Überqueren der Startlinie und stoppt beim Überqueren der Ziellinie, nachdem alle Hindernisse in Reihenfolge gesprungen wurden; angezeigt und gespeichert in Hundertstelsekunden (Regel 27; §Begriffe Ritt-Zeit).
- [ ] Das Hindernis, das an der Reihe ist, ist in der Szene hervorgehoben und zeigt seine Nummer; alle Parcours-Hindernisse zeigen ihre Sprungrichtung mit Fahnen (rot rechts, weiß links) (Regel 28; §Begriffe Sprungrichtung).
- [ ] Ein Abwurf am Hindernis, das an der Reihe ist, zählt 4 Fehler; das Hindernis gilt als gesprungen, die Stange bleibt bis zum Ende des Ritts liegen, die Hervorhebung geht zum nächsten (Kombination: nach Abwurf an a geht es mit b weiter; vor einem neuen Anlauf werden a und b wieder aufgebaut, Regel 31) (Regeln 27, 32).
- [ ] Jede Verweigerung am Hindernis, das an der Reihe ist, zählt 4 Fehler, auch die zweite und weitere; es gibt keinen Ausschluss (Regel 32).
- [ ] Ein Sprung auf Space kurz nach einer Verweigerung (innerhalb des Anreitabstands) über das Hindernis, das an der Reihe ist, wird normal gewertet (Regel 22).
- [ ] Ein Sprung über ein Hindernis, das nicht an der Reihe ist, oder über das richtige Hindernis gegen die Sprungrichtung bringt keine Fehler und keine Wertung; das richtige Hindernis bleibt hervorgehoben (Regel 29).
- [ ] Fällt dabei eine Stange, wird sie nach etwa 3 Sekunden wieder aufgebaut, und das Hindernis muss später regulär gesprungen werden (Regel 29).
- [ ] An Hindernissen, die nicht an der Reihe sind, und an der Rückseite des richtigen Hindernisses gibt es keine Verweigerung und kein Selbst-Springen; ohne Sprung weicht das Pferd ohne Fehler aus und behält Gangart, Tempo und Galopp (Regel 22).
- [ ] Nach dem letzten Hindernis ist kein Hindernis mehr hervorgehoben, die Ziellinie ist markiert und die Anzeige „nächstes Hindernis" zeigt „Ziel" (Regel 28).
- [ ] Wird die Ziellinie überquert, bevor alle Hindernisse gesprungen sind, läuft der Ritt weiter und ein Hinweis nennt das fehlende Hindernis (Regel 30).
- [ ] Kombination: Teil b ist erst nach einem Sprung über a an der Reihe; ein Sprung über b allein gilt wie ein falsches Hindernis (keine Wertung, keine Verweigerung, Wiederaufbau nach ca. 3 s) (Regeln 29, 31).
- [ ] Kombination: Nach einer Verweigerung an Teil a oder b springt die Hervorhebung auf Teil a zurück und die ganze Kombination muss neu angeritten werden (Regel 31).
- [ ] Kombination: Wendet das Kind nach Teil a ab, ohne b zu springen (ohne Verweigerung), springt die Hervorhebung ohne Fehler auf Teil a zurück, sobald das Pferd b nicht anreitet und weiter als den Anreitabstand von b entfernt ist (Regel 31).
- [ ] Kombination: Vor jedem neuen Anlauf werden gefallene Stangen von a und b wieder aufgebaut; jeder Abwurf an a oder b aus jedem Anlauf zählt 4 Fehler (Regel 31).
- [ ] Die erlaubte Zeit je Parcours ist die Zeit der Ideallinie bei mittlerem Galopptempo × 1,5, aufgerundet auf volle Sekunden; für Parcours 1 mit mittlerem Trabtempo statt Galopptempo (Regel 33).
- [ ] Parcours 1 ist im Trab ohne Zeitfehler schaffbar (Regel 33).
- [ ] Spieltest (dokumentiert): Eine Testperson ohne Vorwissen schafft Parcours 1 mit eingeschalteter Absprung-Hilfe in höchstens 5 Versuchen fehlerfrei (Regel 15).
- [ ] Je angefangene 4 Sekunden über der erlaubten Zeit gibt es 1 Fehlerpunkt (z. B. 0,01 s drüber = 1, 4,00 s drüber = 1, 4,01 s drüber = 2) (Regel 33).
- [ ] Während des Ritts sind laufende Zeit, erlaubte Zeit, aktuelle Fehlerpunkte und das nächste Hindernis sichtbar (Regel 34).
### Ergebnis, Sterne, Freischaltung
- [ ] Nach dem Ziel erscheint die Ergebnisanzeige mit Pferdename, Zeit, Abwürfen, Verweigerungen, Zeitfehlern, Sternen, ggf. „Neue Bestleistung" und den Buttons „Nochmal", „Nächster Parcours" (nur wenn freigeschaltet) und „Zur Auswahl" (Regel 35).
- [ ] „Nochmal" führt zur Vorstart-Karte desselben Parcours (Regel 35).
- [ ] Sterne: 0 Fehler = 3, 1–4 Fehler = 2, mehr als 4 Fehler = 1; je Parcours wird die beste je erreichte Sternzahl gespeichert und angezeigt (Regel 36).
- [ ] Bestleistung: Ein Ritt ist neue Bestleistung, wenn er weniger Fehler hat als die bisherige oder bei gleichen Fehlern eine kürzere Zeit (auf Hundertstel); der erste beendete Ritt eines Parcours ist immer eine neue Bestleistung (§Begriffe Bestleistung, Regel 35).
- [ ] Jeder beendete Ritt schaltet den nächsten Parcours frei (Regel 37).
- [ ] Freischaltung, Bestleistung, beste Sterne und der Zähler beendeter Parcours-Ritte werden sofort bei Rittende gespeichert und gelten nach Neuladen (Regeln 44, 45).
- [ ] Der Touch-Galopp-Umschalter steht nach Rittende auf aus (Regel 38).
### Pause und Abbruch
- [ ] Pause (Esc/Button, Fokusverlust, Hochformat im Touch-Modus) hält im Vorstart und im Ritt Pferd und Zeit an; Pausemenü mit „Weiter", „Neu starten", „Zur Auswahl", „Einstellungen" (Regeln 12, 38).
- [ ] „Neu starten" führt in den Vorstart desselben Parcours: Pferd im Halt vor der Startlinie, alle Stangen aufgebaut, Zeit und Fehler zurückgesetzt; der Touch-Galopp-Umschalter steht auf aus (Regeln 26, 27, 38, 39).
- [ ] Ein abgebrochener Ritt („Neu starten" oder „Zur Auswahl" vor dem Ziel) bringt keine Sterne, keine Bestleistung, keine Freischaltung und erhöht den Zähler beendeter Ritte nicht (Regel 40).
- [ ] Sprünge aus Vorstart und abgebrochenen Ritten erhöhen trotzdem den Sprungzähler (Regel 40).
### Absprung-Hilfe
- [ ] Ist die Absprung-Hilfe im Parcours an, ist die Absprungzone vor dem Hindernis markiert, das an der Reihe ist (bei der Kombination vor dem Teil, der an der Reihe ist: zuerst a, nach dem Sprung über a dann b) (Regeln 31, 42).
- [ ] Der Schalter auf der Vorstart-Karte ändert die gespeicherte Einstellung „Absprung-Hilfe im Parcours" (dieselbe wie in den Einstellungen) (Regel 42).
- [ ] Die Absprung-Hilfe hat keinen Einfluss auf Fehler, Sterne oder Bestleistung (Regel 42).
### Sprache und Bedienung
- [ ] Alle neuen Texte liegen auf Deutsch und Englisch vor (Regel 6); alle neuen Buttons sind im Touch-Modus mindestens 44×44 px (Regel 10).
### Tests
- [ ] Unit-Tests decken ab: Fehlerpunkte, Zeitfehler-Rundung, Sterne, Bestleistungsvergleich, Freischaltung, Reihenfolge/falsches Hindernis/Sprungrichtung, Kombinations-Regeln, Abbruch ohne Wertung.

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (Regeln 6, 10; §Begriffe Parcours, Ritt, Vorstart, Sprungrichtung, Vor, Bestleistung, Ritt-Zeit; Regeln 12, 22, 25–40, 42, 44, 45, 53–55; §Grenzfälle „Abbruch", „Ziellinie vor allen Hindernissen", „Falsches Hindernis", „Verweigerung in der Kombination", „Abwenden zwischen a und b", „gegen die Sprungrichtung", „Abwurf im Vorstart", „Sehr viele Verweigerungen")
- Voraussetzung: SRT-003
- Folge: SRT-005 (Auszeichnungen im Ergebnis), SRT-006 (Start-/Zielsignal)

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
