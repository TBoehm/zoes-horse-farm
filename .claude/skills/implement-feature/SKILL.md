---
name: "implement-feature"
description: "Setzt ein Ticket, eine Spec oder ein Feature end-to-end in beliebigem Repo um: Konventionen erkennen, offene Fragen einmal klären, Worktree, Gates bis 0 blocker/major, PR/MR. Bei /implement-feature."
---

# implement-feature: vom Ticket zum Pull/Merge Request

Orchestrator. Der **einzige** interaktive Stopp ist Schritt 3 (offene Fragen); danach läuft alles
ohne weitere Freigabe bis zum PR/MR.

| Name | Wert |
| --- | --- |
| `<MAIN>` | Haupt-Checkout (erster Eintrag von `git worktree list`). Pfade mit Leerzeichen immer quoten. |
| `<BASE>` | Basis-Branch für Feature-Branches (Schritt 0) |
| `<TASK>` | Ticket-ID, leer wenn keins existiert |
| `<SLUG>` | kurzer Kebab-Name, z. B. `soft-delete-recovery` |
| `<BRANCH>` | Projekt-Konvention, sonst `feat/<task-lower>-<SLUG>` bzw. `feat/<SLUG>` |
| `<WT>` | Worktree als Geschwister-Ordner: `<MAIN>/../<repo>-<BRANCH ohne />` |

## Schritt 0: Projekt erkennen (prüfen, nicht annehmen)

1. **Dieser Skill ist der Projekt-Skill** (`.claude/skills/implement-feature`). Nicht auf
   `/anthropic-skills:implement` oder einen anderen generischen `implement`-Skill umleiten,
   sondern hier weitermachen.
