# Springreiten-Trainer – Tickets

Konzept: [../concept.md](../concept.md)

| ID | Titel | Status | Plattform | Priorität | Aufwand | hängt ab von |
| --- | --- | --- | --- | --- | --- | --- |
| [SRT-001](SRT-001-web-grundgeruest.md) | Grundgerüst – App-Rahmen, Menü, Sprachen, Speicher, Offline, CI/CD | in-review | web | p1 | M | – |
| [SRT-002](SRT-002-web-reiten.md) | Reitplatz, Pferd und Reiten | in-review | web | p1 | L | SRT-001 |
| [SRT-003](SRT-003-web-springen-freier-modus.md) | Springen und freier Modus | in-review | web | p1 | L | SRT-002 |
| [SRT-004](SRT-004-web-parcours.md) | Gewertete Parcours | in-review | web | p1 | L | SRT-003 |
| [SRT-005](SRT-005-web-pferd-auszeichnungen.md) | Mein Pferd, Auszeichnungen, Fortschritt löschen | in-review | web | p2 | M | SRT-001, SRT-002, SRT-004 |
| [SRT-006](SRT-006-web-klang.md) | Klang | in-review | web | p2 | S | SRT-002, SRT-004, SRT-005 |
| [SRT-007](SRT-007-web-spieltest-feedback.md) | Spieltest-Feedback – Rückwärtsrichten, Lenkung, Grafikwechsel, fps-Anzeige | in-review | web | p1 | M | SRT-002, SRT-003 |
| [SRT-008](SRT-008-web-grafik-verschwindet.md) | 3D-Bild verschwindet nach wenigen Sekunden (Android-Tablet) | in-review | web | p1 | M | SRT-007 |
| [SRT-009](SRT-009-web-lenkung-wendiger.md) | Lenkung wendiger – Kurven etwa 50 % enger, schneller einlenken | in-review | web | p1 | S | SRT-007 |
| [SRT-010](SRT-010-web-button-farben.md) | Button-Farben – Grün für Weiter, Rot nur für Löschen | in-review | web | p1 | S | SRT-001 |
| [SRT-011](SRT-011-web-details-animationen.md) | Mehr Details und geschmeidigere Animationen – Umgebung, Pferd, Reiter | in-review | web | p1 | L | SRT-002, SRT-003, SRT-008 |
| [SRT-012](SRT-012-web-bedienungs-tipps.md) | Bedienungs-Tipps – beim ersten Start und jederzeit wieder aufrufbar | in-review | web | p1 | M | SRT-001, SRT-007 |
| [SRT-013](SRT-013-web-mittel-absturz.md) | „Mittel" stürzt nach ca. 5 s ab – leichter, sparsames Herunterstufen, Absturz-Erkennung | in-review | web | p1 | M | SRT-008, SRT-011 |
| [SRT-014](SRT-014-web-automatik-hocharbeiten.md) | Automatik startet auf „Niedrig" und stuft dynamisch hoch | in-review | web | p1 | M | SRT-008, SRT-013 |
| [SRT-015](SRT-015-web-versionsanzeige.md) | Versionsanzeige in den Einstellungen und in der Debug-Anzeige | ready-for-dev | web | p2 | S | SRT-001, SRT-008 |

Empfohlene Reihenfolge: SRT-001 → SRT-002 → SRT-003 → SRT-004 → SRT-005 → SRT-006.
Umsetzung je Ticket mit `/implement-feature <ID>`.
