# Zoe's Horse Farm

Ein Springreit-Spiel für Kinder im Browser (three.js), statisch auf GitHub Pages, offline-fähig.
Alle Grafiken und Klänge entstehen zur Laufzeit; einzige Datei-Grafik ist das selbst gezeichnete
App-Icon (`public/icon.svg`, PNGs werden beim Build erzeugt).

- Konzept: [docs/features/springreiten-trainer/concept.md](docs/features/springreiten-trainer/concept.md)
- Tickets: [docs/features/springreiten-trainer/tasks/](docs/features/springreiten-trainer/tasks/README.md)
- Technische Spec: [docs/specs/springreiten-trainer/architecture.md](docs/specs/springreiten-trainer/architecture.md)

## Entwicklung

Voraussetzung: Node.js 20 oder neuer (die CI nutzt Node.js 22).

```bash
npm ci
npm run dev        # Entwicklungs-Server
npm run build      # Produktions-Build nach dist/
npm run preview    # gebauten Stand unter http://localhost:4173 ansehen
```

Entwickler-Demoseiten (nicht ausgeliefert) laufen über den Dev-Server unter `/dev/*.html`.

Test-Hook für Browser-Tests: Mit `?testhooks` in der URL (z. B. `http://localhost:4173/?testhooks`)
stellt die App `window.__zhfTest` bereit (aktueller Bildschirm, Zustand des laufenden Rittes, Speicher,
Audio-Zustand; nur lesend, dazu `go(screen, params)` zum Springen auf einen Bildschirm). Ohne den
Parameter existiert der Hook nicht. Die Smoke-Tests (`tests/smoke/`) nutzen ihn und warten auf
Zustände statt auf feste Zeiten, weil der Software-Renderer in CI langsam ist (wenige fps).

## Quality Gates

Dieselben Befehle wie in GitHub Actions (`.github/workflows/ci.yml`):

| Gate         | Befehl                                                                                      |
| ------------ | ------------------------------------------------------------------------------------------- |
| Lint         | `npm run lint`                                                                              |
| Format check | `npm run format:check` (beheben: `npm run format`)                                          |
| Unit tests   | `npm test`                                                                                  |
| Build        | `npm run build`                                                                             |
| Smoke        | `npm run smoke` (nach `npm run build`; Browser einmalig: `npx playwright install chromium`) |

Alles am Stück: `npm run qa`. Der Smoke-Test läuft lokal in Chromium; andere Browser per
`SMOKE_BROWSERS=firefox,webkit npm run smoke`. Ein bereits installiertes Chromium lässt sich mit
`PW_CHROMIUM_PATH=/pfad/zu/chrome` nutzen. In CI läuft er in Chromium, Firefox, WebKit und Edge.

## CI/CD

- **Pull Request:** Lint, Format check, Unit tests, Build und Smoke (je Browser) laufen als eigene
  Checks (`ci.yml`).
- **Merge auf main:** `deploy.yml` führt alle Gates erneut aus; nur wenn alle grün sind, wird
  `dist/` auf GitHub Pages veröffentlicht (Job „Deploy to GitHub Pages" hängt von allen Gates ab).

### Einmalige Einrichtung (Repo-Inhaberin / Repo-Inhaber)

1. **Pages-Quelle:** Settings → Pages → Build and deployment → Source: **GitHub Actions**.
2. **Branch-Schutz für `main`:** Settings → Branches → Regel für `main` mit „Require status checks
   to pass" und diesen Pflicht-Checks: `Lint`, `Format check`, `Unit tests`, `Build`,
   `Smoke (chromium)`, `Smoke (firefox)`, `Smoke (webkit)`, `Smoke (msedge)`.

Beides erledigt auch das Skript (idempotent, mit Vorschau):

```bash
./scripts/github-setup.sh --dry-run   # zeigt, was passieren würde
./scripts/github-setup.sh             # richtet Pages und Branch-Schutz ein (gh CLI, Admin-Rechte)
```

## Speicherstand

Der Fortschritt liegt im `localStorage` des Browsers (Schlüssel `zoes-horse-farm.save`). Unbekannte
Bereiche bleiben beim Speichern erhalten, damit spätere Spielbereiche eigene Daten ergänzen können.
Bekannte Grenze: Safari kann Daten nach längerer Nichtnutzung löschen (nicht bei Nutzung über den
Startbildschirm).
