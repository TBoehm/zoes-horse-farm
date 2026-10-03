---
id: SRT-005
title: Mein Pferd, Auszeichnungen und Fortschritt löschen
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p2
depends_on: [SRT-001, SRT-002, SRT-004]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Ergänzt die Motivations- und Personalisierungs-Schicht: Das Kind benennt sein Pferd beim ersten
Start und kann Fellfarbe und Kopfabzeichen wählen; acht Auszeichnungen belohnen Meilensteine; in den
Einstellungen lässt sich der Spielfortschritt zurücksetzen. Nutzt den Sprungzähler (SRT-003, über
SRT-004 verfügbar), den Zähler beendeter Ritte, Sterne und Freischaltung (SRT-004) sowie die
prozedurale Pferde-Darstellung (SRT-002).

## Ziel
Das Kind reitet „sein" Pferd mit eigenem Namen und Aussehen, bekommt für Meilensteine Auszeichnungen,
die es in einer Übersicht sammelt, und kann seinen Fortschritt bei Bedarf neu beginnen.

## Scope
- In:
  - Namensabfrage beim ersten Start, überspringbar mit Vorgabe „Blitz"/„Flash" (Regel 43).
  - Hauptmenü-Eintrag „Mein Pferd": Name (1–16 Zeichen), Fellfarbe (Fuchs, Brauner, Rappe, Schimmel,
    Schecke), Kopfabzeichen (keins, Stern, Blesse, Schnippe), Vorschau, gilt in allen Modi (Regeln 43,
    53).
  - Pferdename im Hauptmenü (Regel 43).
  - Acht Auszeichnungen mit Bedingungen und Vergabezeitpunkt, Einblendung bzw. Anzeige im Ergebnis
    (Regeln 35 Auszeichnungs-Teil, 49).
  - Hauptmenü-Eintrag „Auszeichnungen" mit Übersicht (Regeln 50, 53).
  - „Fortschritt löschen" in den Einstellungen (Regel 48).
  - Speicherung von Pferd und Auszeichnungen mit Datum, sofort (Regeln 44, 45).
- Out:
  - Anpassung des Reiters (Nicht-Ziel).
  - Pferde mit Eigenschaften, mehrere Pferde (Nicht-Ziel).
  - Klang bei Auszeichnungen (nicht im Konzept gefordert).
  - Festlegung englischer Auszeichnungsnamen im Konzept: die Umsetzung übersetzt kindgerecht.

## Design (SSoT)
Kein Design-Entwurf (bewusst ohne Design freigegeben).

| Bildschirm / Zustand | Inhalt |
| --- | --- |
| Erster Start: Pferdename | Kurzer Text „Wie heißt dein Pferd?", Eingabefeld (max. 16 Zeichen), „Los geht's" (nur aktiv bei 1–16 Zeichen), „Überspringen". Erscheint beim ersten Start vor dem Hauptmenü (ggf. nach dem Hinweis „Speichern nicht möglich“). |
| Hauptmenü | ergänzt um den Pferdenamen (z. B. „Blitz wartet auf dich!") und die Einträge „Mein Pferd" und „Auszeichnungen". |
| Mein Pferd | 3D-Vorschau des Pferdes, Namensfeld, Auswahl Fellfarbe (5), Kopfabzeichen (4), „Zurück". Änderungen sofort in der Vorschau sichtbar und gespeichert. |
| Auszeichnungen | Raster mit 8 Abzeichen-Karten: erhalten = farbig mit Datum; fehlend = grau mit Bedingung. |
| Einblendung „sofort" | Kleine Karte am Bildrand („Auszeichnung: Erster Sprung!"), ca. 3 s, unterbricht das Spiel nicht. |
| Ergebnis | ergänzt um Abschnitt „Neue Auszeichnungen" (nur wenn welche erhalten). |
| Fortschritt löschen | Button in den Einstellungen (nur aus dem Hauptmenü geöffnet) → Sicherheitsabfrage „Wirklich alles löschen? Parcours, Sterne und Auszeichnungen gehen verloren." mit „Löschen" / „Abbrechen". |

