---
id: SRT-006
title: Klang – synthetisierte Effekte und Menü-Melodie
status: in-review
platform: web
feature: docs/features/springreiten-trainer/concept.md
priority: p2
depends_on: [SRT-002, SRT-004, SRT-005]
effort: S
---
## Kontext
Betroffene Produkt-Plattformen: web (einzige Plattform; Ausprägungen nach Eingabeart Tastatur/Touch).

Ergänzt den Ton. Alle Klänge entstehen zur Laufzeit im Spiel (keine Audiodateien). Hufschlag braucht
die Gangarten aus SRT-002, Absprung/Landung/fallende Stange die Sprungmechanik aus SRT-003, Start-
und Zielsignal sowie Vorstart-Karte und Ergebnisanzeige den Parcours aus SRT-004.

## Ziel
Das Spiel klingt lebendig: Hufschlag im Rhythmus der Gangart, hörbare Sprünge und fallende Stangen,
Start- und Zielsignal im Parcours und eine einfache Melodie in den Menüs, alles getrennt regelbar
und stumm schaltbar.

## Scope
- In:
  - Effekte: Hufschlag je Gangart, Absprung, Landung, fallende Stange, Startsignal bei „Los",
    Zielsignal (Regel 51).
  - Menü-Melodie in Hauptmenü und Untermenüs, auf der Vorstart-Karte und in der Ergebnisanzeige;
    keine Musik im Vorstart, im Ritt, im freien Modus und im Pausemenü (Regel 51).
  - Einstellungen: Lautstärke Musik und Effekte getrennt, jeweils stumm schaltbar, gespeichert
    (Regeln 44, 45, 52).
  - Ton erst nach der ersten Interaktion (Regel 52).
- Out:
  - Klänge für Verweigerung, Hopser, Auszeichnungen, Buttons (nicht im Konzept gefordert).
  - Musik während des Reitens (Konzept: keine).

## Design (SSoT)
Kein UI außer zwei Einstellungen: „Musik" und „Effekte", je ein Lautstärkeregler mit Stumm-Schalter
(im Touch-Modus ≥ 44×44 px).

