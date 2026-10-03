---
id: SRT-003
title: Springen und freier Modus – Sprungmechanik, Hindernisse, Absprung-Hilfe, Sprungzähler
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-002]
effort: L
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Baut auf SRT-002 (Reitplatz, Pferd, Reiten, freier Modus ohne Hindernisse) auf. Liefert das Herz des
Spiels: die vier Hindernisarten, die Sprungmechanik (Gangart, Winkel, Distanz, Timing, sicherer Kern
mit Risiko am Rand, Selbst-Springen, Verweigerung, Hopser, Abwurf), den freien Modus mit fester
Übungsaufstellung, die Absprung-Hilfe und den gespeicherten Sprungzähler. Die Parcours-spezifischen
Regeln (Reihenfolge, Sprungrichtung, Hindernisse ohne Wertung) setzt SRT-004 auf dieser Mechanik auf.

Konkrete Abstände, Toleranzen, Tempi und Risikokurven sind laut Konzept Spielwerte, die in der
Umsetzung innerhalb der Regel-Grenzen abgestimmt werden.

## Ziel
Im freien Modus springt das Kind Kreuz, Steilsprung, Oxer und Zweifach-Kombination in verschiedenen
Höhen; gutes Anreiten und Timing führen verlässlich zu sauberen Sprüngen, Fehler zu Abwürfen oder
Verweigerungen, sichtbar und verständlich, mit optionaler Absprung-Hilfe.

## Scope
- In:
  - Hindernisarten Kreuz, Steilsprung, Oxer, Zweifach-Kombination, prozedural, mit fallenden
    Stangen (Regeln 2, 23; §Begriffe).
  - Sprungmechanik (Regeln 15–21), Verweigerung inkl. Zeitpunkt und Verhalten danach (Regel 22, Teil
    freier Modus), Galopp-Ende bei Verweigerung inkl. Shift neu drücken (Regel 9).
  - Sprung-Animation: Absprung, Flug, Landung (Regel 24).
  - Touch-Button „Springen" mit derselben Wirkung wie Space (Regel 10).
  - Freier Modus: feste Aufstellung, keine Wertung, Rückmeldung bei Abwurf/Verweigerung,
    Wiederaufbau nach ca. 3 s, beide Sprungrichtungen gültig (Regel 41; §Begriffe Sprungrichtung).
  - „Neu starten" im freien Modus baut zusätzlich alle Stangen auf (Regel 39).
  - Absprung-Hilfe im freien Modus, zwei gespeicherte Einstellungen in den Einstellungen (Regel 42,
    Teil Einstellungen und freier Modus).
  - Sprungzähler: zählt jeden Sprung über ein Hindernis, sofort gespeichert (Regeln 40 Zähler-Teil,
    44, 45).
- Out:
  - Parcours-Regeln: Reihenfolge, Hervorhebung, Sprungrichtung im Parcours, Verhalten an Hindernissen
    ohne Wertung (Ausweichen), Kombinations-Regeln im Ritt, Vorstart, Fehlerpunkte, Absprung-Hilfe
    im Parcours (SRT-004).
  - Auszeichnungen „Erster Sprung" und „Springmaus" (SRT-005; nutzen den Sprungzähler).
  - Klang (SRT-006).

## Design (SSoT)
Kein Design-Entwurf (bewusst ohne Design freigegeben).

| Element / Zustand | Inhalt |
| --- | --- |
| Übungsaufstellung | Mindestens je ein Kreuz, Steilsprung, Oxer und eine Zweifach-Kombination, in verschiedenen Höhen (40–85 cm), mit genug Platz zum Anreiten und Wenden. |
| Absprung-Hilfe | Farbige, halbtransparente Markierung der Absprungzone auf dem Sand vor dem angerittenen Hindernis. |
| Rückmeldung | Kurze Einblendung „Abwurf!" bzw. „Verweigert!" (DE/EN), verdeckt die Bildmitte nicht, verschwindet nach ca. 2 s. |
| Einstellungen | ergänzt um „Absprung-Hilfe im freien Modus: an/aus" und „Absprung-Hilfe im Parcours: an/aus". |