2. **Projekt-Regeln laden:** `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, `.github/`/`.gitlab/`
   Templates, ADRs. Sie regeln Architektur, Stil, Tests, Commit-Format, Sprache der UI-Texte und
   gehen den Defaults hier vor. Nicht wiederholen, befolgen.
3. **Stack und Kommandos ermitteln** aus den Manifesten, nicht aus dem Gedächtnis:
   `package.json` (scripts), `Makefile`/`justfile`/`Taskfile`, `build.gradle(.kts)`/`gradlew`,
   `pom.xml`, `pyproject.toml`/`tox.ini`/`noxfile.py`, `Cargo.toml`, `go.mod`, `*.csproj`,
   `Package.swift`, `composer.json`, `Gemfile`. Gesucht: **format**, **lint (fix + check)**,
   **typecheck**, **unit tests**, **integration/e2e**, **build**, **dependency audit**, lokale
   Dev-Umgebung (Container, DB, Emulator).
4. **CI lesen** (`.github/workflows/*`, `.gitlab-ci.yml`, `azure-pipelines.yml`, `Jenkinsfile`,
   `bitrise.yml`, ...) und daraus die **Gate-Liste** ableiten: jeder Check, den die CI auf PRs/MRs
   ausführt, muss lokal laufbar sein (Schritt 6). Gibt es ein Skript, das die CI-Gates bündelt
   (z. B. `npm run qa`, `make ci`, `./gradlew check`), ist das die Quelle.
5. **Basis-Branch:** Projekt-Konvention (z. B. `develop`), sonst
   `git symbolic-ref --short refs/remotes/origin/HEAD` (meist `main`).
6. **Forge:** Remote-URL → GitHub (`gh auth status`) oder GitLab (`glab auth status`), sonst nur
   Branch-Push. Fehlende Auth jetzt melden; Schritte 1-6 laufen trotzdem.
7. **Umgebung:** Läuft die Session ohne Repo (z. B. im Chat): im Modal fragen, welches Repo, und
   es anbinden bzw. klonen. Lokale Dienste (Docker, Emulator, DB) nie ungefragt kalt starten, wenn
   die Projekt-Regeln das verbieten; sonst nur das Nötige.

## Schritt 1: Input auflösen

- **Planungsquellen aktualisieren:** Liegen Konzept/Tickets in einem Submodule oder separatem Repo:
  `git -C "<pfad>" fetch origin && git -C "<pfad>" merge --ff-only origin/<basis>`. Wird das
  verweigert (eigene Änderungen), nicht forcieren, sondern per
  `git -C "<pfad>" show origin/<basis>:<datei>` lesen. **Submodule-Pin nie stagen**, außer der
  Pin-Bump ist ausdrücklich Teil der Aufgabe (dann nach Projekt-Regel, inkl. Attestierung).
- **Input bestimmen**, in dieser Reihenfolge:
  1. Ticket-ID als Argument → Ticket aus Tracking-Ordner, `gh issue view`, `glab issue view` oder
     Tracker-Connector (Jira, Linear, ...).
  2. Spec-Pfad oder Feature-Slug → Spec-Ordner des Projekts.
  3. Freie Beschreibung → passendes Ticket suchen (gleiches Feature + Plattform); keins → `<TASK>`
     leer, dem User sagen, dass der PR/MR nicht automatisch verlinkt wird.
  4. Nichts übergeben → Kandidaten (Tickets `ready-for-dev`, Spec-Ordner) listen und im Modal
     fragen.
- Ist das Ticket nicht `ready-for-dev` (DoR offen): im Modal fragen, ob erst
  `/plan-feature --task <ID>` laufen soll (Empfohlen) oder bewusst trotzdem.
- Verweisen Ticket/Spec auf ein Konzept (Link, `derived_from`, `feature:`): dem folgen, das ist
  das „Was".

## Schritt 2: Lesen, dann Spec-Lücke schließen

**Lesen an einen Subagenten delegieren** (`model: "sonnet"`, `general-purpose`/`Explore`). Roher
Spec-, Konzept- und Ticket-Text bleibt aus dieser Session. Digest:

- nutzersichtbares Ergebnis, Scope und Out, Akzeptanzkriterien;
- berührte Bereiche (Module/Schichten), Datenmodell/Migrationen, API-Verträge, i18n, Berechtigungen;
- Design-Referenzen (Pages/Frames), falls UI;
- Tests, die das Verhalten heute absichern (Unit, E2E-Selektoren/Assertions), die sich ändern;
- **jede Frage, die Ticket/Spec/Konzept offen lassen**.

Einzelne Spec nur dann voll in die Session holen, wenn das Digest nachweislich nicht reicht.
Design-Referenzen **vor** jeder UI-Arbeit ansehen.

**Spec-Pflicht** (Entscheidungsbaum):

```
> 1 Datei ODER > 30 LOC?
├── nein → keine Spec, direkt umsetzen
└── ja → mehrdeutig? → ja: Spec PFLICHT
         nein → parallelisiert (mehrere Subagenten/Worktrees)? → ja: Spec PFLICHT (je Strang)
                                                               nein: optional (im Zweifel: ja)
```

Spec-Ort: Projekt-Konvention, sonst `docs/specs/<SLUG>/`. Format der Projekt-Anleitung, sonst die
**Mini-Spec** (unten). Der Dry-Run gegen die Spec erzeugt Fragen → das ist fehlende Spec; sie gehen
in Schritt 3.

**Drift-Regel:** Muss der Code von der Spec abweichen → erst die Spec ändern. Weicht fachlich etwas
vom Konzept ab → erst das Konzept ändern (bzw. `/plan-feature --clarify`). Nie still abweichen.

## Schritt 3: Offene Fragen (der einzige interaktive Checkpoint)

Alle Fragen, die Ticket, Spec und Konzept wirklich offen lassen: mehrdeutiger Scope, Texte/Copy,
UX-Entscheidungen, fehlende Design-Pages, Benennung (Routen, Endpunkte, Tabellen), alles, wo falsch
Raten teuer ist.

- **Im Modal** (`AskUserQuestion`), unabhängige Fragen gebündelt (bis zu vier), abhängige einzeln
  vorab, Empfehlung als erste Option mit Begründung. Ohne Modal-Tool: nummerierte Fragen mit
  Optionen in einer Nachricht.
- **Nicht weitergehen, bevor sie beantwortet sind.** Ist alles eindeutig: in einer Zeile sagen und
  weiter.
- Antworten in Spec/Ticket festhalten (Spec dann einfrieren und committen).

## Schritt 4: Worktree und Umgebung

```bash
git -C "<MAIN>" fetch origin
git -C "<MAIN>" worktree add --no-track -b "<BRANCH>" "<WT>" "origin/<BASE>"
```

- Immer vom frisch gefetchten `origin/<BASE>`, nie vom lokalen Branch. `--no-track`, damit ein
  bloßes `git pull` nicht `<BASE>` in den Feature-Branch merged.
- Submodule im Worktree: `git -C "<WT>" submodule update --init` falls leer; Planungs-Submodule
  ggf. auf den Remote-Stand ziehen (nur Arbeitsbaum).
- **Bootstrap:** Abhängigkeiten aus `<MAIN>` verlinken, wenn das Ökosystem das verträgt
  (`node_modules`: Windows `mklink /J`, POSIX `ln -s`), sonst normal installieren. Neue Dependency
  im Feature → Link entfernen, echtes Install im Worktree (Lockfile!).
- Projekt-spezifische lokale Umgebung (Stack, DB, Emulator) im Worktree hochfahren, Ports aus dem
  Status-Kommando lesen, nie aus dem Gedächtnis.
- Ab hier ist `<WT>` das Arbeitsverzeichnis (in Claude Code ggf. `EnterWorktree` mit `path`).
- Kein Worktree möglich oder sinnvoll (kein Git, Projekt verbietet es): Feature-Branch im
  Haupt-Checkout, vorher `git status --porcelain` muss leer sein.

## Schritt 5: Umsetzen

Projekt-Regeln aus Schritt 0 gelten (Architektur, Stil, Kommentare, i18n, Barrierefreiheit). Was
die **Reihenfolge** bestimmt:

- **Design-first:** Design-Pages und Plattform-Guidelines vor dem Bauen einer Komponente; Code
  folgt dem Design. Wer eine Design-Page schreibt: unmittelbar vorher frisch laden, fremde
  Änderungen erhalten, danach verifizieren.
- **Erst recherchieren, dann bauen:** Bevor etwas selbst erfunden wird (Algorithmus, Kennwerte,
  UI-Muster, Hilfsbibliothek), nach bewährten Lösungen suchen (Web-Recherche, etablierte
  Bibliotheken, Fachquellen) und das Ergebnis mit Quelle in Spec bzw. Agent-Briefing geben.
- **Clean Architecture:** Abhängigkeiten zeigen nur nach innen (Adapter → Application → Domain).
  Domain und Application ohne Framework-, DOM-, Netz- oder Storage-Zugriff; Zeit, Zufall und
  Persistenz kommen über injizierte Ports. UI enthält keine Fachregeln. Ordnerstruktur und
  Grenzen aus den Projekt-Regeln; fehlen sie, in der Spec festlegen und per Lint-Regel
  (z. B. `no-restricted-imports`) erzwingen.
- **Contract-first bei Parallelität:** Schnittstellen/DTOs/Typen zuerst, dann Domain, Daten, UI
  parallel, Verdrahtung (DI, Routen, zentrale Registries) zuletzt und sequentiell. Hotspot-Dateien
  gehören genau einem Strang.
- **TDD:** Für Domain- und Application-Code (Use-Cases, Parser, Berechnungen, Zustandsautomaten)
  zuerst ein fehlschlagender Test, dann der minimale Code, dann aufräumen (Red → Green →
  Refactor). Adapter: reine Hilfsfunktionen herauslösen und testen, Verhalten per E2E/Smoke.
  Jedes Agent-Briefing fordert TDD ausdrücklich; der Report nennt Teile ohne Test.
- **Gates nach jedem Implementierungsschritt:** Nach jedem abgeschlossenen Strang bzw.
  Agent-Ergebnis laufen die schnellen Gates (Lint, Format-Check, Unit-Tests) über das ganze Repo,
  vor jedem Commit zusätzlich Build und Smoke/E2E. Nichts wird committet, solange ein Gate rot ist
  (auch kein „WIP"-Commit mit kaputtem Build auf einem geteilten Branch).
- **Tests im selben Durchgang:** nach Selektoren/Assertions suchen, die die Änderung bricht
  (Unit, E2E, Snapshots), und jetzt anpassen.
- **Migrationen sind unveränderlich**, sobald gemerged oder ausgerollt: Korrekturen nur als neue
  Migration.
- **Fan-out parallelisieren:** unabhängige Dateigruppen → parallele Subagenten in EINER Nachricht,
  je mit klarer Datei-Ownership. Umsetzungs-Agents laufen auf `model: "sonnet"`; auch
  Ticket-übergreifend parallelisieren, wenn Abhängigkeiten es zulassen (contract-first).
- **Koordination und tragende Entscheidungen in der Hauptsession (`opus`):** neue
  Feature-Vertikale, Schichtung, Datenmodell/Schema, Migrationen, Sicherheits-relevante Logik
  werden in der Spec entschieden und den Sonnet-Agents als Vertrag mitgegeben. Ab Schritt 3 hält
  niemand mehr an, um das Modell hochzustellen.
- Manuelle Prüfung, wenn hilfreich, mit dem Test-/Dev-Kommando des Projekts; gestartete Server
  werden in Schritt 8 aufgeräumt.
- Peer-Sessions (falls `ListAgents` existiert): Test schlägt fehl, den du nicht verursacht hast,
  oder Entscheidung, an der andere Tickets hängen → erst nachweisen, dass es nicht deins ist
  (gegen die Basis laufen lassen), Bugs/offene PRs prüfen, dann höchstens drei passende Sessions
  mit einem konkreten Vorschlag anschreiben; nicht blockieren, nicht pollen.

## Schritt 6: Quality Gates

**Der lokale Lauf ist ein Superset der CI.** Nichts darf in der Pipeline laufen, was hier nicht
vorher lief, und nie eine schwächere Variante (ein schreibendes `format` ersetzt kein
`format:check`).

**Ein roter mechanischer Gate blockiert den PR/MR, auch wenn `<BASE>` ihn verursacht hat.** Abhilfe
in dieser Reihenfolge: (1) aktuellen `origin/<BASE>` mergen und neu laufen lassen, (2) Fix
separat landen, dann mergen, (3) User fragen, PR/MR bis dahin nicht öffnen.

Reihenfolge:

0. `git fetch origin && git merge origin/<BASE>` (Gates laufen auf aktuellem Stand).
1. **Schreibende** Schritte: format, lint --fix (bis 0 Fehler/Warnungen nach Projekt-Standard).
2. **Prüfende** CI-Gates komplett: format-check, lint, typecheck, tests, build, audit, DB-Lint,
   was die CI eben hat. Alle laufen lassen, auch nach dem ersten Fehler, damit ein Lauf alle
   Probleme nennt. In der Fix-Schleife darf ein schnellerer Teil laufen; der komplette Lauf einmal
   vor dem PR/MR.
3. **Lokal-only** nach Auslöser: E2E/Integration, wenn sich gerendertes Verhalten, Routen,
   Datenfluss oder Seeds ändern.
4. **Review-Subagenten** (immer **frisch**, `model: "opus"`, nie der Session-Wert):

| Berührt | Review |
| --- | --- |
| Produktionscode | Code-Review (Korrektheit, Architektur, Tests, Sicherheit, Spec-Erfüllung) |
| zusätzlich UI (auch ein Token, ein Padding, ein Klassenname) | Design-QA (gegen Design-Pages und Guidelines, gerendert) |
| Migrationen, Schema, Seeds, Policies | DB-Review (Sperren, Rückwärtskompatibilität, RLS/Rechte, Indizes) |
| Auth, Secrets, Krypto, Eingabe-Parsing | Security-Fokus im Code-Review |

Hat das Projekt eigene Reviewer-Agenten (`.claude/agents/`) oder Gate-Skills, diese nutzen. Gibt es
einen externen Reviewer-Weg (z. B. ein Codex-Skill), nach Projekt-Regel. Bei UI laufen Code-Review
und Design-QA **parallel** (zwei Agent-Aufrufe in einer Nachricht).

**Briefing** jedes Reviewers, sonst prüft er ins Leere:
1. Spec/Konzept/Ticket-Pfade (die Verträge, gegen die geprüft wird);
2. Absicht in 1-3 Sätzen (nutzersichtbares Ergebnis);
3. exakte Liste der geänderten Dateien mit absoluten Pfaden.
Der Code-Review prüft zusätzlich die Schichtgrenzen (keine Fachregeln im UI, keine
Framework-/DOM-Abhängigkeit in Domain/Application) und ob für neue Domain-/Application-Logik Tests
existieren; fehlende Tests für Fachlogik sind `major`.
Ausgabe: Befunde mit Schwere `blocker | major | medium | minor | nitpick`, Datei:Zeile, Begründung,
Fix-Vorschlag. Halb oder nicht erfüllte Spec-Verifikationskriterien sind `major`.

**Schleife:** alle `blocker`/`major` (bei Design/DB auch `medium`) fixen, mechanische Gates neu,
**neuer** Reviewer pro Runde. Abgebrochener Reviewer (Netz, Standby) wird per `SendMessage`
fortgesetzt, nicht neu gestartet. `minor`/`nitpick` fixen, wenn billig, sonst im Report nennen.
Zwei Runden sind normal; **ab der dritten umdenken statt weiter flicken** und dem User sagen.
Fertig erst bei 0 blocker und 0 major.

Lang laufende Schritte im Hintergrund mit Abschluss-Benachrichtigung oder `Monitor`, nie
`sleep`-Schleifen, nie `| tail` vor einem Exit-Code, der gebraucht wird.

### Schritt 6b: Lokale Umgebung runterfahren, sobald die Gates grün sind

Container, Stacks, Emulatoren, die nur für Tests liefen, jetzt stoppen, nicht erst nach dem PR/MR
(jede Unterbrechung danach ließe sie laufen). Der Worktree bleibt. Ausnahme: User wollte sie
behalten; dann im Report nennen.

### Schritt 6c: Ticket selbst abhaken (Pflicht)

- **Jede** offene Zeile in Akzeptanzkriterien und DoD gegen das Gebaute prüfen.
- Verifizierbar (Code, Test, Gate-Ergebnis, Datei, Artefakt) → `[x]` mit Kurz-Nachweis:
  `(Nachweis: <test/datei/gate>)`.
- Konzept-/Doku-Nachzug (Umsetzungsstand, Changelog) gehört in diesen Schritt.
- `[?]` nur für Zeilen, die du nachweislich nicht selbst prüfen kannst (echtes Gerät, Produktion,
  Bestätigung durch Betreiber, subjektive Design-Abnahme), je ein Satz Begründung.
- Code widerspricht einem AK → zurück zu Schritt 5, nicht abhaken.
- Offen bleibt nur „gemerged".
- Schreiben im Ticket-System des Projekts (Datei-Commit nach dessen Konvention oder Tracker-API).

## Schritt 7: Report, dann publizieren

Kurzer scanbarer Report zuerst (rein informativ, keine zweite Freigabe): geänderte Dateien, die
Entscheidungen aus Schritt 3, Gate-Ergebnisse, bewusst nicht gefixte Befunde.

Dann publizieren, nach Projekt-Konvention (Bot-Identität, Commit-Format, Versionierung):

- Branch pushen; PR/MR gegen `<BASE>` öffnen (`gh pr create` / `glab mr create`), `<TASK>` in
  Branch **und** Titel, ein Ticket pro PR/MR.
- Beschreibung: was und warum (ein Absatz), Link auf Ticket, Spec und Konzept, Gate-Ergebnisse,
  offen gebliebene Punkte, Operator-Schritte (7a). PR-Template des Repos verwenden, falls vorhanden.
- Kein Forge-Zugang: Branch pushen und dem User den Compare-Link bzw. das Kommando nennen.

### Schritt 7a: Operator-Aufgaben als Skript

Braucht das Feature manuelle Schritte vor dem Betrieb (CI-Variablen, Secrets, Cloud-Setup,
einmalige Konfiguration): kein Checklisten-Text allein, sondern ein Skript für das OS dieser
Session (PowerShell 5.1-kompatibel auf Windows, Bash auf macOS/Linux) unter
`scripts/<SLUG>-setup.*`, im selben PR/MR. Idempotent, `-WhatIf`/`--dry-run`, Secrets über
Temp-Datei oder stdin statt argv, Tool-Ausgaben vor dem Drucken redigiert. Mit Parse-Check und
Dry-Run verifizieren; den echten Schreiblauf **nie** selbst ausführen. Skript und Aufruf in
PR/MR-Beschreibung und Report nennen.

## Schritt 8: Abschluss

- PR/MR-URL, eine Zeile was geliefert wird, wo `<WT>` liegt.
- Worktree **bleibt** (Review-Runden arbeiten darin). Abbau erst nach dem Merge: lokale Umgebung
  stoppen → `git merge-base --is-ancestor "<BRANCH>" "origin/<BASE>"` (sonst NICHT löschen, User
  fragen) → `git -C "<WT>" status --porcelain` leer → Dependency-Link lösen →
  `git worktree remove "<WT>"` (mit Submodulen `--force`) → `git branch -d "<BRANCH>"`.
- Hintergrund-Prozesse und Dev-Server dieser Session beenden, Ports freigeben.
- **Nie selbst mergen, nie deployen.** Merge integriert, Deploy/Promotion ist Entscheidung des Users.

---

## Mini-Spec (wenn das Projekt kein eigenes Format hat)

```markdown
# <Feature/Task>
## Outcome
<3-5 Zeilen, Nachbedingung statt Ziel: was ist wahr, wenn das fertig ist?>
## Scope
- In: ...
- Out: ...
## Constraints
<5-10 MUSS / DARF NICHT: Architektur, bestehende Verträge, Hotspot-Ownership>
## Files
- Owns: <Pfade/Globs, die diese Spec schreibt>
- Reads: <nur gelesen>
- Forbidden: <darf nicht angefasst werden>
## Tasks
1. <atomar, eine Zeile, ein Commit>
## Verification
- [ ] <objektiv prüfbar>
## References
- Ticket / Konzept / Vertrag / Eltern-Spec
```

Budget: Mini-Spec < 150 Zeilen (~1.500 Tokens), Obergrenze 2.000; darüber zerlegen (nach Schicht,
nach Oberfläche, nach Risiko; bei Parallelität contract-first mit `spec-00-contract` und
`spec-NN-wiring` zuletzt). Nicht in die Spec: Hintergrund und User Stories (Konzept), Begründungen
und Alternativen (ADR), Code > 5 Zeilen (verlinken), offene Fragen (vor dem Einfrieren klären).
Validierung vor dem Einfrieren: Dry-Run gegen die Spec erzeugt 0 Rückfragen.

## Modell-Policy

Projekt-Policy geht vor. Default: Lesephase und **jede Umsetzung** durch Subagents `sonnet`;
Koordination, Spec/Architektur-Entscheidungen und **alle** Reviewer/QA `opus`. Jeder Subagent-Aufruf trägt sein `model`
explizit. Werkzeuge, die nur der Hauptsession zur Verfügung stehen (z. B. Design-Schreibzugriffe),
werden nicht delegiert.

## Guardrails

- Schritt 3 ist der einzige Stopp; nicht auf Vermutungen daran vorbei, keine zweite Freigabe
  erfinden.
- Gates sind Pflicht, nach jedem Implementierungsschritt und vor jedem Commit; Ausnahmen nur nach
  Projekt-Regel und mit genannter Begründung.
- TDD und Clean Architecture für Domain/Application; Recherche vor Eigenbau.
- Ein roter Gate blockiert, auch wenn er von `<BASE>` kommt.
- Spec/Konzept zuerst ändern, nie still abweichen.
- Migrationen unveränderlich nach Merge/Rollout.
- Submodule-Pins nur mit Absicht und Projekt-Attestierung.
- Keine Secrets in Commits, Logs, argv oder Nachrichten.
- Nie mergen, nie deployen.
- Projekt-eigene Skills (implement, Gates, Publish, Worktree) werden über das Skill-Tool
  aufgerufen, nicht aus ihrem Text nachgebaut.