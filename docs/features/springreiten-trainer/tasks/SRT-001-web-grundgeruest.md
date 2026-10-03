---
id: SRT-001
title: Grundgerüst – App-Rahmen, Menü, Sprachen, Speicher, Offline, CI/CD
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p1
depends_on: []
effort: M
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Erstes Ticket des Springreiten-Trainers (erstes Feature von „Zoe's Horse Farm"). Das Repo ist bis
auf Planungsdokumente leer. Dieses Ticket legt den lauffähigen, veröffentlichten Rahmen an, auf dem
alle weiteren Tickets (SRT-002 bis SRT-006) aufbauen: statische Web-App, Hauptmenü, Einstellungen,
zweisprachige Texte, Speicherung im Browser, Offline-Fähigkeit, Querformat- und 3D-Hinweise sowie
die automatischen Qualitätsprüfungen und die Veröffentlichung per GitHub Actions.

Nachweis: AKs, die erst nach dem Merge prüfbar sind, tragen den Vermerk „(Nachweis nach Merge)“; sie prüft die
Repo-Inhaberin bzw. der Repo-Inhaber nach dem Merge. Alle anderen AKs werden vor dem Merge am gebauten Stand
lokal bzw. in CI nachgewiesen.

Liefer-Reihenfolge: Das Hauptmenü zeigt in diesem Ticket nur Einträge, deren Funktion schon
existiert (hier: „Einstellungen"). SRT-002, SRT-004 und SRT-005 fügen ihre Einträge hinzu; danach ist
das Menü vollständig wie in Regel 53. Das ist zugleich der Nachweis für Regel 54.

## Ziel
Unter der GitHub-Pages-Adresse öffnet sich ein zweisprachiges Hauptmenü mit Einstellungen, das nach
dem ersten Laden offline startet und Einstellungen dauerhaft speichert; jede Änderung am Code wird
automatisch geprüft und nach dem Merge auf main automatisch veröffentlicht.

## Scope
- In:
  - Statische Web-App, veröffentlicht auf GitHub Pages, ohne Server-Logik (Regel 1).
  - Keine Bild-, Audio-, Modell- oder Schriftdateien; Systemschrift; App-Icon und Browser-Tab-Icon als
    selbst erstellte Vektorgrafik (Regel 2).
  - Offline-Start nach erstem Laden, zum Startbildschirm hinzufügbar, Updates im Hintergrund ab dem
    nächsten Start (Regel 5).
  - Zweisprachige Texte DE/EN, Startsprache nach Browsersprache, Umschalter in den Einstellungen
    (Regel 6).
  - Hinweis bei fehlender 3D-Unterstützung (Regel 7).
  - Touch-Modus (Erkennung, Wechsel bei Geräten mit Tastatur und Touch) und Querformat-Pflicht mit
    Hinweis „Gerät drehen" (Regeln 11, 12). Die Touch-Bedienelemente selbst baut SRT-002.
  - Hauptmenü-Rahmen, erweiterbar (Regeln 53, 54); Einstellungen-Bildschirm mit Sprache.
  - Spielstand im Browser: Grundstruktur, dauerhafter Speicher wo möglich, Erhalt unbekannter Daten, sofortiges Speichern bei Änderung, Hinweis wenn Speichern
    nicht möglich, robustes Laden fehlerhafter/alter Daten, erweiterbar für spätere Bereiche
    (Regeln 44 Rahmen, 45, 46, 47).
  - GitHub Actions: Gates bei jedem Pull Request (Lint, Format-Check, Unit-Tests, Build,
    Browser-Smoke-Test), Veröffentlichung auf Pages nach Merge auf main nur bei grünen Gates,
    Anleitung zum Branch-Schutz.
- Out:
  - 3D-Szene, Pferd, Steuerung, Grafikstufen (SRT-002).
  - Inhalte der Menüeinträge „Parcours", „Freier Modus", „Mein Pferd", „Auszeichnungen" (SRT-002 bis
    SRT-005); Einstellungen für Grafik, Kamera, Absprung-Hilfe, Lautstärke (jeweils im Ticket des
    Features).
  - „Fortschritt löschen" (SRT-005).
  - Typprüfung als Gate (bewusst nicht gewünscht).
  - Klang (SRT-006).
  - 60 fps und Grafikstufen aus Regel 3 (SRT-002).

## Design (SSoT)
Kein Design-Entwurf (bewusst ohne Design freigegeben). Der Look entsteht im Code ohne Bilddateien.

| Bildschirm / Zustand | Inhalt |
| --- | --- |
| Hauptmenü | Spieltitel „Zoe's Horse Farm", darunter die verfügbaren Einträge als große Buttons (in diesem Ticket: „Einstellungen"), in der Reihenfolge aus Regel 53. |
| Einstellungen | Sprache (Deutsch / English), „Zurück". Platz für weitere Einstellungen der Folge-Tickets. |
| Hinweis „Gerät drehen" | Vollflächig im Hochformat bei aktivem Touch-Modus, ersetzt jede Ansicht, DE/EN. |
| Hinweis „Kein 3D" | Vollflächig, kindgerechter Text DE/EN, keine leere Seite. |
| Hinweis „Speichern nicht möglich" | Einmal je Sitzung, schließbar, Spiel bleibt bedienbar. |

Alle Buttons im Touch-Modus mindestens 44×44 px.

## Akzeptanzkriterien
- [x] Der gebaute Stand läuft als rein statische Seite; im Netzwerk-Tab gibt es keine Anfragen an eine eigene Server-Logik (Regel 1). (Nachweis: tests/smoke/app.spec.js › page loads, main menu appears, no errors, no forbidden files; vite.config.js (statischer Build nach dist/, kein fetch/XHR/WebSocket im Code); .github/workflows/deploy.yml (nur dist/ als Pages-Artefakt))
- [?] Die App ist unter der GitHub-Pages-Adresse erreichbar (Regel 1) (Nachweis nach Merge). (Nicht hier prüfbar: Das geht erst nach dem Merge auf GitHub. Vorhandener Nachweis: .github/workflows/deploy.yml und scripts/github-setup.sh sind vorbereitet)
- [x] Beim Laden der App werden keine Bild-, Audio-, 3D-Modell- oder Schriftdateien angefragt; einzige Ausnahme sind App-Icon und Browser-Tab-Icon als selbst erstellte Vektorgrafik sowie daraus beim Build erzeugte Rastergrafiken (Regel 2). (Nachweis: tests/smoke/app.spec.js › page loads, main menu appears, no errors, no forbidden files; tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files; tests/smoke/audio.spec.js › audio › nothing starts before the first interaction, then the sound is unlocked; tests/smoke/helpers.js (watchPage: Whitelist nur icon.svg und generated/*.png))
- [x] Nach einmaligem vollständigen Laden startet die App mit abgeschaltetem Netzwerk und das Menü ist bedienbar (Regel 5). (Nachweis: tests/smoke/pwa.spec.js › PWA › starts offline after the first load)
- [?] Auf dem Desktop lässt sich der lokal gebaute Stand in Chrome/Edge installieren und zeigt das eigene Icon (Regeln 2, 5). (Nicht hier prüfbar: Die Installation in einem echten Chrome/Edge wurde hier nicht ausgeführt. Vorhandener Nachweis: tests/smoke/app.spec.js › PWA: manifest and service worker present; vite.config.js (Manifest mit Icons 192/512/maskable, display standalone); scripts/generate-icons.mjs)
- [?] Auf Tablet/Handy kann die App zum Startbildschirm hinzugefügt werden und zeigt dort das eigene Icon (Regeln 2, 5) (Nachweis nach Merge). (Nicht hier prüfbar: Nachweis nach Merge auf echten Geräten. Vorhandener Nachweis: Manifest und Icons siehe vite.config.js und tests/smoke/app.spec.js › PWA: manifest and service worker present)
- [x] Wird eine neue Version bereitgestellt, wird sie im Hintergrund geladen und gilt erst, nachdem alle Tabs bzw. die Startbildschirm-App geschlossen und neu geöffnet wurden; Neuladen eines offenen Tabs wechselt die Version nicht; es erscheint kein Hinweis und ein laufendes Spiel wird nicht unterbrochen; der gespeicherte Stand bleibt erhalten (Regeln 5, 47). Nachweis am lokal gebauten Stand mit zwei Versionen. (Nachweis: tests/smoke/pwa.spec.js › PWA › new version only after closing all tabs, save data is kept; vite.config.js (kein skipWaiting/clientsClaim, kein Update-Hinweis; die App sendet kein SKIP_WAITING))
- [x] Beim ersten Start ist die Sprache Deutsch, wenn die Browsersprache Deutsch ist, sonst Englisch (Regel 6). (Nachweis: src/adapters/ui/i18n.test.js › detectLang (rule 6) › picks German for a German browser language / picks English otherwise; src/adapters/storage/local-store.test.js › loading save data (rule 47) › uses initial values for empty storage (start language from env))
- [x] In den Einstellungen lässt sich die Sprache umschalten; alle sichtbaren Texte wechseln sofort; die Wahl bleibt nach Neuladen erhalten (Regeln 6, 45). (Nachweis: tests/smoke/app.spec.js › switching the language takes effect immediately and persists after a reload; src/application/settings-service.test.js › settings service › language › saves a supported language)
- [x] Jeder sichtbare Text liegt auf Deutsch und Englisch vor; es erscheinen keine Platzhalter-Schlüssel (Regel 6). (Nachweis: src/adapters/ui/i18n/strings.test.js › texts (rule 6) › have the same keys in German and English / are not empty and have the same placeholders; tests/smoke/helpers.js (watchPage macht „Missing text“-Konsolenmeldungen zum Fehler; geprüft in app.spec.js › page loads, main menu appears, no errors, no forbidden files))
- [x] In einem Browser ohne 3D-Unterstützung (z. B. WebGL deaktiviert) erscheint beim App-Start statt der ganzen App (auch statt der Menüs) der kindgerechte Hinweis in der passenden Sprache, keine leere Seite (Regel 7). (Nachweis: tests/smoke/app.spec.js › without WebGL only the notice appears instead of the app; src/adapters/ui/i18n/core.js (notice.no3d.* DE/EN))
- [x] Auf einem reinen Touch-Gerät ist der Touch-Modus von Anfang an aktiv, auf einem Gerät ohne Touchscreen nie; erkennbar daran, dass im Hochformat der Dreh-Hinweis erscheint bzw. ausbleibt (Regel 11). (Nachweis: src/adapters/platform/input-mode.test.js › touch mode (rule 11) › touch-only device: always active, keys change nothing / keyboard-only device: never active; tests/smoke/app.spec.js › touch device in portrait › shows the rotate notice; tests/smoke/app.spec.js › desktop in portrait shows no rotate notice)
- [x] Auf einem Gerät mit Tastatur und Touchscreen startet die App ohne Touch-Modus; eine Berührung schaltet ihn an, eine Spieltaste (W, A, S, D, Shift, Space, Esc, C) schaltet ihn aus; erkennbar am Dreh-Hinweis im Hochformat (Regel 11). (Nachweis: src/adapters/platform/input-mode.test.js › touch mode (rule 11) › hybrid: starts off, touch turns it on, game key turns it off; src/adapters/platform/input-mode.test.js › shared game keys › is the same list the keyboard adapter handles)
- [x] Solange der Touch-Modus aktiv ist, erscheint im Hochformat in jeder Ansicht (auch Menüs) der Hinweis „Gerät drehen"; im Querformat verschwindet er (Regel 12). (Nachweis: tests/smoke/app.spec.js › touch device in portrait › shows the rotate notice (Hochformat sichtbar, Querformat weg); tests/smoke/ride.spec.js › touch controls (SRT-002) › turning to portrait pauses the ride and shows the rotate notice; src/adapters/ui/notices.js (Hinweis über allen Ansichten))
- [x] Ohne Touch-Modus erscheint im Hochformat kein Dreh-Hinweis (Regel 12). (Nachweis: tests/smoke/app.spec.js › desktop in portrait shows no rotate notice)
- [x] Das Hauptmenü zeigt nur Einträge, deren Funktion vorhanden ist (in diesem Ticket „Einstellungen"); die Ergänzbarkeit ohne Änderung bestehender Einträge wird mit SRT-002 (Eintrag „Freier Modus") nachgewiesen (Regeln 53, 54). (Nachweis: src/adapters/ui/menu.js (registerMenuEntry/visible, Reihenfolge nach order); tests/smoke/profile.spec.js › main menu entries › all entries of rule 53 in order, with the horse name in the greeting; tests/smoke/app.spec.js › page loads, main menu appears, no errors, no forbidden files; Hinweis: Ergänzbarkeit ohne Änderung bestehender Einträge ist mit dem Eintrag „Freier Modus“ (SRT-002) und den weiteren Einträgen praktisch nachgewiesen; kein eigener Unit-Test der Registry)
- [x] Eine geänderte Einstellung ist nach Neuladen der Seite noch gesetzt, ohne dass ein Speichern-Button nötig ist (Regel 45). (Nachweis: tests/smoke/app.spec.js › switching the language takes effect immediately and persists after a reload; tests/smoke/ride.spec.js › free riding (SRT-002) › the graphics setting offers automatic and three levels and applies the choice; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › saves immediately on change)
- [x] Ist Speichern nicht möglich (z. B. privater Modus mit blockiertem Speicher, Speicher voll), bleibt die App bedienbar und zeigt einmal je Sitzung einen Hinweis, dass der Fortschritt nicht gespeichert wird; Neuladen zeigt ihn nicht erneut, erst nach Schließen und Neuöffnen von Tab bzw. App (Regel 46). (Nachweis: tests/smoke/app.spec.js › saving blocked (localStorage / both storages): notice once per session, app usable; src/adapters/storage/local-store.test.js › saving not possible (rule 46) › shows the notice once per session, not again after a reload / stays usable and reports canSave=false)
- [x] Mit absichtlich beschädigten gespeicherten Daten startet die App ohne Absturz, übernimmt lesbare Werte und setzt den Rest auf Anfangswerte (Regel 47). (Nachweis: src/adapters/storage/local-store.test.js › loading save data (rule 47) › starts with broken JSON without crashing / keeps readable values and resets invalid ones / accepts non-objects as save data; src/application/save-schema.test.js › objectSection › replaces invalid fields with the env-dependent fallback and keeps valid ones)
- [x] Gespeicherte Daten einer früheren Version mit fehlenden oder zusätzlichen Bereichen werden ohne Verlust der übrigen Werte geladen; ein später hinzukommender Bereich kann eigene Daten ergänzen, ohne bestehende zu löschen (Regel 47). (Nachweis: src/adapters/storage/local-store.test.js › loading save data (rule 47) › loads older saves with missing sections without loss; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › a new section adds data without deleting existing data; src/adapters/storage/local-store.test.js › sections extended later (rule 47) › returns new fields even without a prior update; src/application/save-schema.test.js › settings section › addSettingsFields merges new fields and keeps the existing ones / registerSection adds a section by name)
- [x] Daten, die die laufende Version nicht kennt, bleiben beim Speichern unverändert erhalten (Regel 47). (Nachweis: src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › preserves unknown sections and fields unchanged; src/application/save-schema.test.js › objectSection › keeps unknown fields; tests/smoke/pwa.spec.js › PWA › new version only after closing all tabs, save data is kept (futureArea bleibt erhalten))
- [x] Wo der Browser es anbietet, wird dauerhafter Speicher angefordert (Regel 44). (Nachweis: src/adapters/storage/local-store.js (requestPersistentStorage nutzt navigator.storage.persist); aufgerufen in src/main.js; Hinweis: kein eigener Test dafür vorhanden)
- [?] Bei jedem Pull Request laufen in GitHub Actions automatisch: Lint, Format-Check, Unit-Tests, Build und ein Browser-Smoke-Test; jeder Schritt erscheint als eigener Check. (Nicht hier prüfbar: Der erste CI-Lauf auf GitHub steht noch aus. Vorhandener Nachweis: .github/workflows/ci.yml (on: pull_request; eigene Jobs Lint, Format check, Unit tests, Build, Smoke je Browser))
- [x] Der Browser-Smoke-Test prüft auf dem gebauten Stand: die Seite lädt, das Hauptmenü erscheint, es gibt keine Fehler in der Konsole und es werden keine Bild-, Audio-, Modell- oder Schriftdateien (außer den Icons) geladen. (Nachweis: tests/smoke/app.spec.js › page loads, main menu appears, no errors, no forbidden files; .github/workflows/ci.yml (Job „Smoke“ läuft gegen den Build-Artefakt-Stand dist/ via vite preview))
- [?] Je ein absichtlicher Fehler in Lint, Format, Unit-Test, Build und Smoke-Test lässt genau den zugehörigen Check rot werden. (Nicht hier prüfbar: Das lässt sich erst im ersten CI-Lauf auf GitHub zeigen. Vorhandener Nachweis: .github/workflows/ci.yml (fünf getrennte Jobs mit eigenen Check-Namen))
- [x] Der Veröffentlichungs-Workflow startet nur nach Merge auf main, führt die Gates erneut aus und veröffentlicht nur, wenn alle grün sind; der Veröffentlichungsschritt hängt erkennbar von allen Gates ab (Nachweis am Workflow). (Nachweis: .github/workflows/deploy.yml (on: push auf main; Job gates ruft ci.yml per workflow_call auf; Job deploy hat needs: gates))
- [?] Nach dem ersten Merge auf main ist die App automatisch auf GitHub Pages veröffentlicht (Nachweis nach Merge). (Nicht hier prüfbar: Nachweis nach Merge auf GitHub. Vorhandener Nachweis: .github/workflows/deploy.yml)
- [x] Im Repo ist beschrieben, welche Checks als Pflicht-Checks für main (Branch-Schutz) eingetragen werden müssen und dass als Pages-Quelle „GitHub Actions" gewählt sein muss; beides ist eingerichtet oder als offener Einrichtungsschritt für die Repo-Inhaberin/den Repo-Inhaber dokumentiert. (Nachweis: README.md §CI/CD › Einmalige Einrichtung (Pflicht-Checks und Pages-Quelle „GitHub Actions“); scripts/github-setup.sh (--dry-run); Hinweis: noch nicht ausgeführt: als offener Einrichtungsschritt für die Repo-Inhaberin/den Repo-Inhaber dokumentiert)
- [?] Die AKs dieses Tickets sind in aktuellen Versionen von Chrome, Firefox und Edge sowie in einem WebKit-Lauf in CI erfüllt (Regel 3); auf Safari mit iPad/iPhone (Nachweis nach Merge). (Nicht hier prüfbar: Die CI-Matrix (chromium, firefox, webkit, msedge) ist vorbereitet, hat aber auf GitHub noch nicht gelaufen; Safari auf iPad/iPhone nach Merge. Vorhandener Nachweis: .github/workflows/ci.yml (matrix.browser); playwright.config.js; lokal läuft der Smoke-Test in Chromium)
- [x] Unit-Tests decken ab: Startsprache aus der Browsersprache, Laden beschädigter, alter und erweiterter Speicherdaten, Ergänzen eines neuen Bereichs ohne Verlust, Verhalten wenn Speichern nicht möglich ist. (Nachweis: src/adapters/ui/i18n.test.js › detectLang (rule 6); src/adapters/storage/local-store.test.js › loading save data (rule 47) › starts with broken JSON without crashing / loads older saves with missing sections without loss; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › a new section adds data without deleting existing data; src/adapters/storage/local-store.test.js › saving not possible (rule 46))
- [x] Die Gates lassen sich lokal mit denselben Befehlen ausführen wie in CI; die Befehle sind in der README beschrieben. (Nachweis: package.json (lint, format:check, test, build, smoke, qa); README.md §Quality Gates; .github/workflows/ci.yml (ruft dieselben npm-Skripte auf))

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (§Ziel, §Nicht-Ziele, Regeln 1, 2, 3, 5, 6, 7, 11, 12, 44–47, 53, 54; §Grenzfälle „Erster Start", „Speichern nicht möglich", „Defekte oder alte Speicherdaten", „Kein 3D", „Zwei Tabs", „Offline", „Neue Spielversion")
- Folge-Tickets: SRT-002, SRT-003, SRT-004, SRT-005, SRT-006

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
- [x] Alle Akzeptanzkriterien erfüllt, mit Nachweis (außer [?]-Zeilen: Gerät/Mensch/nach Merge; Nachweis je Zeile siehe oben)
- [x] Quality Gates grün, Review ohne blocker/major (Nachweis: lokal lint, format, 790+ Unit-Tests, Build, 64 Smoke-Tests grün; Opus-Reviews je Ticket Runde 2 ohne blocker/major; Standards-QA)
- [x] Konzept/Doku nachgezogen (Nachweis: docs/specs/springreiten-trainer/architecture.md, README.md, CLAUDE.md)
- [ ] PR/MR gemerged
