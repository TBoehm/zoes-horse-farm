---
id: SRT-001
title: Grundgerüst – App-Rahmen, Menü, Sprachen, Speicher, Offline, CI/CD
status: ready-for-dev
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
- [ ] Der gebaute Stand läuft als rein statische Seite; im Netzwerk-Tab gibt es keine Anfragen an eine eigene Server-Logik (Regel 1).
- [ ] Die App ist unter der GitHub-Pages-Adresse erreichbar (Regel 1) (Nachweis nach Merge).
- [ ] Beim Laden der App werden keine Bild-, Audio-, 3D-Modell- oder Schriftdateien angefragt; einzige Ausnahme sind App-Icon und Browser-Tab-Icon als selbst erstellte Vektorgrafik sowie daraus beim Build erzeugte Rastergrafiken (Regel 2).
- [ ] Nach einmaligem vollständigen Laden startet die App mit abgeschaltetem Netzwerk und das Menü ist bedienbar (Regel 5).
- [ ] Auf dem Desktop lässt sich der lokal gebaute Stand in Chrome/Edge installieren und zeigt das eigene Icon (Regeln 2, 5).
- [ ] Auf Tablet/Handy kann die App zum Startbildschirm hinzugefügt werden und zeigt dort das eigene Icon (Regeln 2, 5) (Nachweis nach Merge).
- [ ] Wird eine neue Version bereitgestellt, wird sie im Hintergrund geladen und gilt erst, nachdem alle Tabs bzw. die Startbildschirm-App geschlossen und neu geöffnet wurden; Neuladen eines offenen Tabs wechselt die Version nicht; es erscheint kein Hinweis und ein laufendes Spiel wird nicht unterbrochen; der gespeicherte Stand bleibt erhalten (Regeln 5, 47). Nachweis am lokal gebauten Stand mit zwei Versionen.
- [ ] Beim ersten Start ist die Sprache Deutsch, wenn die Browsersprache Deutsch ist, sonst Englisch (Regel 6).
- [ ] In den Einstellungen lässt sich die Sprache umschalten; alle sichtbaren Texte wechseln sofort; die Wahl bleibt nach Neuladen erhalten (Regeln 6, 45).
- [ ] Jeder sichtbare Text liegt auf Deutsch und Englisch vor; es erscheinen keine Platzhalter-Schlüssel (Regel 6).
- [ ] In einem Browser ohne 3D-Unterstützung (z. B. WebGL deaktiviert) erscheint beim App-Start statt der ganzen App (auch statt der Menüs) der kindgerechte Hinweis in der passenden Sprache, keine leere Seite (Regel 7).
- [ ] Auf einem reinen Touch-Gerät ist der Touch-Modus von Anfang an aktiv, auf einem Gerät ohne Touchscreen nie; erkennbar daran, dass im Hochformat der Dreh-Hinweis erscheint bzw. ausbleibt (Regel 11).
- [ ] Auf einem Gerät mit Tastatur und Touchscreen startet die App ohne Touch-Modus; eine Berührung schaltet ihn an, eine Spieltaste (W, A, S, D, Shift, Space, Esc, C) schaltet ihn aus; erkennbar am Dreh-Hinweis im Hochformat (Regel 11).
- [ ] Solange der Touch-Modus aktiv ist, erscheint im Hochformat in jeder Ansicht (auch Menüs) der Hinweis „Gerät drehen"; im Querformat verschwindet er (Regel 12).
- [ ] Ohne Touch-Modus erscheint im Hochformat kein Dreh-Hinweis (Regel 12).
- [ ] Das Hauptmenü zeigt nur Einträge, deren Funktion vorhanden ist (in diesem Ticket „Einstellungen"); die Ergänzbarkeit ohne Änderung bestehender Einträge wird mit SRT-002 (Eintrag „Freier Modus") nachgewiesen (Regeln 53, 54).
- [ ] Eine geänderte Einstellung ist nach Neuladen der Seite noch gesetzt, ohne dass ein Speichern-Button nötig ist (Regel 45).
- [ ] Ist Speichern nicht möglich (z. B. privater Modus mit blockiertem Speicher, Speicher voll), bleibt die App bedienbar und zeigt einmal je Sitzung einen Hinweis, dass der Fortschritt nicht gespeichert wird; Neuladen zeigt ihn nicht erneut, erst nach Schließen und Neuöffnen von Tab bzw. App (Regel 46).
- [ ] Mit absichtlich beschädigten gespeicherten Daten startet die App ohne Absturz, übernimmt lesbare Werte und setzt den Rest auf Anfangswerte (Regel 47).
- [ ] Gespeicherte Daten einer früheren Version mit fehlenden oder zusätzlichen Bereichen werden ohne Verlust der übrigen Werte geladen; ein später hinzukommender Bereich kann eigene Daten ergänzen, ohne bestehende zu löschen (Regel 47).
- [ ] Daten, die die laufende Version nicht kennt, bleiben beim Speichern unverändert erhalten (Regel 47).
- [ ] Wo der Browser es anbietet, wird dauerhafter Speicher angefordert (Regel 44).
- [ ] Bei jedem Pull Request laufen in GitHub Actions automatisch: Lint, Format-Check, Unit-Tests, Build und ein Browser-Smoke-Test; jeder Schritt erscheint als eigener Check.
- [ ] Der Browser-Smoke-Test prüft auf dem gebauten Stand: die Seite lädt, das Hauptmenü erscheint, es gibt keine Fehler in der Konsole und es werden keine Bild-, Audio-, Modell- oder Schriftdateien (außer den Icons) geladen.
- [ ] Je ein absichtlicher Fehler in Lint, Format, Unit-Test, Build und Smoke-Test lässt genau den zugehörigen Check rot werden.
- [ ] Der Veröffentlichungs-Workflow startet nur nach Merge auf main, führt die Gates erneut aus und veröffentlicht nur, wenn alle grün sind; der Veröffentlichungsschritt hängt erkennbar von allen Gates ab (Nachweis am Workflow).
- [ ] Nach dem ersten Merge auf main ist die App automatisch auf GitHub Pages veröffentlicht (Nachweis nach Merge).
- [ ] Im Repo ist beschrieben, welche Checks als Pflicht-Checks für main (Branch-Schutz) eingetragen werden müssen und dass als Pages-Quelle „GitHub Actions" gewählt sein muss; beides ist eingerichtet oder als offener Einrichtungsschritt für die Repo-Inhaberin/den Repo-Inhaber dokumentiert.
- [ ] Die AKs dieses Tickets sind in aktuellen Versionen von Chrome, Firefox und Edge sowie in einem WebKit-Lauf in CI erfüllt (Regel 3); auf Safari mit iPad/iPhone (Nachweis nach Merge).
- [ ] Unit-Tests decken ab: Startsprache aus der Browsersprache, Laden beschädigter, alter und erweiterter Speicherdaten, Ergänzen eines neuen Bereichs ohne Verlust, Verhalten wenn Speichern nicht möglich ist.
- [ ] Die Gates lassen sich lokal mit denselben Befehlen ausführen wie in CI; die Befehle sind in der README beschrieben.

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
- [ ] Alle Akzeptanzkriterien erfüllt, mit Nachweis
- [ ] Quality Gates grün, Review ohne blocker/major
- [ ] Konzept/Doku nachgezogen
- [ ] PR/MR gemerged
