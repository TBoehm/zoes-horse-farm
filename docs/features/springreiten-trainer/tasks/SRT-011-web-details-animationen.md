---
id: SRT-011
title: Mehr Details und geschmeidigere Animationen – Umgebung, Pferd, Reiter
status: ready-for-dev
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-002, SRT-003, SRT-008]
effort: L
---
## Kontext
Betroffene Produkt-Plattformen: web (alle Grafikstufen).

Spieltest 2026-10-04: „Mir sind es noch viel zu wenig Details, sei es Umgebung, sei es das Pferd
und der Reiter. Auch mehr/geschmeidigere Animationen wären sehr sinnvoll."

| Bereich | Stand | Fehlt |
| --- | --- | --- |
| Umgebung | Bäume, Büsche, Gras (nur „Hoch" im Wind), Stall, Richterhaus, Bänke, Heu, Wolken | Blumen, Wind in Bäumen, Tiere/Vögel, Schmuck am Platz, Staub |
| Pferd | Körper, Mähne (starr), Schweif (Sinus), Augen ohne Lider, Sattel, Trense, Zügel | nachschwingende Mähne/Schweif, Blinzeln, Bandagen, Nüstern, Ruhe-Gesten |
| Reiter | Helm, Jacke, Reithose, Stiefel, Handschuhe; Gesicht leer | Gesicht, Kinnriemen, schwingender Zopf, Blick in die Kurve, Nachgeben im Sprung |
| Übergänge | Gangarten in ca. 0,2 s überblendet; Galoppwechsel und Sprungende springen sichtbar | geschmeidige Übergänge |

Recherche (Auszug, Quellen im PR):
- three.js-Praxis für Mobilgeräte: unter etwa 100 Draw Calls, Instanzen statt Einzelobjekte,
  Wind per Vertex-Shader, Nebel kaschiert Entfernung (three.js-Forum, Codrops „Fluffiest Grass").
- Pferde-Biomechanik: Schritt ≈ 0,7–1,0, Trab ≈ 1,1–1,5, Galopp ≈ 1,5–1,9 Schritte/s
  (IFCE). Kopf nickt im Schritt und Galopp deutlich, im Trab kaum.
- Pferde blinzeln etwa 8–19-mal pro Minute (Merkel et al., Sci. Rep. 2020).
- Geschmeidige Übergänge: gemeinsame normierte Schrittphase, Gewichte über 0,3–0,5 s
  überblenden, Parameter mit gedämpften Federn glätten („Spring-It-On", The Orange Duck).
- Mähne/Schweif: Ketten mit Feder-Dämpfer-Nachlauf (Verlet) statt starrem Sinus.

## Ziel
Hof, Pferd und Reiter wirken lebendig und detailreich, und alle Bewegungen laufen weich.

## Scope
- In: Regel 3 (Detailreichtum), Regel 24 (geschmeidige Bewegungen), Regel 4 (Speicher-Abschätzung
  zählt neue Details mit).
- Out: neue Hindernisarten, Wetter, Zuschauer-Figuren, neue Spielregeln, geladene Modelle oder
  Bilder (Regel 2).

## Design (SSoT)
Kein UI. Stufung: „Niedrig" erhält nur Details ohne Mehrkosten pro Bild (z. B. Gesicht,
Bandagen, Blinzeln); „Mittel" und „Hoch" zusätzlich Blumen, Wind, Vögel, Koppel, Staub; „Hoch"
die größte Dichte und Schmetterlinge.

## Akzeptanzkriterien
- [ ] Umgebung: Blumen auf den Wiesen, Bäume und Büsche im Wind, Vögel am Himmel, Wimpelketten
  und Blumenkästen am Reitplatz bzw. an den Hindernissen, eine Koppel mit grasenden Pferden
  (Regel 3).
- [ ] Sand staubt unter den Hufen im Trab, Galopp und bei der Landung (Regel 3).
- [ ] Pferd: Mähne und Schweif schwingen der Bewegung nach, das Pferd blinzelt (unregelmäßig,
  etwa alle 3–8 s), Bandagen an den Beinen, Nüstern atmen mit; im Stand gelegentlich
  Kopfschütteln oder Hufscharren (Regeln 3, 24).
- [ ] Reiter: Augen, Nase, Mund, Kinnriemen; der Zopf schwingt nach; der Kopf schaut in die Kurve;
  im Sprung leichter Sitz mit nachgebenden Händen (Regeln 3, 24).
- [ ] Gangartwechsel, Galoppwechsel, Übergänge in und aus Sprung, Verweigerung und Halt ohne
  sichtbares Springen von Beinen, Körper oder Reiter (geprüft per Test: keine Sprünge der
  Pose von Bild zu Bild über einer Grenze) (Regel 24).
- [ ] „Niedrig" bleibt mindestens so schnell wie bisher (Draw Calls und Dreiecke nicht mehr als
  vorher), „Mittel"/„Hoch" bleiben im Budget der Speicher-Abschätzung (Regeln 3, 4).
- [ ] Die Speicher-Abschätzung zählt die neuen Details mit (Regel 4).
- [ ] Weiterhin werden keine Bild-, Modell- oder Audiodateien geladen (Regel 2).

## Links
Konzept Regeln 2, 3, 4, 24; SRT-008 (Grafikbudget).

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
