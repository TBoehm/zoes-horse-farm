# Projektregeln – Zoe's Horse Farm

Diese Regeln gelten für jede Änderung (auch für Subagents). Details:
`docs/specs/springreiten-trainer/architecture.md`.

## Arbeitsweise

- **Code immer auf Englisch:** Bezeichner, Kommentare, Testbeschreibungen (`describe`/`it`),
  Log- und Fehlermeldungen, Dateinamen. Nutzersichtbare Texte stehen nur in den i18n-Dateien
  (DE + EN). Planungs-Dokumente (Konzept, Tickets, Specs) bleiben deutsch.
- **TDD:** Für Domain- und Application-Code zuerst einen fehlschlagenden Test schreiben, dann den
  minimalen Code, dann aufräumen (Red → Green → Refactor). Kein neuer Domain-/Application-Code ohne
  Test. Adapter (three.js, DOM, WebAudio, Storage) werden über reine Hilfsfunktionen (mit Tests) und
  den Browser-Smoke-Test abgesichert.
- **Erst recherchieren, dann bauen:** Bewährte Lösungen, Bibliotheken und reale Kennwerte (Reitsport,
  three.js-Praxis) suchen, bevor etwas selbst erfunden wird.
- **Modelle:** Umsetzung durch Sonnet-Agents, Reviews und Koordination durch Opus.

## Clean Architecture (Abhängigkeiten zeigen nur nach innen)

```
src/domain/       reine Fachlogik: Reit-/Sprung-Simulation, Parcours & Wertung, Fortschritt &
                  Auszeichnungen, Pferd (Name, Aussehen). Kein three.js, kein DOM, kein Storage,
                  kein Date.now()/Math.random() direkt (Zeit/Zufall werden injiziert).
src/application/  Anwendungsfälle: Ritt-Sitzung (frei/Parcours), Fortschritt speichern, Einstellungen.
                  Orchestriert Domain über Ports (Interfaces als Parameter: store, clock, rng, sound).
                  Kein three.js, kein DOM.
src/adapters/     Technik: ui/ (Bildschirme, App-Rahmen, DOM), view3d/ (three.js), input/ (Tastatur,
                  Touch), audio/ (WebAudio), storage/ (localStorage), platform/ (PWA, WebGL, Touch-Modus).
src/main.js       Composition Root: verdrahtet alles, sonst keine Logik.
```

- `domain` importiert nur `domain`. `application` importiert `domain` und `application`.
  `adapters` dürfen alles Innere importieren, aber nicht `main.js`. ESLint erzwingt das
  (`no-restricted-imports`).
- UI-Bildschirme enthalten keine Fachregeln (Wertung, Freischaltung, Auszeichnungen,
  Sprungentscheidung) – sie rufen Application-Code auf und zeigen dessen Zustand an.
- Spielwerte nur in `src/domain/sim/tuning.js`.

## Gates (lokal = CI)

`npm run lint`, `npm run format:check`, `npm test`, `npm run build`, `npm run smoke` – Befehle siehe
README. Nach jedem Implementierungsschritt (jedes Agent-Ergebnis) laufen mindestens Lint,
Format-Check und Unit-Tests über das ganze Repo; vor jedem Commit alle fünf. Kein Commit mit rotem
Gate, auch kein WIP-Commit mit kaputtem Build.

Nach den Gates prüft immer ein frischer Opus-QA-Agent den gesamten Diff gegen alle Regeln dieser
Datei und die globalen Regeln des Users (Englisch, Schichten, TDD, Stil). Verstöße sind `major`;
erst bei 0 blocker/major ist ein Schritt fertig.
