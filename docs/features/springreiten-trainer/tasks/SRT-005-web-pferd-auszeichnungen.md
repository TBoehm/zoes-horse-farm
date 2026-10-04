---
id: SRT-005
title: Mein Pferd, Auszeichnungen und Fortschritt löschen
status: in-review
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
- [x] Beim ersten Start fragt das Spiel nach dem Pferdenamen, bevor das Hauptmenü erscheint; die Frage erscheint bei jedem Start erneut, bis sie beantwortet oder übersprungen wurde (auch nach Schließen der App während der Frage und bei Spielständen früherer Versionen ohne Namen) (Regel 43). (Nachweis: tests/smoke/profile.spec.js › name question (first start) › appears before the menu, rejects invalid names and accepts a valid one; src/application/horse-service.test.js › needsNamePrompt › asks until the question was answered or skipped (rule 43) / counts an old save with a valid name but no answered flag as answered / still asks when the saved name is not valid and the flag is missing)
- [x] Die Namensfrage ist überspringbar; dann heißt das Pferd „Blitz" (Deutsch) bzw. „Flash" (Englisch); dieser Vorgabe-Name wechselt beim Sprachwechsel mit, solange kein eigener Name vergeben ist (Regel 43). (Nachweis: tests/smoke/profile.spec.js › name question (first start) › skipping gives the default name, which follows the language; tests/smoke/profile.spec.js › name question: language default name › typing the default name keeps it following the language (name stays empty); src/application/horse-service.test.js › skipName › keeps the default name but counts the question as answered; src/application/horse-service.test.js › displayName › shows the own name, else the language default name)
- [x] Leerzeichen am Anfang und Ende werden entfernt; danach muss der Name 1 bis 16 Zeichen lang sein. In der Namensfrage lässt sich eine ungültige Eingabe nicht übernehmen; in „Mein Pferd" wird eine ungültige Eingabe nicht gespeichert und es gilt der letzte gültige Name (Regel 43). (Nachweis: src/domain/horse/horse-name.test.js › Horse name (rule 43) › trims leading and trailing whitespace / rejects empty and too-long names / allows 1 to 16 characters; src/application/horse-service.test.js › answerName › accepts 1 to 16 characters only; src/application/horse-service.test.js › rename › does not save an invalid name and keeps the last valid one; tests/smoke/profile.spec.js › my horse › a changed name is saved; an invalid one keeps the last valid name)
- [x] Nach Beantworten oder Überspringen erscheint die Namensfrage bei späteren Starts nicht mehr (Regel 43). (Nachweis: src/application/horse-service.test.js › needsNamePrompt › does not ask again after an answer; src/application/horse-service.test.js › answerName › saves the trimmed name and marks the question as answered; tests/smoke/profile.spec.js › name question (first start) › appears before the menu, rejects invalid names and accepts a valid one)
- [x] Im Hauptmenü erscheinen „Mein Pferd" und „Auszeichnungen" an ihren Positionen laut Regel 53 (nach „Freier Modus", vor „Einstellungen"); die bestehenden Einträge bleiben unverändert; damit enthält das Menü alle Einträge aus Regel 53 (Regeln 53, 54). (Nachweis: tests/smoke/profile.spec.js › main menu entries › all entries of rule 53 in order, with the horse name in the greeting; tests/smoke/ride.spec.js › touch controls (SRT-002) › menus fit the low landscape screen and every button is at least 44 px high (fünf Einträge))
- [?] In „Mein Pferd" lassen sich Name, Fellfarbe (Fuchs, Brauner, Rappe, Schimmel, Schecke) und Kopfabzeichen (keins, Stern, Blesse, Schnippe) ändern; jede Fellfarbe und jedes Kopfabzeichen ist am 3D-Pferd erkennbar; beim Schimmel genügt eine realistische Darstellung, auch wenn das Kopfabzeichen kaum sichtbar ist (Regel 43). (Nicht hier prüfbar: Ob jede Fellfarbe und jedes Kopfabzeichen am 3D-Pferd gut erkennbar ist, ist ein visueller Eindruck (Ändern und Speichern ist belegt). Vorhandener Nachweis: tests/smoke/profile.spec.js › my horse › coat and marking can be changed, are saved and used when riding; src/adapters/view3d/horse/coats.test.js › coat colours and markings › offers the five coats and four head markings of rule 43 / chestnut: reddish, long hair lighter than the coat / bay: brown coat, black long hair and black lower legs / black is black, grey is light with dapples, pinto has white patches / markings are clear on dark coats and barely visible on the grey; src/application/horse-service.test.js › setAppearance › saves coat and marking and returns the saved appearance / ignores values that are not part of the offered choices)
- [x] Das gewählte Aussehen gilt im freien Modus und im Parcours (Regel 43). (Nachweis: tests/smoke/profile.spec.js › my horse › coat and marking can be changed, are saved and used when riding; src/adapters/ui/screens/ride-screen.js (horse.setAppearance(store.get("horse")) im gemeinsamen Ritt-Bildschirm beider Modi))
- [x] Der Pferdename erscheint im Hauptmenü und in der Ergebnisanzeige (Regel 43). (Nachweis: tests/smoke/profile.spec.js › main menu entries › all entries of rule 53 in order, with the horse name in the greeting; tests/smoke/courses.spec.js › results screen › shows name, time, penalty points as count x points and the buttons (English) (Pferdename „Blitz“); src/application/horse-service.test.js › displayName › shows the own name, else the language default name)
- [x] Name und Aussehen sind nach Neuladen erhalten, ohne Speichern-Button (Regeln 44, 45). (Nachweis: tests/smoke/profile.spec.js › my horse › coat and marking can be changed, are saved and used when riding / a changed name is saved; an invalid one keeps the last valid name; src/application/horse-service.test.js › rename › saves a valid name; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › saves immediately on change)
- [x] Fellfarben und Kopfabzeichen entstehen ohne geladene Bilddateien (Regel 2). (Nachweis: tests/smoke/ride.spec.js › free riding (SRT-002) › starts, renders a non-blank 3D scene without console errors or asset files (watch.forbidden = []); src/adapters/view3d/horse/coats.js (Farben und Abzeichen als Daten/Prozedur); src/adapters/view3d/horse/coats.test.js › coat colours and markings › offers the five coats and four head markings of rule 43)
### Auszeichnungen
- [x] „Erster Sprung" wird sofort vergeben, sobald mindestens 1 Sprung gezählt ist (Regeln 40, 49). (Nachweis: src/domain/progress/badges.test.js › Instant badges › first jump is awarded after the first counted jump, not before; src/application/progress-service.test.js › recordJump › counts the jump, saves it at once and awards "first jump"; src/application/ride-session.test.js › jump counting and instant badges (rules 40, 45, 49) › counts a jump at once, saves it and announces the first-jump badge)
- [x] „Springmaus" wird sofort vergeben, sobald mindestens 100 Sprünge gezählt sind (Kombination: jeder Teil zählt) (Regeln 40, 49). (Nachweis: src/domain/progress/badges.test.js › Instant badges › jump mouse at exactly 100, not at 99; src/application/progress-service.test.js › recordJump › awards the jump mouse with the 100th jump; src/application/ride-session.test.js › jump counting and instant badges (rules 40, 45, 49) › announces the jump mouse with the 100th jump; src/application/ride-session.test.js › jump counting at session level (rule 40) › counts both parts of a combination: two jumps)
- [x] Ist eine Bedingung bereits erfüllt (z. B. Spielstand einer früheren Version mit 150 Sprüngen oder 10 beendeten Ritten), wird die Auszeichnung nachgeholt: sofortige beim nächsten gezählten Sprung, die übrigen beim nächsten beendeten Ritt. Für „Fehlerfrei" genügt ein gespeicherter Parcours mit 3 Sternen; „Oxer-Profi" und „Kombi-Könner" werden nicht aus gespeicherten Daten abgeleitet (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Instant badges › catches up both from an old save with 150 jumps on the next jump; src/domain/progress/badges.test.js › Ride-end badges › catches up busy from an old save with 10 rides on the next ride / clean: a stored 3-star course is enough (catch-up) / oxer pro and combination pro are not derived from stored data)
- [x] Sofort vergebene Auszeichnungen werden kurz eingeblendet, ohne das Spiel zu pausieren (Regel 49). (Nachweis: tests/smoke/profile.spec.js › badge toast › is small, sits at the bottom centre and goes away again; tests/smoke/profile.spec.js › badge toast on a phone (touch mode) › stays in the gap between the joystick and the buttons and covers no touch control; src/application/ride-session.test.js › jump counting and instant badges (rules 40, 45, 49) › counts a jump at once, saves it and announces the first-jump badge; src/adapters/ui/screens/ride-screen.js (showBadgeToast als Overlay, ohne setPaused))
- [x] „Fehlerfrei" wird bei Rittende vergeben, wenn ein Parcours-Ritt mit 0 Fehlern (inkl. Zeitfehler) beendet wurde (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › clean: ride with 0 faults / clean: not awarded with faults and no 3-star course; src/application/progress-service.test.js › finishRide › awards end-of-ride badges with the clock time)
- [x] „Oxer-Profi" wird bei Rittende vergeben, wenn im beendeten Ritt ein Oxer gewertet (an der Reihe, in Sprungrichtung) gesprungen wurde, an dem es im ganzen Ritt weder Verweigerung noch Abwurf gab; ein Oxer als Teil einer Kombination zählt mit (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › oxer pro only from the ride result; src/domain/course/course-run.test.js › cleanOxer (rule 49) › true for a scored oxer without refusal and without knockdown / false if the oxer was refused (even if clean afterwards) / an oxer as part of the combination counts / unscored jumps over an oxer do not count)
- [x] „Kombi-Könner" wird bei Rittende vergeben, wenn im beendeten Ritt eine Zweifach-Kombination (a und b) gewertet gesprungen wurde, an der es im ganzen Ritt weder Verweigerung noch Abwurf gab (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › combination pro only from the ride result; src/domain/course/course-run.test.js › cleanCombination (rule 49) › true for a and b without refusal and knockdown / true after a fault-free turn away and a clean new attempt / false after a refusal at the combination / false after a knockdown at a)
- [x] „Alles offen" wird bei Rittende vergeben, sobald alle 5 Parcours freigeschaltet sind (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › all open at unlocked >= 5, not at 4)
- [x] „Sternenreiter" wird bei Rittende vergeben, sobald alle 5 Parcours mit 3 Sternen geschafft sind (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › star rider: all 5 courses with 3 stars, not with 4 / star rider: a 2-star course prevents awarding)
- [x] „Fleißig" wird bei Rittende vergeben, sobald 10 Parcours-Ritte beendet wurden (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Ride-end badges › busy at 10 finished rides, not at 9)
- [x] Bei Rittende vergebene Auszeichnungen erscheinen in der Ergebnisanzeige unter „Neue Auszeichnungen"; während des Ritts sofort vergebene erscheinen dort nicht erneut (Regeln 35, 49). (Nachweis: tests/smoke/courses.spec.js › results screen › shows name, time, penalty points as count x points and the buttons (English) (.new-badges); src/application/result-summary.test.js › summarizeResult › describes the awarded badges and skips unknown ids; src/domain/progress/badges.test.js › Instant badges › does not award ride-end badges; src/domain/progress/badges.test.js › Ride-end badges › does not award instant badges)
- [x] Abgebrochene Ritte vergeben keine der bei Rittende vergebenen Auszeichnungen (Regel 40). (Nachweis: src/application/ride-session.test.js › abort without credit (rules 40, 49) › awards no ride-end badges on abort, even when every condition would hold)
- [x] Jede Auszeichnung wird nur einmal vergeben; eine erneute Erfüllung zeigt nichts erneut an (Regel 49). (Nachweis: src/domain/progress/badges.test.js › Instant badges › is awarded only once: second fulfilment awards nothing and keeps the date; src/domain/progress/badges.test.js › Ride-end badges › is awarded only once: second fulfilment awards nothing; src/application/progress-service.test.js › recordJump › awards each badge only once; src/application/ride-session.test.js › jump counting and instant badges (rules 40, 45, 49) › announces a badge only once and counts every further jump)
- [x] Die Übersicht zeigt alle 8 Auszeichnungen: erhaltene hervorgehoben mit Datum, fehlende mit ihrer Bedingung (Regel 50). (Nachweis: tests/smoke/profile.spec.js › badges overview › shows all 8 badges; earned ones with a date, missing ones with their condition / oxer and combination badges say that it must happen in a course ride up to the finish; src/application/badge-overview.test.js › listBadges › lists every badge in the order of rule 49 with text keys / merges the earned date from the progress; src/domain/progress/badges.test.js › BADGES › contains the 8 badges in the order of rule 49)
- [x] Erhaltene Auszeichnungen und ihr Datum sind nach Neuladen erhalten (Regeln 44, 45). (Nachweis: src/domain/progress/progress.test.js › sanitizeProgress › validates badge dates but keeps unknown ids; src/application/progress-service.test.js › finishRide › saves the result, reports a new best and unlocks the next course; src/adapters/storage/local-store.test.js › saving save data (rules 45, 47) › saves immediately on change; Hinweis: kein eigener Neulade-Test speziell für Auszeichnungen; Neuladen des Fortschritts ist in tests/smoke/courses.spec.js › a complete ride of course 1 belegt)
- [x] Die Absprung-Hilfe hat keinen Einfluss auf die Vergabe (Regel 42). (Nachweis: src/domain/progress/badges.js und src/application/progress-service.js haben keinen Bezug zur Absprung-Hilfe; sie ist nur Ansicht (src/application/ride-session.js); Hinweis: kein eigener Vergleichstest „mit/ohne Hilfe“)
### Fortschritt löschen
- [x] In den Einstellungen gibt es „Fortschritt löschen", aber nur wenn sie aus dem Hauptmenü geöffnet wurden; in den Einstellungen aus dem Pausemenü fehlt der Button. Es wirkt erst nach Bestätigung der Sicherheitsabfrage; „Abbrechen" ändert nichts (Regel 48). (Nachweis: tests/smoke/profile.spec.js › delete progress › only in the settings of the main menu, only after confirming, keeps settings and horse / the settings from the pause menu have no delete button / the confirmation is fully visible on a low phone screen (568x320); tests/smoke/ride.spec.js › free riding (SRT-002) › pause menu: restart puts the horse back, "to menu" leaves, settings keep the pause (kein Löschen-Button))
- [x] Nach dem Löschen ist nur Parcours 1 offen, es gibt keine Bestleistungen, Sterne, Auszeichnungen und die Zähler (Sprünge, beendete Ritte) stehen auf 0 (Regel 48). (Nachweis: src/application/progress-service.test.js › resetProgress › deletes only the progress fields and leaves other sections alone (rule 48); src/domain/progress/progress.test.js › resetProgress › resets only the fields from rule 48 / creates no shared state with the defaults; tests/smoke/profile.spec.js › delete progress › only in the settings of the main menu, only after confirming, keeps settings and horse)
- [x] Zurückgesetzt werden nur die in Regel 48 genannten Fortschrittsdaten (Freischaltung, Bestleistungen, Sterne, Auszeichnungen, Zähler). Alle Einstellungen (auch später hinzukommende), das Pferd und alle übrigen gespeicherten Daten bleiben unverändert; die Namensfrage erscheint nicht erneut (Regeln 47, 48). (Nachweis: src/application/progress-service.test.js › resetProgress › deletes only the progress fields and leaves other sections alone (rule 48); src/application/settings-schema.test.js › sound settings fields › survive "delete progress" (rule 48 resets progress only); tests/smoke/audio.spec.js › sound settings and deleting progress › all four sound fields are kept after "Delete progress"; tests/smoke/profile.spec.js › delete progress › only in the settings of the main menu, only after confirming, keeps settings and horse)
### Sprache
- [?] Alle neuen Texte (Namensfrage, Mein Pferd, Namen und Bedingungen der Auszeichnungen, Einblendung, Sicherheitsabfrage) liegen auf Deutsch und Englisch vor und sind kurz und für eine 3. Klasse lesbar (Regel 6). (Nicht hier prüfbar: Ob die Texte kurz und für eine 3. Klasse gut lesbar sind, kann nur ein Mensch beurteilen (Deutsch und Englisch vollständig ist belegt). Vorhandener Nachweis: src/adapters/ui/i18n/strings.test.js › texts (rule 6) › have the same keys in German and English / are not empty and have the same placeholders; src/adapters/ui/i18n/badges.test.js › badge texts › de and en have the same keys / contain all keys from BADGES, non-empty / names match the concept / placeholders match in both languages)
### Tests
- [x] Unit-Tests decken die Bedingungen aller 8 Auszeichnungen, Einmaligkeit, Nicht-Vergabe bei Abbruch und den Umfang von „Fortschritt löschen" ab. (Nachweis: src/domain/progress/badges.test.js › Instant badges; src/domain/progress/badges.test.js › Ride-end badges; src/application/progress-service.test.js › recordJump › awards each badge only once; src/application/ride-session.test.js › abort without credit (rules 40, 49) › awards no ride-end badges on abort, even when every condition would hold; src/application/progress-service.test.js › resetProgress › deletes only the progress fields and leaves other sections alone (rule 48); src/domain/progress/progress.test.js › resetProgress › resets only the fields from rule 48)

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
- [x] Alle Akzeptanzkriterien erfüllt, mit Nachweis (außer [?]-Zeilen: Gerät/Mensch/nach Merge; Nachweis je Zeile siehe oben)
- [x] Quality Gates grün, Review ohne blocker/major (Nachweis: lokal lint, format, 790+ Unit-Tests, Build, 64 Smoke-Tests grün; Opus-Reviews je Ticket Runde 2 ohne blocker/major; Standards-QA)
- [x] Konzept/Doku nachgezogen (Nachweis: docs/specs/springreiten-trainer/architecture.md, README.md, CLAUDE.md)
- [ ] PR/MR gemerged