## Akzeptanzkriterien
- [x] Beim Laden der App wird keine Audiodatei angefragt; alle Klänge entstehen zur Laufzeit (Regel 2). (Nachweis: tests/smoke/audio.spec.js › audio › nothing starts before the first interaction, then the sound is unlocked (watch.forbidden = []); tests/smoke/app.spec.js › page loads, main menu appears, no errors, no forbidden files; src/adapters/audio/dsp.js, sfx.js, music.js (Synthese zur Laufzeit))
- [x] Vor der ersten Interaktion (Klick, Berührung, Taste) ist kein Ton zu hören; danach startet der Ton ohne weiteren Schritt (Regel 52). (Nachweis: src/adapters/audio/audio.test.js › before unlock › creates no AudioContext and silently ignores all calls / remembers the music request and starts the melody after unlock; src/adapters/audio/audio.test.js › installUnlock › registers the gesture events and removes them after unlocking; tests/smoke/audio.spec.js › audio › nothing starts before the first interaction, then the sound is unlocked / the audio context really runs after the first gesture; Hinweis: geprüft über den Audio-Zustand, nicht per Gehör)
- [?] Der Hufschlag ist in Schritt, Trab und Galopp hörbar unterschiedlich (Rhythmus/Tempo) und folgt dem Gangartwechsel; im Halt ist kein Hufschlag zu hören (Regel 51). (Nicht hier prüfbar: Ob der Hufschlag auf echten Lautsprechern hörbar unterschiedlich klingt, kann nur ein Mensch beurteilen. Vorhandener Nachweis: src/adapters/view3d/horse/motion.test.js › footfall › never fires at halt / walk: four-beat LH → LF → RH → RF, ¼ cycle apart / trot: diagonal pairs land together / canter (left lead): RH → LH+RF → LF, then suspension; src/adapters/audio/sfx.test.js › mid-range clop for small speakers › hoof (${gait}) has a clop between 1 and 2.5 kHz that is clearly audible; src/adapters/audio/audio.test.js › effects › every effect and every gait creates nodes, an unknown gait does not)
- [?] Absprung und Landung sind jeweils hörbar (Regel 51). (Nicht hier prüfbar: Die Hörbarkeit auf echten Lautsprechern kann nur ein Mensch beurteilen. Vorhandener Nachweis: src/application/ride-sounds.test.js › sound mapper › maps takeoff, landing and rail-down events to sound commands, in event order; src/application/ride-session.test.js › jump counting and instant badges (rules 40, 45, 49) › asks for takeoff and landing sounds; src/adapters/audio/sfx.test.js › mid-range clop for small speakers › landing has a clop for each of its two hoof impacts)
- [?] Eine fallende Stange ist hörbar (Regel 51). (Nicht hier prüfbar: Die Hörbarkeit auf echten Lautsprechern kann nur ein Mensch beurteilen. Vorhandener Nachweis: src/application/ride-sounds.test.js › sound mapper › plays at most one rail-down sound per element per jump; src/application/ride-session.test.js › knockdown, feedback and rebuild timers › plays the rail-down sound and gives knockdown feedback)
- [x] Bei „Los" auf der Vorstart-Karte ertönt das Startsignal (Regeln 26, 51). (Nachweis: tests/smoke/audio.spec.js › start signal and effects › "Go" plays the start signal once; start again and the pause menu do not play it; tests/smoke/courses.spec.js › a complete ride of course 1 › time, results, stars, unlocking, badge and saved progress (startSignal: 1); Hinweis: gezählt über den Audio-Zustand (sfxCounts), nicht per Gehör)
- [x] Beim Überqueren der Ziellinie am Ende eines Ritts ertönt das Zielsignal; wird die Ziellinie überquert, bevor alle Hindernisse gesprungen sind, ertönt kein Zielsignal (Regeln 30, 51). (Nachweis: src/application/ride-session.test.js › finish signal sound › is commanded right before the finished command on a real finish / is not commanded when the finish line is crossed too early (obstacles missing); tests/smoke/courses.spec.js › a complete ride of course 1 › time, results, stars, unlocking, badge and saved progress (finishSignal: 1); Hinweis: gezählt über den Audio-Zustand (sfxCounts), nicht per Gehör)
- [x] Die Melodie läuft in Hauptmenü, Untermenüs (Parcours-Auswahl, Mein Pferd, Auszeichnungen, Einstellungen aus dem Hauptmenü), auf der Vorstart-Karte und in der Ergebnisanzeige (Regel 51). (Nachweis: tests/smoke/audio.spec.js › audio › music in the menus, none in the free ride, the pause menu or its settings / music is also wanted on the pre-start card, none in the pre-start ride; src/adapters/ui/music-gate.test.js › music gate (music per screen, optionally delayed) › passes the music wish of a screen on immediately / starts the music after the delay of the screen, not before; src/adapters/ui/screens/courses/screens.js (Ergebnisanzeige: music: true, verzögert), src/adapters/ui/screens/profile/*.js (Mein Pferd, Auszeichnungen: music: true); Hinweis: Ergebnisanzeige und Untermenüs Mein Pferd/Auszeichnungen nur im Code, nicht im Smoke-Test geprüft)
- [x] Im Vorstart, während eines Ritts, im freien Modus und im Pausemenü läuft keine Musik, auch nicht in den Einstellungen, wenn sie aus dem Pausemenü geöffnet wurden (Regel 51). (Nachweis: tests/smoke/audio.spec.js › audio › music in the menus, none in the free ride, the pause menu or its settings / music is also wanted on the pre-start card, none in the pre-start ride; src/adapters/ui/settings-screen.js (music: !fromPause))
- [x] Ist die App im Hintergrund (Tab gewechselt, Fenster minimiert), ist kein Ton zu hören; zurück im Vordergrund läuft die Musik dort weiter, wo sie laufen soll (Regel 51). (Nachweis: tests/smoke/audio.spec.js › audio › a tab in the background plays no music; back in the menu it plays again; src/adapters/audio/audio.test.js › music should run › background: music stops, context is suspended; on return it runs again / a request made while in the background is applied on return / unlock in the background starts no sound)
- [x] „Neu starten" aus dem Pausemenü löst kein Startsignal aus; „Nochmal" führt zur Vorstart-Karte, das Signal ertönt dort erst bei „Los" (Regeln 26, 35). (Nachweis: tests/smoke/audio.spec.js › start signal and effects › "Go" plays the start signal once; start again and the pause menu do not play it; tests/smoke/audio.spec.js › start signal and effects › "Again" on the results screen opens the pre-start card; the signal only comes with "Go")
- [x] Beim ersten Start sind Musik und Effekte an, mit mittlerer Lautstärke (Regel 52). (Nachweis: src/application/settings-schema.test.js › sound settings fields › default to half volume, not muted; tests/smoke/audio.spec.js › audio › volume and mute of both channels are saved separately and survive a reload (erster Start: 0,5 / nicht stumm); src/adapters/audio/logic.test.js › volume mapping › normalizeSettings sets medium volume and both channels on)
- [x] In den Einstellungen lassen sich Musik und Effekte getrennt in der Lautstärke regeln und je Kanal mit eigenem Schalter stumm schalten; nach dem Aufheben von „Stumm" gilt die vorher eingestellte Lautstärke; Lautstärken und Stumm-Zustände gelten sofort und nach Neuladen (Regeln 44, 45, 52). (Nachweis: tests/smoke/audio.spec.js › audio › volume and mute of both channels are saved separately and survive a reload / the volume sliders and switches are large enough for touch; src/application/settings-service.test.js › sound › sets the volume per channel / mutes and unmutes per channel; src/adapters/audio/audio.test.js › volume and mute › setVolumes takes effect immediately and muting keeps the volume / muting the music stops the melody, unmuting starts it again)
- [x] Lautstärke und Stumm je Kanal bleiben nach „Fortschritt löschen" erhalten (Regel 48). (Nachweis: tests/smoke/audio.spec.js › sound settings and deleting progress › all four sound fields are kept after "Delete progress"; src/application/settings-schema.test.js › sound settings fields › survive "delete progress" (rule 48 resets progress only))
- [x] Ist ein Kanal stumm, sind dessen Klänge nicht zu hören; der andere Kanal bleibt unverändert (Regel 52). (Nachweis: src/adapters/audio/audio.test.js › effects › muted effects channel creates no nodes, music is untouched; src/adapters/audio/logic.test.js › volume mapping › muting sets the channel to 0 without changing the volume; src/adapters/audio/logic.test.js › shouldMusicRun › runs only when wanted, visible and not muted)
- [x] Während der Pause sind keine Effekte zu hören (Regel 38: Szene steht). (Nachweis: src/adapters/audio/audio.test.js › effects › pause: new effects are ignored, running ones are cut; tests/smoke/audio.spec.js › start signal and effects › no effects while the game is paused)

## Links
- Konzept: docs/features/springreiten-trainer/concept.md (Regeln 2, 26, 30, 35, 38, 44, 45, 48, 51, 52)
- Voraussetzungen: SRT-002 (Gangarten), SRT-004 (Vorstart, Ziel, Ergebnis; enthält SRT-003), SRT-005 (Untermenüs Mein Pferd, Auszeichnungen)

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
