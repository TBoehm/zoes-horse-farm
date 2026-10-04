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
| [SRT-007](SRT-007-web-spieltest-feedback.md) | Spieltest-Feedback – Rückwärtsrichten, Lenkung, Grafikwechsel, fps-Anzeige | in-progress | web | p1 | M | SRT-002, SRT-003 |

Empfohlene Reihenfolge: SRT-001 → SRT-002 → SRT-003 → SRT-004 → SRT-005 → SRT-006.
Umsetzung je Ticket mit `/implement-feature <ID>`.
