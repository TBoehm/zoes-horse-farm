---
id: SRT-013
title: „Mittel" stürzt nach ca. 5 s ab – leichter, sparsames Herunterstufen, Absturz-Erkennung
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-008, SRT-011]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (Android-Tablet, Chrome).

Spieltest 2026-10-04 nach SRT-010..012: „App lief ca. 5 s mit Automodus Mittel, springt dann
zurück ins Menü und WebGL crasht wieder." Vor SRT-011 lief „Mittel" auf dem Tablet stabil.

| Beobachtung | Vermutete Ursache | Soll |
| --- | --- | --- |
| Absturz nach ca. 5 s | SRT-011 macht „Mittel" deutlich teurer (Blumen, Wind, Vögel, Deko, grasende Pferde, Staub, mehr Shader-Programme; Dreiecke etwa doppelt) → Bildrate unter 50 fps → nach 5 s stuft die Automatik herunter (Regel 4) → der Stufenwechsel baut neue Programme/Objekte auf, solange die alten noch belegt sind → Speicherspitze → Browser beendet den Tab | „Mittel" wieder etwa so teuer wie vor SRT-011; Herunterstufen gibt zuerst Speicher frei |
| „Springt zurück ins Menü" | Der Browser hat den Tab neu geladen; die Behandlung des Kontextverlusts (Regel 4) kommt nicht mehr zum Zug | Unerwartetes Ende während der 3D-Darstellung zählt beim nächsten Start wie ein Kontextverlust |
| Beim nächsten Ritt wieder Absturz | Die Automatik weiß nichts vom Absturz und bleibt auf „Mittel" | Nach einem solchen Absturz Stufe Niedrig (Automatisch) bzw. Hinweis (manuell) |

## Ziel
„Mittel" läuft auf dem Tablet wieder stabil, ein Stufenwechsel stürzt nicht ab, und nach einem
Absturz startet das Spiel nicht erneut in die zu hohe Stufe.

## Scope
- In: Regel 4 (neue Punkte: Speicher zuerst freigeben, Absturz-Erkennung, „Mittel" nur
  geringfügig teurer), Regel 3 (Stufung der Details).
- Out: neue Details; Änderungen an „Hoch" außer dem sparsamen Stufenwechsel.

## Design (SSoT)
Kein neues UI; der Hinweis bei manueller Stufe ist der bestehende Hinweis aus Regel 4.

## Akzeptanzkriterien
- [ ] „Mittel" hat höchstens geringfügig mehr Shader-Programme, Draw Calls, Dreiecke und
  geschätzten Grafikspeicher als vor SRT-011 (Stand 5e240fc), geprüft per Test; Wind, Blumen,
  Vögel, Schmetterlinge, grasende Pferde nur auf „Hoch" (Regeln 3, 4).
- [ ] Beim Herunterstufen wird Grafikspeicher der wegfallenden Details freigegeben, bevor neue
  Programme oder Objekte entstehen; die Spitze der gleichzeitig belegten Programme und Objekte
  während eines Wechsels liegt nicht über dem Wert vor dem Wechsel (Regel 4).
- [ ] Endet das Spiel während der 3D-Darstellung unerwartet, gilt beim nächsten Start:
  „Automatisch" → Niedrig (gespeichert); manuelle Stufe über Niedrig → Hinweis (Regel 4).
- [ ] Normales Schließen, App-Wechsel oder ein Ende im Hintergrund zählt nicht als Absturz
  (Regel 4).
- [ ] Die Debug-Anzeige (`?debug`) zeigt nach einem erkannten Absturz die Stufe und Laufzeit der
  abgestürzten Sitzung.
- [?] Auf dem Tablet läuft „Mittel" stabil (Nachweis nur am Gerät).

## Links
Konzept Regeln 3, 4; SRT-008 (Grafikbudget, Kontextverlust), SRT-011 (Details).

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