## Akzeptanzkriterien
### Pferd
- [ ] Beim ersten Start fragt das Spiel nach dem Pferdenamen, bevor das Hauptmenü erscheint; die Frage erscheint bei jedem Start erneut, bis sie beantwortet oder übersprungen wurde (auch nach Schließen der App während der Frage und bei Spielständen früherer Versionen ohne Namen) (Regel 43).
- [ ] Die Namensfrage ist überspringbar; dann heißt das Pferd „Blitz" (Deutsch) bzw. „Flash" (Englisch); dieser Vorgabe-Name wechselt beim Sprachwechsel mit, solange kein eigener Name vergeben ist (Regel 43).
- [ ] Leerzeichen am Anfang und Ende werden entfernt; danach muss der Name 1 bis 16 Zeichen lang sein. In der Namensfrage lässt sich eine ungültige Eingabe nicht übernehmen; in „Mein Pferd" wird eine ungültige Eingabe nicht gespeichert und es gilt der letzte gültige Name (Regel 43).
- [ ] Nach Beantworten oder Überspringen erscheint die Namensfrage bei späteren Starts nicht mehr (Regel 43).
- [ ] Im Hauptmenü erscheinen „Mein Pferd" und „Auszeichnungen" an ihren Positionen laut Regel 53 (nach „Freier Modus", vor „Einstellungen"); die bestehenden Einträge bleiben unverändert; damit enthält das Menü alle Einträge aus Regel 53 (Regeln 53, 54).
- [ ] In „Mein Pferd" lassen sich Name, Fellfarbe (Fuchs, Brauner, Rappe, Schimmel, Schecke) und Kopfabzeichen (keins, Stern, Blesse, Schnippe) ändern; jede Fellfarbe und jedes Kopfabzeichen ist am 3D-Pferd erkennbar; beim Schimmel genügt eine realistische Darstellung, auch wenn das Kopfabzeichen kaum sichtbar ist (Regel 43).
- [ ] Das gewählte Aussehen gilt im freien Modus und im Parcours (Regel 43).
- [ ] Der Pferdename erscheint im Hauptmenü und in der Ergebnisanzeige (Regel 43).
- [ ] Name und Aussehen sind nach Neuladen erhalten, ohne Speichern-Button (Regeln 44, 45).
- [ ] Fellfarben und Kopfabzeichen entstehen ohne geladene Bilddateien (Regel 2).
### Auszeichnungen
- [ ] „Erster Sprung" wird sofort vergeben, sobald mindestens 1 Sprung gezählt ist (Regeln 40, 49).
- [ ] „Springmaus" wird sofort vergeben, sobald mindestens 100 Sprünge gezählt sind (Kombination: jeder Teil zählt) (Regeln 40, 49).
- [ ] Ist eine Bedingung bereits erfüllt (z. B. Spielstand einer früheren Version mit 150 Sprüngen oder 10 beendeten Ritten), wird die Auszeichnung nachgeholt: sofortige beim nächsten gezählten Sprung, die übrigen beim nächsten beendeten Ritt. Für „Fehlerfrei" genügt ein gespeicherter Parcours mit 3 Sternen; „Oxer-Profi" und „Kombi-Könner" werden nicht aus gespeicherten Daten abgeleitet (Regel 49).
- [ ] Sofort vergebene Auszeichnungen werden kurz eingeblendet, ohne das Spiel zu pausieren (Regel 49).
- [ ] „Fehlerfrei" wird bei Rittende vergeben, wenn ein Parcours-Ritt mit 0 Fehlern (inkl. Zeitfehler) beendet wurde (Regel 49).
- [ ] „Oxer-Profi" wird bei Rittende vergeben, wenn im beendeten Ritt ein Oxer gewertet (an der Reihe, in Sprungrichtung) gesprungen wurde, an dem es im ganzen Ritt weder Verweigerung noch Abwurf gab; ein Oxer als Teil einer Kombination zählt mit (Regel 49).
- [ ] „Kombi-Könner" wird bei Rittende vergeben, wenn im beendeten Ritt eine Zweifach-Kombination (a und b) gewertet gesprungen wurde, an der es im ganzen Ritt weder Verweigerung noch Abwurf gab (Regel 49).
- [ ] „Alles offen" wird bei Rittende vergeben, sobald alle 5 Parcours freigeschaltet sind (Regel 49).
- [ ] „Sternenreiter" wird bei Rittende vergeben, sobald alle 5 Parcours mit 3 Sternen geschafft sind (Regel 49).
- [ ] „Fleißig" wird bei Rittende vergeben, sobald 10 Parcours-Ritte beendet wurden (Regel 49).
- [ ] Bei Rittende vergebene Auszeichnungen erscheinen in der Ergebnisanzeige unter „Neue Auszeichnungen"; während des Ritts sofort vergebene erscheinen dort nicht erneut (Regeln 35, 49).
- [ ] Abgebrochene Ritte vergeben keine der bei Rittende vergebenen Auszeichnungen (Regel 40).
- [ ] Jede Auszeichnung wird nur einmal vergeben; eine erneute Erfüllung zeigt nichts erneut an (Regel 49).
- [ ] Die Übersicht zeigt alle 8 Auszeichnungen: erhaltene hervorgehoben mit Datum, fehlende mit ihrer Bedingung (Regel 50).
- [ ] Erhaltene Auszeichnungen und ihr Datum sind nach Neuladen erhalten (Regeln 44, 45).
- [ ] Die Absprung-Hilfe hat keinen Einfluss auf die Vergabe (Regel 42).
### Fortschritt löschen
- [ ] In den Einstellungen gibt es „Fortschritt löschen", aber nur wenn sie aus dem Hauptmenü geöffnet wurden; in den Einstellungen aus dem Pausemenü fehlt der Button. Es wirkt erst nach Bestätigung der Sicherheitsabfrage; „Abbrechen" ändert nichts (Regel 48).
- [ ] Nach dem Löschen ist nur Parcours 1 offen, es gibt keine Bestleistungen, Sterne, Auszeichnungen und die Zähler (Sprünge, beendete Ritte) stehen auf 0 (Regel 48).
- [ ] Zurückgesetzt werden nur die in Regel 48 genannten Fortschrittsdaten (Freischaltung, Bestleistungen, Sterne, Auszeichnungen, Zähler). Alle Einstellungen (auch später hinzukommende), das Pferd und alle übrigen gespeicherten Daten bleiben unverändert; die Namensfrage erscheint nicht erneut (Regeln 47, 48).
### Sprache
- [ ] Alle neuen Texte (Namensfrage, Mein Pferd, Namen und Bedingungen der Auszeichnungen, Einblendung, Sicherheitsabfrage) liegen auf Deutsch und Englisch vor und sind kurz und für eine 3. Klasse lesbar (Regel 6).
### Tests
- [ ] Unit-Tests decken die Bedingungen aller 8 Auszeichnungen, Einmaligkeit, Nicht-Vergabe bei Abbruch und den Umfang von „Fortschritt löschen" ab.

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (§Begriffe Auszeichnung, Kopfabzeichen; Regeln 2, 6, 35, 40, 42, 43, 44, 45, 48, 49, 50, 53, 54; §Grenzfälle „Erster Start")
- Voraussetzungen: SRT-001 (Speicher, Einstellungen), SRT-002 (Pferd), SRT-004 (Ritt-Ergebnis, Zähler, Sterne, Freischaltung)

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