## Akzeptanzkriterien
- [ ] Der freie Modus zeigt eine feste Aufstellung mit mindestens je einem Kreuz, Steilsprung, Oxer und einer Zweifach-Kombination in verschiedenen Höhen, ohne Reihenfolge, Zeit, Fehlerpunkte oder Sterne (Regel 41).
- [ ] Alle Hindernisse entstehen ohne geladene Dateien (Regel 2).
- [ ] Aus Halt oder Schritt wird kein Hindernis gesprungen; aus dem Trab nur Kreuze; aus dem Galopp alle Hindernisse (Regel 16).
- [ ] Wird ein Steilsprung, Oxer oder eine Kombination im Trab angeritten und das Pferd ist am letzten Absprungpunkt noch im Trab, verweigert es (Regeln 16, 22).
- [ ] Ein Kurs mit mehr als 30° Abweichung von der Senkrechten, der das Hindernis zwischen den Ständern trifft, führt zur Verweigerung (Vorbeilaufen), auch mit Space (Regel 17).
- [ ] Bis 30° springt das Pferd; jenseits der Winkel-Toleranz des sicheren Kerns steigt die Abwurfrate mit zunehmendem Winkel (Regeln 17, 18).
- [ ] Liegen Gangart, Winkel, Tempo und Absprungdistanz innerhalb der Absprungzone und ihrer Toleranzen, gelingt der Sprung in 100 % der Versuche ohne Abwurf (Regel 18).
- [ ] Je weiter Tempo oder Absprungdistanz außerhalb der Toleranzen liegen, desto höher ist im Test die Abwurfrate (Regel 18).
- [ ] Höhere und breitere Hindernisse verlangen mehr Tempo und genaueres Timing: Bei gleicher Abweichung ist die Abwurfrate an einem 85-cm-Oxer höher als an einem Kreuz (Regel 15).
- [ ] Space in Reichweite bei zulässiger Gangart und zulässigem Winkel löst den Absprung sofort aus; zu früh (vor der Zone) oder zu spät (zu dicht) erhöht das Abwurfrisiko (Regel 19).
- [ ] Ohne Space springt das Pferd am letzten Absprungpunkt selbst, wenn Gangart, Winkel und Tempo passen, mit deutlich höherem Abwurfrisiko als ein Sprung aus der Absprungzone; sonst verweigert es (Regel 20).
- [ ] Im Touch-Modus löst „Springen" dieselbe Wirkung aus wie Space: Sprung, Hopser oder nichts (Regeln 10, 19, 21).
- [ ] Space in Reichweite bei unzulässiger Gangart oder unzulässigem Winkel löst weder Sprung noch Hopser aus (Regeln 19, 21).
- [ ] Space im Trab oder Galopp ohne Hindernis in Reichweite löst einen kleinen Hopser aus; im Halt oder Schritt passiert nichts; ein Hopser erhöht den Sprungzähler nicht (Regeln 21, 40).
- [ ] Ein Kurs, der am Hindernis vorbeiführt (z. B. eine Volte daneben), löst keine Verweigerung aus (Regel 22).
- [ ] Ob verweigert wird, entscheidet sich erst am letzten Absprungpunkt: Wer vorher angaloppiert oder abwendet, erhält keine Verweigerung (Regel 22).
- [ ] Verweigerung wegen Gangart oder zu geringem Tempo: Das Pferd bleibt vor dem Hindernis stehen und ist im Halt. Verweigerung wegen Winkel: Das Pferd läuft vorbei und ist danach im Trab. In beiden Fällen ist Galopp aus, auch der Touch-Umschalter (Regel 22).
- [ ] Hält das Kind bei einer Verweigerung Shift gedrückt, galoppiert das Pferd erst wieder, nachdem Shift losgelassen und neu gedrückt wurde (Regel 9).
- [ ] Nach einer Verweigerung kann das Kind im Halt wenden; eine weitere Verweigerung am selben Hindernis entsteht erst nach Entfernen über den Anreitabstand hinaus und neuem Anreiten (Regel 22).
- [ ] Reitet das Kind vorher (innerhalb des Anreitabstands) erneut darauf zu, springt das Pferd nur auf Space (normaler Sprung, Abwurf möglich, zählt im Sprungzähler) und nie selbst; ohne Sprung weicht es ohne Fehler seitlich aus und behält Gangart, Tempo und Galopp (Regeln 19, 22).
- [ ] Trifft das Pferd einen Ständer, weicht es ohne Fehler seitlich aus (Regel 22).
- [ ] Treffen bei einer Verweigerung Gangart bzw. Tempo und Winkel zusammen, bleibt das Pferd stehen und ist im Halt (Regel 22).
- [ ] Im freien Modus ist jedes Hindernis aus beiden Richtungen springbar und trägt keine Richtungsfahnen; Verweigerungen sind an jedem Hindernis möglich (Regeln 22, 41; §Begriffe Sprungrichtung).
- [ ] Bei einem Abwurf fällt die Stange sichtbar; im freien Modus wird sie etwa 3 Sekunden danach automatisch wieder aufgebaut (Regeln 23, 41).
- [ ] Abwurf und Verweigerung werden im freien Modus kurz als Rückmeldung eingeblendet (Regel 41).
- [ ] Aus der Standard-Kamera sind beim Anreiten das Hindernis und die Distanz dazu gut einschätzbar (Hindernis bis zum Absprung im Bild) (Regel 13).
- [ ] Absprung, Flug und Landung sind als eigene Bewegungsphasen des Pferdes erkennbar (Regel 24).
- [ ] Eine Zweifach-Kombination wird als ein Hindernis mit Teil a und b dargestellt; beide Teile sind einzeln springbar und können einzeln abgeworfen werden (§Begriffe).
- [ ] „Neu starten" im freien Modus setzt das Pferd an den Startpunkt und baut alle Stangen auf (Regel 39).
- [ ] Mit eingeschalteter Absprung-Hilfe ist im freien Modus vor dem gerade angerittenen Hindernis die Absprungzone auf dem Boden markiert (Regel 42).
- [ ] Die Markierung zeigt die Absprungzone für Hindernisart, Höhe und aktuelles Tempo und passt sich an, wenn sich das Tempo ändert (§Begriffe Absprungzone, Regel 42).
- [ ] In den Einstellungen (auch über das Pausemenü) gibt es „Absprung-Hilfe im freien Modus" (Standard an) und „Absprung-Hilfe im Parcours" (Standard aus); beide bleiben nach Neuladen erhalten (Regeln 42, 44).
- [ ] Jeder Sprung über ein Hindernis (auch mit Abwurf) erhöht den Sprungzähler um eins und wird sofort gespeichert; bei einer Zweifach-Kombination zählt jeder Teil einzeln (a und b = 2); Verweigerungen und Hopser zählen nicht (Regeln 40, 45).
- [ ] Mit der Übungsaufstellung erreicht der freie Modus auf der automatisch gewählten Stufe auf einem aktuellen PC und einem aktuellen Tablet beim Reiten und Springen über 30 Sekunden im Mittel mindestens 50 fps (Ziel 60 fps) (Regel 3).
- [ ] Rückmeldungen („Abwurf!", „Verweigert!") und die neuen Einstellungen liegen auf Deutsch und Englisch vor (Regel 6).
- [ ] Unit-Tests decken die Entscheidungslogik ab: Springbarkeit je Gangart, 30°-Grenze, sicherer Kern ohne Zufall, Verweigerung nur am letzten Absprungpunkt, Hopser, Sprungzähler.

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (Regeln 3, 6, 10, 13; §Begriffe Anreiten, Vor, Reichweite, Absprungzone, Letzter Absprungpunkt, Spielwert, Zweifach-Kombination; Regeln 2, 9, 15–24, 39, 40, 41, 42, 44, 45; §Grenzfälle „Volte neben einem Hindernis")
- Voraussetzung: SRT-002
- Folge: SRT-004 (Parcours), SRT-005 (Sprung-Auszeichnungen), SRT-006 (Sprung-Klänge)

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
