---
id: SRT-008
title: 3D-Bild verschwindet nach wenigen Sekunden (Android-Tablet)
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: [SRT-007]
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (Android-Tablet, Chrome; vermutlich weitere Mobilgeräte).

Spieltest 2026-10-04 (Screenshot): Kurz nach dem Start ist das Pferd zu sehen, nach wenigen
Sekunden nur noch Himmel und Gras; die Touch-Bedienelemente sind noch da, das Spiel reagiert nicht
sichtbar. Dasselbe passierte am Vortag direkt nach einem manuellen Grafikwechsel.

| Beobachtung | Ursache (Analyse) |
| --- | --- |
| Himmel/Gras mit harter Kante bei 58 % | Das ist der CSS-Hintergrund der Seite (`body`), nicht die 3D-Szene: die Zeichenfläche ist leer/durchsichtig – typisch für einen verlorenen WebGL-Kontext. |
| „nach wenigen Sekunden" | passt zur Automatik (3 s Schonzeit + 5 s Messfenster): unter 50 fps stuft sie herunter. |
| Vortag: direkt nach manuellem Wechsel | derselbe Stufenwechsel (alle Shader neu, Texturen neu hochgeladen, Schatten-Map neu) in einem Frame → Grafikprozess des Geräts überlastet → Kontextverlust. |
| Kein Pausemenü | Die laufende Version war noch die vor SRT-007 (PWA-Update erst nach Schließen aller Tabs), die Kontextverluste nicht behandelt. |

## Ziel
Das Bild verschwindet auf dem Tablet nicht mehr; falls doch einmal ein Kontextverlust passiert,
erholt sich das Spiel von selbst und stellt die Grafik niedriger.

## Scope
- In: Regel 4 (neuer Absatz): Kontextverlust → Automatik auf Niedrig bzw. Hinweis; Herunterstufen
  während des Ritts in verkraftbaren Schritten; Diagnose-Anzeige für Tests auf echten Geräten.
- Out: neue Grafikstufen; Hochstufen.

## Design (SSoT)
Kein Design-Werkzeug. Hinweise als Toast wie bestehende Meldungen. Diagnose nur mit `?debug` in der
Adresse, kleine Textbox oben links unter der fps-Zeile.

## Akzeptanzkriterien
- [x] Nach einem Kontextverlust mit „Automatisch" steht die Stufe nach der Wiederherstellung auf
  Niedrig (gespeichert), das Spiel ist weiter spielbar (Regel 4). (Nachweis: src/adapters/view3d/quality.test.js › levelAfterContextLoss; quality.test.js › levelAfterContextLoss › with "Automatic" on, a level above low goes to low and is saved; Verlust im Hintergrund zählt nicht: levelAfterContextLoss › a loss while the page is in the background is no overload / a loss right after the page came back to the foreground is no overload either; resilience.test.js › createRestoreWatchdog; Smoke-Test: tests/smoke/graphics.spec.js › the ride pauses on loss and can go on after the restore)
- [x] Nach einem Kontextverlust mit manueller Stufe über Niedrig erscheint ein Hinweis, die Grafik
  niedriger zu stellen (Regel 4). (Nachweis: quality.test.js › a manual level above low stays and the player gets a hint; quality.test.js › levelAfterContextLoss › the hint follows the same rule as the "level too high" hint; canHintLowerLevel › is true only for a manual level above low)
- [x] Das automatische Herunterstufen während des Ritts löst keinen Kontextverlust aus: es geschieht
  in kleinen Schritten über mehrere Frames (z. B. erst Auflösung, dann Schatten, dann Material),
  ohne Textur-Neuupload und ohne großen Shader-Stau in einem Frame (Regel 4). (Nachweis: src/adapters/view3d/quality-stages.test.js (Reihenfolge, keine Anisotropie-Stufe, Precompile je Shader-Stufe, Stage-Queue); quality-stages.test.js › planning a change between two levels › steps down resolution first, then shadows and materials in one stage, characters and scenery / createStageQueue › hands out the first stage on the next frame and the others after the gap / planQualityStagesFromState; Smoke-Test: tests/smoke/graphics.spec.js › after changing the level in both directions the ride stays steerable)
- [x] Mit `?debug` zeigt das Spiel GPU-Name, Stufe/Automatik, Pixel-Ratio und Zeichenflächen-Größe,
  Anzahl Kontextverluste/-wiederherstellungen und die letzten Fehlermeldungen. (Nachweis: src/adapters/ui/debug-display.test.js, src/adapters/platform/debug-info.test.js; tests/smoke/graphics.spec.js › debug box (?debug) › shows GPU, level, pixel ratios, buffer, context counts and the last errors)
- [x] Vor jeder Stufe (Start, Wechsel, auch manuell „Hoch") schätzt das Spiel den Grafikspeicher und
  senkt bei Überschreitung der Gerätegrenze erst die Auflösung, dann Schatten, dann Gras/Umgebung; die
  `?debug`-Anzeige zeigt Schätzung und Grenze (Regel 4). (Nachweis: quality.test.js › estimateGpuMemoryMB / gpuBudgetMB / fitPresetToBudget (Reihenfolge, monoton) / chooseAntialias; debug-display.test.js › shows the GPU memory estimate against the budget; Smoke-Test: tests/smoke/graphics.spec.js › GPU memory budget (rule 4) › a budget that "high" does not fit caps the pixel ratio and the ride still renders)
- [?] Auf dem Android-Tablet des Spieltests verschwindet das Bild nicht mehr, auch nicht auf „Hoch"
  (Nachweis nur am Gerät; Spieltest 2026-10-04: „Mittel" läuft gut, „Hoch" stürzt ab).

## Links
Konzept Regel 4; SRT-007.

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
