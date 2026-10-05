# Springreiten-Trainer (Konzept)

Status: freigegeben · Stand: 2026-10-03 · Plattform: Web (statisch, Browser auf PC, Tablet, Handy)

## Ziel

Ein Kind im Alter von etwa 9 Jahren (3. Klasse) übt im Browser Springreiten auf einem Reitplatz in
3D: Tempo regeln, Hindernisse sauber anreiten, im richtigen Moment abspringen. Gewertete Parcours
mit kindgerechten Turnierregeln geben ein Ziel, ein freier Modus erlaubt Üben ohne Wertung. Der
Fortschritt bleibt im Browser gespeichert, das Spiel funktioniert nach dem ersten Laden auch offline.

Der Springreiten-Trainer ist das **erste Feature** des Spiels „Zoe's Horse Farm". Weitere Bereiche
(z. B. Pferdepflege, Zucht) sollen später dazukommen, sind aber nicht Teil dieses Konzepts. Das
Konzept hält nur fest, was diese Erweiterbarkeit vom ersten Feature verlangt (Regeln 47, 54).

## Begriffe

| Begriff | Bedeutung |
| --- | --- |
| Reitplatz | Eingezäunte Sandfläche, auf der alle Ritte stattfinden. Das Pferd kann den Platz nicht verlassen. |
| Hindernis | Sprung auf dem Platz. Arten: **Kreuz**, **Steilsprung**, **Oxer**, **Zweifach-Kombination** (zwei Sprünge a und b mit kurzem Abstand, zählen als **ein** Hindernis mit einer Nummer). |
| Parcours | Feste Folge nummerierter Hindernisse mit Start- und Ziellinie und erlaubter Zeit. „Anzahl Hindernisse" = Anzahl Nummern. |
| Ritt | Ein Durchgang durch einen Parcours vom Überqueren der Startlinie bis zur Ziellinie (beendet) oder bis zum Abbruch. |
| Vorstart | Phase zwischen „Los" auf der Vorstart-Karte und dem Überqueren der Startlinie. |
| Freier Modus | Reitplatz mit fester Übungsaufstellung, ohne Reihenfolge, Zeit und Wertung. |
| Gangart | Halt, Schritt, Trab, Galopp. |
| Anreiten | Das Pferd ist näher als der Anreitabstand vor einem Hindernis, und sein aktueller Kurs würde das Hindernis zwischen den Ständern treffen. Ein Kurs, der am Hindernis vorbeiführt, ist kein Anreiten. |
| Sprungrichtung | Im Parcours hat jedes Hindernis eine Sprungrichtung, erkennbar an den Fahnen (rot rechts, weiß links, wie im Turnier). Im freien Modus sind beide Richtungen gültig. |
| Vor (einem Hindernis) | Die Seite, von der das Pferd gerade kommt, unabhängig von der Sprungrichtung. Ein Hindernis ist also von beiden Seiten springbar; im Parcours zählt nur ein Sprung in Sprungrichtung (Regel 29). |
| Touch-Modus | Zustand, in dem die Touch-Bedienung gilt: auf reinen Touch-Geräten immer aktiv; auf Geräten mit Tastatur und Touchscreen per Berührung an- und per Spieltaste ausgeschaltet (Regel 11). |
| Reichweite | Bereich vor einem Hindernis, in dem Space einen Sprung über dieses Hindernis auslöst. Größer als die Absprungzone. |
| Absprungzone | Teil der Reichweite, aus dem ein sauberer Sprung gelingt; Lage und Tiefe hängen von Hindernisart, Höhe und Tempo ab. |
| Letzter Absprungpunkt | Dichtester Punkt vor dem Hindernis, an dem noch abgesprungen werden kann. Er liegt hinter der Absprungzone (zu dicht, Regel 19); ein Selbst-Sprung (Regel 20) liegt daher nie im sicheren Kern (Regel 18). |
| Abwurf | Beim Sprung fällt eine Stange des Hindernisses. |
| Verweigerung | Das Pferd springt ein angerittenes Hindernis nicht (bleibt stehen oder läuft vorbei). |
| Fehler(punkte) | Summe aus Abwurf-, Verweigerungs- und Zeitfehlern eines Ritts. |
| Absprung-Hilfe | Optionale Markierung der Absprungzone auf dem Boden. |
| Sterne | Bewertung eines Parcours mit 1 bis 3 Sternen. |
| Auszeichnung | Freischaltbare Belohnung für Meilensteine (Badge). |
| Kopfabzeichen | Weiße Zeichnung am Pferdekopf (Stern, Blesse, Schnippe), rein optisch. |
| Bestleistung | Bester beendeter Ritt eines Parcours: zuerst wenigste Fehler, bei Gleichstand kürzeste Zeit. |
| Ritt-Zeit | Gemessen, angezeigt und verglichen in Hundertstelsekunden (z. B. 48,27 s). |
| Spielwert | Zahl, die das Spielgefühl einstellt (Anreitabstand, Reichweite, Toleranzen, Risiko-Kurven). Spielwerte werden in der Umsetzung abgestimmt, innerhalb der Grenzen, die die Regeln setzen. |

## Nicht-Ziele

- Keine Bild-, Audio-, 3D-Modell- oder Schriftdateien; einzige Ausnahme sind App-Icon und Browser-Tab-Icon (Regel 2).
- Keine Online-Funktionen: kein Konto, kein Server, keine Bestenliste, kein Multiplayer, kein Sync zwischen Geräten.
- Kein Export/Import des Spielstands.
- Keine mehreren Profile; ein Spielstand je Browser.
- Keine Pferde mit unterschiedlichen Eigenschaften; Anpassung ist rein optisch. Der Reiter ist nicht anpassbar.
- Kein Parcours-Editor; der freie Modus hat eine feste Aufstellung.
- Kein Sturz und kein Ausschluss.
- Keine weiteren Hindernisarten (Mauer, Wassergraben) und kein Gelände außerhalb des Reitplatzes in dieser Version.
- Keine Gamepad-Steuerung in dieser Version.
- Keine Hochformat-Darstellung auf Touch-Geräten.
- Keine weiteren Spielbereiche wie Pferdepflege, Stall, Zucht, Füttern, mehrere eigene Pferde oder
  Spielwährung; sie werden in eigenen Konzepten geplant.

## Regeln

### Darstellung und Technik-Leitplanken

1. Das Spiel MUSS als statische Seite (GitHub Pages) ohne Server-Logik laufen.
2. Alle Grafik (Pferd, Reiter, Reitplatz, Hindernisse, Umgebung, Oberflächen) und alle Klänge MÜSSEN
   zur Laufzeit im Spiel erzeugt werden; es werden keine Bild-, Audio-, Modell- oder Schriftdateien
   geladen. Schrift ist Systemschrift. Einzige Ausnahme: App-Icon und Browser-Tab-Icon dürfen als im
   Projekt selbst erstellte Vektorgrafik-Datei vorliegen (kein Foto, kein fremdes Asset); daraus
   beim Build erzeugte Rastergrafiken (z. B. für Startbildschirm-Icons auf iPhone/iPad) sind erlaubt.
3. Der Look SOLL möglichst realistisch sein, solange die Ziel-Bildrate gehalten wird. Ziel-Bildrate
   ist **60 fps** auf der automatisch gewählten Grafikstufe; Stufe „Niedrig" SOLL 60 fps auch auf
   schwachen Geräten halten (keine feste Geräte-Untergrenze). Abnahmegrenze: Auf der automatisch
   gewählten Stufe MÜSSEN im Mittel mindestens 50 fps erreicht werden. Zielgeräte sind PC, Tablet und
   Handy mit aktuellen Versionen von Chrome, Safari (inklusive iPad/iPhone), Firefox und Edge.
   Detailreichtum (Spieltest 2026-10-04: „viel zu wenig Details"), gestuft nach Grafikstufe; „Niedrig"
   DARF dafür nicht langsamer werden als bisher, „Mittel" nur geringfügig (Regel 4), und die
   Speicher-Abschätzung (Regel 4) MUSS die neuen Details mitzählen:
   - Umgebung: Blumen auf den Wiesen, Bäume, Büsche und Gras bewegen sich leicht im Wind, Vögel am
     Himmel, Schmetterlinge über den Blumen, Schmuck am Reitplatz (Blumenkästen an den Hindernissen,
     Wimpelketten), eine Koppel mit grasenden Pferden; Sand staubt unter den Hufen.
   - Pferd: Mähne und Schweif schwingen der Bewegung nach, das Pferd blinzelt, Bandagen an den
     Beinen, Nüstern bewegen sich mit dem Atem; im Stand gelegentlich Kopfschütteln oder Hufscharren.
   - Reiter: Gesicht (Augen, Nase, Mund), Kinnriemen am Helm, Zopf schwingt nach, der Kopf schaut in
     die Kurve.
4. Das Spiel MUSS drei Grafikstufen (Niedrig, Mittel, Hoch) und die Einstellung „Automatisch" haben.
   - WENN das Spiel zum ersten Mal startet, MUSS „Automatisch" aktiv sein und mit Niedrig beginnen
     (Spieltest 2026-10-04: lieber sicher starten und sich hocharbeiten).
   - SOLANGE „Automatisch" aktiv ist: WENN die durchschnittliche Bildrate über 5 Sekunden unter
     50 fps liegt, MUSS das Spiel eine Stufe heruntergehen (nicht unter Niedrig); nach einer
     Anpassung MUSS es mindestens 10 Sekunden bis zur nächsten Anpassung warten. Gemessen wird nur,
     während geritten wird (Vorstart, Ritt, freier Modus), nicht in Pause, Menüs oder bei
     verstecktem/minimiertem Fenster und nicht in den ersten 3 Sekunden danach.
   - SOLANGE „Automatisch" aktiv ist, MUSS das Spiel dynamisch während des Reitens hochstufen, wenn
     genug Reserve da ist: WENN die Bildrate über 10 Sekunden im Mittel mindestens 57 fps beträgt
     und kaum langsame Bilder enthält (gemessen wie oben), MUSS es einen kleinen Schritt Richtung
     nächster Stufe gehen (nicht über Hoch); nach jeder Anpassung (hoch oder runter) MUSS es
     mindestens 20 Sekunden warten, bevor es wieder hochstuft. Ein Schritt nach oben DARF nicht
     während eines Sprungs (Absprung, Flug, Landung) beginnen. Nie hochgestuft wird auf eine Stufe,
     (a) bei der auf diesem Gerät schon einmal die 3D-Darstellung verloren ging oder das Spiel
     abstürzte (gesperrt, gespeichert, bis „Automatisch" neu gewählt wird), (b) deren geschätzter
     Grafikspeicher nicht in die vorsichtige Grenze für das Gerät passt, (c) von der die Automatik
     im laufenden Spiel schon einmal wegen zu niedriger Bildrate heruntergestuft hat.
   - Die automatisch erreichte Stufe MUSS gespeichert werden und beim nächsten Start gelten.
   - WENN das Kind eine Stufe manuell wählt, MUSS diese gelten und die Automatik aus sein, bis
     wieder „Automatisch" gewählt wird (dann beginnt sie wieder bei Niedrig, gesperrte Stufen werden
     freigegeben).
   - Ein Wechsel der Grafikstufe MUSS ohne Neustart wirken; danach MUSS das Spiel weiter steuerbar
     und spielbar sein. FALLS die 3D-Darstellung verloren geht (Grafikspeicher vom Gerät
     zurückgesetzt), DANN MUSS das Spiel pausieren und nach der Wiederherstellung weiterspielbar sein.
     Ein solcher Verlust zeigt, dass das Gerät überlastet ist: SOLANGE „Automatisch" aktiv ist, MUSS
     die Stufe danach auf Niedrig gehen (gespeichert); bei manuell gewählter Stufe über Niedrig MUSS
     ein Hinweis erscheinen, die Grafik niedriger zu stellen. Ein Verlust, während das Spiel im
     Hintergrund ist oder kurz nach der Rückkehr, zählt nicht als Überlastung (Stufe und Hinweis
     bleiben unverändert). Ein automatisches Herunterstufen
     während des Ritts DARF die Darstellung nicht verlieren lassen: es MUSS in kleinen, für das Gerät
     verkraftbaren Schritten geschehen und dabei zuerst Grafikspeicher freigeben, bevor Neues
     aufgebaut wird.
   - WENN das Spiel beim letzten Mal während der 3D-Darstellung unerwartet beendet wurde (z. B. der
     Browser hat den Tab wegen Überlastung geschlossen oder neu geladen), MUSS das beim nächsten
     Start wie ein Verlust der 3D-Darstellung zählen: bei „Automatisch" Stufe Niedrig (gespeichert),
     bei manuell gewählter Stufe über Niedrig ein Hinweis, die Grafik niedriger zu stellen. Ein
     normales Schließen, Wechseln in eine andere App, ein Beenden im Hintergrund oder in den ersten
     3 Sekunden nach der Rückkehr zählt nicht.
   - „Mittel" MUSS auf Geräten, die vor den zusätzlichen Details (Regel 3, SRT-011) „Mittel"
     flüssig darstellten, weiterhin flüssig laufen: die zusätzlichen Umgebungsdetails auf „Mittel"
     DÜRFEN die Grafikspeicher-Abschätzung, die Zahl der Shader-Programme und der Draw Calls
     gegenüber dem Stand davor nur geringfügig erhöhen; aufwendige Details (z. B. Wind, Blumen,
     grasende Pferde, Vögel) gibt es nur auf „Hoch".
   - Bevor eine Stufe angewendet wird (Start und jeder Wechsel, auch manuell „Hoch"), MUSS das Spiel
     den Grafikspeicher dieser Stufe abschätzen und mit einer vorsichtigen Grenze für das Gerät
     vergleichen; passt sie nicht, MUSS es zuerst die Auflösung, dann die Schattenqualität, dann die
     Dichte von Gras und Umgebung senken, bis sie passt. Die gewählte Stufe bleibt eingestellt.
   - WENN eine manuell gewählte Stufe über 5 Sekunden im Mittel unter 30 fps läuft (gemessen wie
     oben), MUSS das Spiel einmal je Ritt bzw. freiem Modus einen kurzen Hinweis zeigen, dass die
     Grafik für das Gerät zu hoch ist; die Stufe bleibt.
   - In den Einstellungen MUSS sich eine Bildraten-Anzeige ein- und ausschalten lassen (Standard:
     aus). SOLANGE sie an ist, MUSS beim Reiten (Vorstart, Ritt, freier Modus) die aktuelle Bildrate
     in fps (etwa zweimal pro Sekunde aktualisiert) zusammen mit der aktuellen Grafikstufe in einer
     Ecke stehen, ohne Bedienelemente zu verdecken; bei automatisch gewählter Stufe MUSS das
     erkennbar sein (z. B. „58 fps · Mittel (Auto)"). Wechselt die Stufe (Automatik oder
     Einstellungen), MUSS die Anzeige sofort folgen.
5. Das Spiel MUSS nach dem ersten vollständigen Laden ohne Internetverbindung startbar und spielbar
   sein und auf Tablet/Handy zum Startbildschirm hinzugefügt werden können. Eine neue Spielversion
   MUSS im Hintergrund geladen werden und ab dem nächsten Start gelten, ohne Hinweis und ohne
   laufendes Spiel zu unterbrechen. „Nächster Start" heißt: alle Tabs bzw. die Startbildschirm-App
   wurden geschlossen und neu geöffnet; Neuladen eines offenen Tabs zählt nicht.
6. Alle Texte MÜSSEN auf Deutsch und Englisch vorliegen. Beim ersten Start gilt die Browsersprache
   (Deutsch, wenn sie Deutsch ist, sonst Englisch); die Sprache ist in den Einstellungen umschaltbar.
   Texte SOLLEN kurz und für eine 3. Klasse lesbar sein.
7. FALLS Gerät oder Browser keine 3D-Darstellung unterstützt, DANN MUSS beim App-Start statt der
   ganzen App (auch statt der Menüs) ein
   kindgerechter Hinweis in der passenden Sprache erscheinen („Dein Gerät oder Browser kann das
   Spiel leider nicht anzeigen"), keine leere Seite.

### Steuerung

8. Tastatur: A/D lenken links/rechts; W erhöht, S verringert das Tempo stufenlos, solange gedrückt;
   aus dem Halt geht das Pferd mit gehaltenem S rückwärts (Regel 9); Space springt; Shift (gehalten) = Galopp; Esc = Pause; C = Kamera umschalten.
9. SOLANGE kein Galopp aktiv ist, MUSS das Tempo stufenlos zwischen Halt, Schritt und Trab liegen;
   die Gangart ergibt sich aus dem Tempo. SOLANGE Galopp aktiv ist, MUSS das Pferd galoppieren und
   W/S (bzw. Joystick) regeln das Galopptempo stufenlos. WENN Galopp beendet wird, MUSS das Pferd
   in den Trab zurückfallen. Tastatur: Galopp ist aktiv, solange Shift gehalten wird; wird Galopp
   vom Spiel beendet (Verweigerung, Regel 22; frontaler Stopp am Zaun, Regel 24), MUSS Shift erst
   losgelassen und neu gedrückt werden. WENN der Touch-Modus an- oder ausgeht (Regel 11), MUSS ein
   aktiver Galopp enden (Trab) und der Touch-Umschalter auf aus stehen.
   Rückwärtsrichten: WENN das Pferd steht und S (bzw. Joystick nach unten) weiter gehalten wird, MUSS
   es nach einer kurzen Pause (unter einer halben Sekunde) langsam rückwärts gehen (langsamer als
   Schritt) und dabei lenkbar sein; WENN losgelassen wird, MUSS es anhalten. W, Galopp oder Joystick
   nach oben beenden das Rückwärtsgehen. Rückwärts wird nicht gesprungen (Space wirkt nicht),
   Hindernisse und Umzäunung halten das Pferd auf, und Start- oder Ziellinie zählen rückwärts nicht.
10. Touch (Tablet/Handy): links ein virtueller Joystick, rechts große Buttons „Galopp" und
    „Springen" (wie Space), dazu Buttons für Pause und Kamera.
    - Joystick hoch/runter ändert das Tempo, solange ausgelenkt, wie W/S; je weiter ausgelenkt,
      desto schneller die Änderung. Aus dem Halt nach unten gehalten geht das Pferd rückwärts wie
      mit S (Regel 9).
    - Joystick links/rechts lenkt stufenlos: je weiter ausgelenkt, desto enger die Kurve. Die engste
      Kurve MUSS schon vor dem Anschlag erreicht sein (spätestens bei etwa zwei Dritteln der
      seitlichen Auslenkung), und auch schräg nach vorn gehalten MUSS das Pferd deutlich lenken.
      Das Pferd MUSS wendig und leicht zu steuern sein: Kurven deutlich enger und schnelleres
      Einlenken als bei einem echten Pferd (Kinder-Spiel, Spieltest 2026-10-04).
    - „Galopp" ist ein Umschalter: einmal tippen = Galopp an, nochmal tippen = Galopp aus. Der Button
      MUSS sichtbar zeigen, ob Galopp an ist.
    - Alle Touch-Bedienelemente MÜSSEN mindestens 44×44 px groß sein.
11. Touch-Modus: Auf reinen Touch-Geräten (Tablet, Handy) ist er von Anfang an aktiv, auf reinen
    Tastatur-Geräten nie. Geräte mit Tastatur und Touchscreen starten ohne Touch-Modus; WENN der
    Bildschirm berührt wird, MUSS er aktiv werden; WENN eine Spieltaste gedrückt wird, MUSS er
    enden. SOLANGE der Touch-Modus aktiv ist, MÜSSEN im Spiel die Touch-Bedienelemente sichtbar sein.
12. SOLANGE der Touch-Modus aktiv ist, MUSS die ganze App (Spiel und alle Menüs) im Querformat
    laufen; im Hochformat MUSS statt der App ein Hinweis „Gerät drehen" erscheinen. FALLS das im
    Vorstart, während eines Ritts oder im freien Modus passiert, MUSS das Spiel pausieren (Regel 38).

### Kamera

13. Die Standard-Kamera MUSS schräg hinter und über Pferd und Reiter mitlaufen, so dass das nächste
    Hindernis und die Distanz dazu einschätzbar sind.
14. Per C bzw. Kamera-Button MUSS zwischen Standard-Kamera und Reiter-Sicht (zwischen den
    Pferdeohren) umgeschaltet werden können. Die zuletzt gewählte Kamera wird gespeichert (Regel 44).
    Eine Kamera-Auswahl in den Einstellungen gibt es nicht.

### Reiten und Springen

15. Ob ein Sprung gelingt, MUSS von vier Faktoren abhängen: Gangart/Tempo beim Anreiten,
    Anreitwinkel zum Hindernis, Absprungdistanz (Abstand zum Hindernis beim Absprung) und dem
    Zeitpunkt, an dem Space gedrückt wird. Höhere und breitere Hindernisse MÜSSEN mehr Tempo und
    genaueres Timing verlangen als niedrige. Das Spiel SOLL verzeihend genug sein, dass ein Kind
    Parcours 1 nach wenigen Versuchen fehlerfrei schafft.
16. Springbarkeit nach Gangart: aus Halt und Schritt nie, aus dem Trab nur Kreuze, aus dem Galopp
    alle Hindernisse. WENN ein Hindernis in einer Gangart angeritten wird, aus der es nicht springbar
    ist, MUSS das Pferd verweigern.
17. Anreitwinkel: Bei mehr als 30° Abweichung von der Senkrechten zum Hindernis MUSS das Pferd
    verweigern (vorbeilaufen), auch wenn Space gedrückt wird. Bis 30° springt es; das Abwurfrisiko
    steigt, je schräger angeritten wird.
18. Sicherer Kern, Risiko am Rand: WENN Gangart, Winkel, Tempo und Absprungdistanz innerhalb der
    Absprungzone und ihrer Toleranzen liegen, MUSS der Sprung **immer** sauber gelingen (kein Zufall).
    Je weiter eine Größe darüber hinaus abweicht, desto höher MUSS die Wahrscheinlichkeit eines
    Abwurfs sein.
19. Timing: WENN Space gedrückt wird, während das Pferd in Reichweite eines Hindernisses ist und
    Gangart und Winkel es zulassen, MUSS das Pferd sofort abspringen. Zu früh (vor der Absprungzone)
    oder zu spät (zu dicht am Hindernis) erhöht das Abwurfrisiko nach Regel 18.
20. Selbst springen (nur an Hindernissen, an denen eine Verweigerung möglich ist, siehe Regel 22):
    WENN das Pferd ein Hindernis anreitet und bis zum letzten möglichen
    Absprungpunkt kein Space gedrückt wurde, MUSS es bei passender Gangart, passendem Winkel und
    ausreichendem Tempo selbst springen, mit deutlich erhöhtem Abwurfrisiko; sonst MUSS es verweigern.
21. Hopser: WENN Space im Trab oder Galopp gedrückt wird, ohne dass ein Hindernis in Reichweite ist,
    MUSS das Pferd einen kleinen Hopser ohne Wertung machen; im Halt oder Schritt passiert nichts.
    Ein Hopser zählt nicht als Sprung.
22. Verweigerung: Eine Verweigerung entsteht nur beim Anreiten (siehe Begriffe), im Parcours nur am
    Hindernis, das an der Reihe ist, und nur in Sprungrichtung; im freien Modus an jedem Hindernis.
    An allen anderen Hindernissen (im Parcours: nicht an der Reihe, gegen die Sprungrichtung; im
    Vorstart alle) springt das Pferd nur auf Space (Regel 19) und nie selbst. Kommt dort kein Sprung
    zustande (kein Space, oder Gangart bzw. Winkel lassen ihn nicht zu), MUSS das Pferd ohne Fehler
    seitlich ausweichen und vorbeilaufen; Gangart, Tempo und Galopp bleiben unverändert, es gibt
    keinen Stopp. Reiten neben oder
    an einem Hindernis vorbei, ohne es anzureiten (z. B. eine Volte daneben), ist keine Verweigerung.
    Nach einer Verweigerung MUSS das Pferd stehen bleiben bzw. vorbeilaufen; das Kind kann im Halt
    wenden. Eine weitere Verweigerung am selben Hindernis kann erst entstehen, nachdem sich das Pferd
    weiter als den Anreitabstand entfernt hat und neu anreitet. Reitet das Kind vorher erneut auf das
    Hindernis zu, gilt wie an Hindernissen ohne Wertung: Das Pferd springt nur auf Space (Regel 19,
    ein solcher Sprung gilt normal, im Parcours gewertet) und nie selbst; kommt kein Sprung zustande,
    MUSS es ohne Fehler seitlich ausweichen und behält Gangart, Tempo und Galopp. Trifft das Pferd
    einen Ständer, MUSS es ebenso ausweichen. Die Zeit läuft weiter.
    - Zeitpunkt: Ob verweigert wird, entscheidet sich erst am letzten möglichen Absprungpunkt. Bis
      dahin darf das Kind die Gangart ändern (z. B. noch angaloppieren) oder abwenden, ohne Fehler.
    - Verhalten: Verweigerung wegen Gangart (Regel 16) oder zu geringem Tempo (Regel 20) = das Pferd
      bleibt vor dem Hindernis stehen und ist danach im Halt. Verweigerung wegen Winkel (Regel 17) =
      das Pferd läuft vorbei und fällt danach in den Trab. In beiden Fällen ist Galopp danach aus
      (auch der Touch-Umschalter; Tastatur siehe Regel 9). Treffen Gangart bzw. Tempo und Winkel
      zusammen, gilt das Verhalten für Gangart/Tempo (stehen bleiben, Halt).
23. Bei einem Abwurf MUSS die Stange sichtbar fallen; die Meldung im Spiel sagt es kindgerecht
    („Stange gefallen!"), damit sie nicht mit einem Sturz verwechselt wird. Wann sie wieder aufgebaut
    wird, regeln die Regeln 26, 27, 29, 31 und 41.
24. Das Pferd MUSS je Gangart, beim Rückwärtsrichten und beim Sprung (Absprung, Flug, Landung)
    erkennbar animiert sein. Die Bewegungen MÜSSEN geschmeidig sein: Gangartwechsel, Galoppwechsel,
    Übergänge in und aus dem Sprung, Verweigerung und Halt laufen ohne sichtbares Springen oder
    Ruckeln der Beine, des Körpers und des Reiters; die Hufe rutschen nicht über den Boden.
    Der Reiter geht beim Sprung in den leichten Sitz und gibt mit den Händen am Hals nach.
    Das Pferd kann den Platz nicht verlassen. Trifft es frontal auf die Umzäunung, MUSS es stoppen
    und ist danach im Halt (Galopp aus, wie Regel 22); trifft es schräg auf, MUSS es mit
    unverändertem Tempo daran entlanggleiten. Ab wann „frontal" gilt, ist ein Spielwert.

### Gewerteter Parcours

25. Es MUSS 5 Parcours geben:

    | Parcours | Hindernisse | Arten | Höhen |
    | --- | --- | --- | --- |
    | 1 | 4 | Kreuze | 40–50 cm |
    | 2 | 5 | Kreuze und Steilsprünge | Steilsprung 60 cm |
    | 3 | 6 | Kreuze, Steilsprünge, erster Oxer | bis 70 cm |
    | 4 | 7 | Steilsprünge und Oxer gemischt | bis 80 cm |
    | 5 | 8–10 | Steilsprünge, Oxer, mindestens eine Zweifach-Kombination | bis 85 cm |

    Eine Zweifach-Kombination kommt erst in Parcours 5 vor.
26. Vorstart: WENN ein Parcours gewählt wird, MUSS eine Vorstart-Karte erscheinen mit
    Parcours-Plan (Lage und Reihenfolge der Hindernisse), Schalter für die Absprung-Hilfe und „Los".
    WENN „Los" gewählt wird, MUSS das Pferd auf dem Platz vor der Startlinie stehen, ein Startsignal
    ertönen und das Kind frei zur Startlinie reiten können. Im Vorstart läuft keine Zeit; Hindernisse
    des Parcours zählen für die Wertung erst nach Überqueren der Startlinie (Sprungzähler: Regel 40).
    Fällt im Vorstart eine Stange, MUSS sie nach etwa 3 Sekunden ohne Wertung wieder aufgebaut werden.
    Im Vorstart ist Hindernis 1 hervorgehoben (bei eingeschalteter Absprung-Hilfe auch mit Zone),
    dort entsteht aber keine Verweigerung (Regel 22). Das Startsignal ertönt nur bei „Los".
    Start- und Ziellinie zählen nur beim Überqueren in Ritt-Richtung; die Ziellinie im Vorstart und
    die Startlinie während des Ritts bewirken nichts.
27. Die Zeit MUSS mit dem Überqueren der Startlinie beginnen und mit dem Überqueren der Ziellinie
    enden, nachdem alle Hindernisse in Reihenfolge gesprungen wurden. Ein Hindernis mit Abwurf gilt
    als gesprungen; es geht mit dem nächsten weiter. Abgeworfene Stangen des Parcours bleiben bis zum
    Ende des Ritts liegen.
28. Das Hindernis, das an der Reihe ist, MUSS hervorgehoben sein und seine Nummer zeigen. Alle
    Parcours-Hindernisse zeigen ihre Sprungrichtung (Fahnen); der Parcours-Plan auf der
    Vorstart-Karte zeigt Reihenfolge und Richtung. Nach dem letzten Hindernis ist kein Hindernis mehr
    hervorgehoben; die Ziellinie ist markiert und die Anzeige „nächstes Hindernis" zeigt „Ziel".
29. Falsches Hindernis: WENN ein Hindernis gesprungen wird, das nicht an der Reihe ist, MUSS das ohne
    Fehlerpunkte und ohne Wertung bleiben; das richtige Hindernis bleibt hervorgehoben. Fällt dabei
    eine Stange, MUSS sie nach etwa 3 Sekunden wieder aufgebaut werden, und das Hindernis muss später
    regulär gesprungen werden. Ein Sprung über das richtige Hindernis gegen die Sprungrichtung gilt
    wie ein falsches Hindernis. Verweigerungen und Selbst-Springen: Regel 22.
30. WENN die Ziellinie überquert wird, bevor alle Hindernisse gesprungen sind, MUSS der Ritt
    weiterlaufen (Ziel zählt noch nicht) und ein Hinweis auf das fehlende Hindernis erscheinen.
31. Zweifach-Kombination: Teil a und b MÜSSEN direkt nacheinander gesprungen werden. Teil b ist erst
    nach einem Sprung über a an der Reihe; ein Sprung über b allein gilt wie ein falsches Hindernis
    (Regel 29).
    - WENN an Teil a oder b verweigert wird, MUSS die ganze Kombination (a und b) neu angeritten
      werden; die Hervorhebung springt auf Teil a zurück.
    - WENN nach Teil a abgewendet wird, ohne b zu springen (und ohne Verweigerung nach Regel 22),
      MUSS die Hervorhebung ohne Fehler auf Teil a zurückspringen; die Kombination wird neu
      angeritten. Als abgewendet gilt: Das Pferd reitet b nicht an und ist weiter als den
      Anreitabstand von b entfernt.
    - Vor jedem neuen Anlauf MÜSSEN gefallene Stangen von a und b wieder aufgebaut werden. Abwürfe
      an a und b zählen je einzeln, aus allen Anläufen (jeder Abwurf = 4 Fehler).
32. Fehlerpunkte: Abwurf = 4, jede Verweigerung = 4 (auch die zweite und weitere am selben
    Hindernis; kein Ausschluss).
33. Zeitfehler: Jeder Parcours hat eine erlaubte Zeit = Zeit für die Ideallinie bei mittlerem
    Galopptempo × 1,5 (aufgerundet auf volle Sekunden). Ausnahme Parcours 1: mittleres Trabtempo
    statt Galopptempo, damit er auch im Trab ohne Zeitfehler schaffbar ist. Je angefangene 4 Sekunden über der erlaubten
    Zeit gibt es 1 Fehlerpunkt.
34. Während des Ritts MÜSSEN laufende Zeit, erlaubte Zeit, aktuelle Fehlerpunkte und das nächste
    Hindernis sichtbar sein.
35. Nach dem Ziel MUSS eine Ergebnisanzeige erscheinen: Pferdename, Zeit, Fehler aufgeschlüsselt
    (Abwürfe, Verweigerungen, Zeitfehler), Sterne, ob es eine neue Bestleistung ist, neu erhaltene
    Auszeichnungen (nur die bei Rittende vergebenen, Regel 49) und die Optionen „Nochmal" (führt zur
    Vorstart-Karte desselben Parcours), „Nächster Parcours" (falls freigeschaltet) und „Zur Auswahl".
    Der erste beendete Ritt eines Parcours ist immer eine neue Bestleistung.
36. Sterne: 3 Sterne = 0 Fehler (inklusive Zeitfehler), 2 Sterne = 1 bis 4 Fehler, 1 Stern =
    beendet mit mehr als 4 Fehlern. Je Parcours zählt die beste je erreichte Sternzahl.
37. Parcours 1 ist von Anfang an offen. WENN ein Parcours mit mindestens 1 Stern beendet wird, MUSS
    der nächste Parcours freigeschaltet werden. (Da jeder beendete Ritt mindestens 1 Stern bringt,
    schaltet jeder beendete Ritt den nächsten Parcours frei.)

### Pause und Abbruch

38. WENN das Kind Pause wählt (Esc/Button), das Spiel den Fokus verliert (Tab/App gewechselt,
    Fenster minimiert) oder ein Touch-Gerät ins Hochformat gedreht wird, MUSS das Spiel pausieren
    (Zeit steht, Pferd steht). Pausemenü: „Weiter", „Neu starten", „Zur Auswahl" bzw. „Zum Menü",
    Einstellungen, Bedienungs-Tipps (Regel 56). Nach Fokusverlust geht es erst mit „Weiter" weiter.
    Nach „Weiter" MUSS das Pferd mit vorherigem Tempo und vorheriger Gangart weiterlaufen; Galopp
    per Tastatur bleibt nur, wenn Shift noch gehalten wird, der Touch-Umschalter behält seinen
    Zustand.
    Der Touch-Galopp-Umschalter MUSS bei „Los", „Neu starten" und Rittende auf aus stehen.
39. Im Parcours führt „Neu starten" zurück in den Vorstart desselben Parcours. Im freien Modus setzt
    „Neu starten" das Pferd an den Startpunkt und baut alle Stangen auf. Beim Betreten des freien
    Modus und nach „Neu starten" steht das Pferd am Startpunkt im Halt, der Touch-Umschalter auf aus.
40. Ein abgebrochener Ritt („Neu starten" oder „Zur Auswahl" vor dem Ziel) MUSS ohne Wertung bleiben:
    keine Sterne, keine Bestleistung, keine Freischaltung, kein Zählen als gerittener Parcours, keine
    ritt-gebundenen Auszeichnungen (Regel 49). Der Sprungzähler zählt dagegen jeden Sprung über ein
    Hindernis (auch mit Abwurf), in jedem Modus, im Vorstart und in abgebrochenen Ritten; bei einer
    Zweifach-Kombination zählt jeder Teil einzeln. Verweigerungen und Hopser zählen nicht.

### Freier Modus

41. Der freie Modus MUSS von Anfang an verfügbar sein und eine feste Aufstellung mit mindestens
    einem Hindernis jeder Art in verschiedenen Höhen zeigen. Es gibt keine Reihenfolge, keine Zeit,
    keine Fehlerpunkte und keine Sterne. Abwürfe und Verweigerungen werden kurz als Rückmeldung
    angezeigt. Abgeworfene Stangen MÜSSEN etwa 3 Sekunden nach dem Abwurf automatisch wieder
    aufgebaut werden. Die Hindernisse im freien Modus haben keine Richtungsfahnen.

### Absprung-Hilfe

42. Die Absprung-Hilfe MUSS die Absprungzone auf dem Boden markieren: im Parcours vor dem
    Hindernis, das an der Reihe ist; im freien Modus vor dem Hindernis, das gerade angeritten wird.
    Es gibt zwei gespeicherte Einstellungen: „im freien Modus" (Standard an) und „im Parcours"
    (Standard aus). Beide stehen in den Einstellungen (auch über das Pausemenü erreichbar); der
    Schalter auf der Vorstart-Karte ändert zusätzlich die Einstellung „im Parcours". Die
    Hilfe hat keinen Einfluss auf Wertung, Sterne oder Auszeichnungen.

### Pferd anpassen

43. Das Kind MUSS dem Pferd einen Namen geben (1–16 Zeichen) sowie Fellfarbe (Fuchs, Brauner,
    Rappe, Schimmel, Schecke) und Kopfabzeichen (keins, Stern, Blesse, Schnippe) wählen können. Die
    Wahl ist rein optisch und gilt in allen Modi. WENN das Spiel zum ersten Mal startet, MUSS es
    nach dem Pferdenamen fragen; die Frage erscheint bei jedem Start, bis sie beantwortet oder
    übersprungen wurde (auch bei Spielständen ohne Namen). Wird sie übersprungen, heißt das Pferd
    „Blitz" (Englisch: „Flash"); dieser Vorgabe-Name folgt der eingestellten Sprache, solange kein
    eigener Name vergeben ist. Leerzeichen am Anfang und Ende werden entfernt; eine danach leere oder
    zu lange Eingabe wird nicht übernommen, es gilt der letzte gültige Name. Vorgabe-Aussehen:
    Brauner mit Stern. Der Pferdename erscheint im Hauptmenü und in der Ergebnisanzeige.

### Fortschritt und Speicherung

44. Im Browser gespeichert werden MÜSSEN: freigeschaltete Parcours, Bestleistung (Fehler und Zeit)
    und beste Sternzahl je Parcours, erhaltene Auszeichnungen mit Datum, Zähler (beendete
    Parcours-Ritte, Sprünge), Pferd (Name, Fellfarbe, Kopfabzeichen) und Einstellungen (Sprache,
    Lautstärke und Stumm je Kanal (Musik, Effekte), Grafikstufe und ob „Automatisch",
    Bildraten-Anzeige, Absprung-Hilfe je Modus, Kamera, ob die Bedienungs-Tipps schon geschlossen
    wurden). Wo der Browser es anbietet, MUSS dauerhafter Speicher angefordert werden. Bekannte
    Grenze: Safari kann Daten einer Website nach längerer Nichtnutzung löschen (nicht bei Nutzung
    über den Startbildschirm).
45. Gespeichert wird sofort bei jeder Änderung: beim Ende eines Ritts, bei jedem gezählten Sprung,
    bei jeder neuen Auszeichnung, bei jeder Änderung von Einstellungen oder Pferd.
46. FALLS der Browser nicht speichern kann (z. B. privater Modus, Speicher voll), DANN MUSS das Spiel
    normal spielbar bleiben und einmal je Sitzung (bis Tab bzw. App geschlossen werden; Neuladen zählt
    nicht als neue Sitzung) einen Hinweis zeigen, dass der Fortschritt nicht gespeichert wird.
47. FALLS gespeicherte Daten fehlerhaft oder von einer älteren Spielversion sind, DANN MUSS das Spiel
    starten, lesbare Teile übernehmen und den Rest auf Anfangswerte setzen, statt abzustürzen. Der
    Spielstand MUSS so angelegt sein, dass spätere Spielbereiche eigene Daten ergänzen können, ohne
    bestehenden Fortschritt zu verlieren. Daten, die die laufende Version nicht kennt, MÜSSEN beim
    Speichern unverändert erhalten bleiben.
48. In den Einstellungen MUSS „Fortschritt löschen" mit Sicherheitsabfrage möglich sein, nur wenn die
    Einstellungen aus dem Hauptmenü geöffnet wurden (nicht aus dem Pausemenü). Danach
    sind Parcours-Freischaltung, Bestleistungen, Sterne, Auszeichnungen und Zähler wie beim ersten
    Start; Einstellungen und Pferd bleiben erhalten.

### Auszeichnungen

49. Es MUSS folgende Auszeichnungen geben; jede wird einmalig erhalten:

    | Auszeichnung | Bedingung | Vergabe |
    | --- | --- | --- |
    | Erster Sprung | erster gezählter Sprung (Regel 40) | sofort |
    | Springmaus | 100 gezählte Sprünge | sofort |
    | Fehlerfrei | erster beendeter Parcours-Ritt mit 0 Fehlern | bei Rittende |
    | Oxer-Profi | beendeter Ritt, in dem ein Oxer ohne Verweigerung und ohne Abwurf gesprungen wurde | bei Rittende |
    | Kombi-Könner | beendeter Ritt, in dem eine Zweifach-Kombination (a und b) ohne Verweigerung und ohne Abwurf gesprungen wurde | bei Rittende |
    | Alles offen | alle 5 Parcours freigeschaltet | bei Rittende |
    | Sternenreiter | alle 5 Parcours mit 3 Sternen | bei Rittende |
    | Fleißig | 10 beendete Parcours-Ritte | bei Rittende |

    „Ohne Verweigerung" und „ohne Abwurf" heißen: an diesem Hindernis im ganzen Ritt keine
    Verweigerung bzw. kein Abwurf. Es zählen nur gewertete Sprünge (Hindernis an der Reihe, in
    Sprungrichtung); ein Oxer als Teil einer Kombination zählt für „Oxer-Profi" mit.
    Bedingungen gelten als „mindestens" erreicht: Ist eine Bedingung schon erfüllt (z. B. aus einem
    älteren Spielstand), wird die Auszeichnung beim nächsten passenden Anlass nachgeholt (sofortige
    beim nächsten gezählten Sprung, die übrigen beim nächsten beendeten Ritt). Für „Fehlerfrei" gilt
    ein gespeicherter Parcours mit 3 Sternen als erfüllte Bedingung; „Oxer-Profi" und „Kombi-Könner"
    lassen sich nicht aus gespeicherten Daten ableiten und brauchen einen passenden Ritt.
    „Sofort" vergebene Auszeichnungen MÜSSEN direkt kurz eingeblendet werden, ohne das Spiel zu
    unterbrechen. „Bei Rittende" vergebene erscheinen in der Ergebnisanzeige.
50. Eine Übersicht MUSS alle Auszeichnungen zeigen, erhaltene hervorgehoben mit Datum, noch fehlende
    mit ihrer Bedingung.

### Klang

51. Es MUSS synthetisierte Soundeffekte geben: Hufschlag passend zur Gangart, Absprung, Landung,
    fallende Stange, Startsignal (bei „Los") und Zielsignal. Eine einfache synthetisierte Melodie
    läuft in Hauptmenü und Untermenüs, auf der Vorstart-Karte und in der Ergebnisanzeige. Keine Musik
    im Vorstart, während eines Ritts, im freien Modus und im Pausemenü (auch nicht in den
    Einstellungen und Bedienungs-Tipps, wenn sie aus dem Pausemenü geöffnet wurden). Ist die App im Hintergrund (Tab
    gewechselt, minimiert), MUSS jeder Ton verstummen.
52. Musik und Effekte MÜSSEN getrennt in der Lautstärke regelbar und stumm schaltbar sein. Ton
    startet erst nach der ersten Interaktion des Kindes (Browser-Vorgabe). Beim ersten Start sind
    beide an, mit mittlerer Lautstärke. „Stumm" ist je Kanal ein eigener Schalter; die eingestellte
    Lautstärke bleibt dabei erhalten und gilt nach dem Einschalten wieder.

### Menüs

53. Hauptmenü: „Parcours", „Freier Modus", „Mein Pferd", „Auszeichnungen", „Bedienungs-Tipps",
    „Einstellungen".
54. Das Hauptmenü MUSS so aufgebaut sein, dass später weitere Spielbereiche als zusätzliche Einträge
    dazukommen können, ohne dass sich die bestehenden Einträge oder ihr Verhalten ändern.
55. Die Parcours-Auswahl MUSS je Parcours zeigen: Nummer, Anzahl Hindernisse, gesperrt/offen, beste
    Sterne und Bestleistung (Fehler und Zeit), falls vorhanden.

56. Bedienungs-Tipps: WENN das Spiel zum ersten Mal startet, MUSS nach der Frage nach dem
    Pferdenamen (Regel 43) und vor dem Hauptmenü eine Übersicht der Bedienung erscheinen; das gilt
    auch für einen bestehenden Spielstand, solange die Übersicht noch nie geschlossen wurde. Sie
    MUSS die Bedienung der aktuellen Eingabeart zeigen (Tastatur: Tasten als Tastenkappen mit kurzer
    Erklärung; Touch: Joystick und Buttons) und sich auf die andere Eingabeart umschalten lassen.
    Texte kurz, mit Symbolen, für eine 3. Klasse lesbar. WENN sie mit „Verstanden" geschlossen
    wird, MUSS das gespeichert werden (Regel 44) und sie erscheint nicht mehr von selbst. Über
    „Bedienungs-Tipps" im Hauptmenü und im Pausemenü MUSS sie jederzeit wieder geöffnet werden
    können; aus der Pause geöffnet, führt „Verstanden" zurück in die Pause.
57. Farbsprache der Buttons: Buttons für das Weitermachen (z. B. Menü-Einträge, „Los", „Weiter",
    „Nochmal", „Verstanden") MÜSSEN grün sein, zurückhaltende Buttons (z. B. „Zurück",
    „Abbrechen", „Überspringen", „Neu starten") hell mit Rahmen, und nur Buttons, die etwas
    löschen, rot. Die Schrift auf allen Buttons MUSS gut lesbar sein (Kontrast mindestens 4,5 : 1),
    und die Art eines Buttons DARF nicht nur an der Farbe erkennbar sein (Beschriftung, Form).

58. Versionsanzeige: In den Einstellungen MUSS unten klein die laufende Spielversion stehen
    (Datum des Stands und eine kurze Kennung, z. B. „Version 2026-10-05 · 3fdf19e"), damit man
    prüfen kann, ob nach einem Update schon die neue Version läuft (Regel 5). Dieselbe Angabe MUSS
    in der Debug-Anzeige stehen.

## Grenzfälle

- **Erster Start / leerer Speicher:** Frage nach dem Pferdenamen (Regel 43), dann
  Bedienungs-Tipps (Regel 56), Parcours 1 offen, Browsersprache, Grafikstufe automatisch (Start
  bei Niedrig, Regel 4).
- **Speichern nicht möglich:** Regel 46.
- **Defekte oder alte Speicherdaten:** Regel 47.
- **Safari löscht Daten nach längerer Nichtnutzung:** bekannte Grenze (Regel 44).
- **Pferd trifft die Umzäunung:** frontal Stopp, schräg Gleiten (Regel 24).
- **Kein 3D möglich:** Regel 7.
- **Zwei Tabs gleichzeitig offen:** kein Abgleich zur Laufzeit; der zuletzt gespeicherte Stand gewinnt.
- **Fokusverlust / Tab-Wechsel / Hochformat:** Auto-Pause (Regel 38).
- **Abbruch eines Ritts:** keine Wertung (Regel 40).
- **Ziellinie vor allen Hindernissen überquert:** Regel 30.
- **Falsches Hindernis gesprungen oder abgeworfen:** Regel 29.
- **Verweigerung in der Kombination:** Regel 31.
- **An einem Hindernis ohne Wertung vorbeigeritten (nicht an der Reihe, Rückseite, Vorstart):** Pferd weicht aus, behält Gangart und Galopp (Regel 22).
- **Volte neben einem Hindernis / Abwenden vor dem letzten Absprungpunkt:** keine Verweigerung (Regel 22).
- **Abwenden zwischen Teil a und b der Kombination:** zurück auf a ohne Fehler (Regel 31).
- **Hindernis gegen die Sprungrichtung gesprungen:** wie falsches Hindernis (Regel 29).
- **Abwurf im Vorstart:** Stange wird ohne Wertung wieder aufgebaut (Regel 26).
- **Pferd an der Umzäunung:** Regel 24.
- **Sehr viele Verweigerungen:** kein Ausschluss, Fehler steigen weiter; der Ritt kann jederzeit abgebrochen werden.
- **Offline:** Spiel startet und läuft vollständig (Regel 5).
- **Neue Spielversion:** gilt ab dem nächsten Start, Spielstand bleibt erhalten (Regeln 5, 47).
- **Schwaches Gerät:** Grafikstufe sinkt automatisch (Regel 4); bei manuell zu hoher Stufe ein
  Hinweis (Regel 4).
- **Grafikstufe im Ritt gewechselt / 3D-Darstellung kurz verloren:** weiter spielbar bzw. Pause
  (Regel 4).
- **Tab vom Browser beendet (Absturz) während der 3D-Darstellung:** beim nächsten Start wie ein
  Kontextverlust; die Stufe wird gesperrt (Regel 4). Ein Ende im Hintergrund oder in den ersten
  3 Sekunden nach der Rückkehr zählt nicht.
- **Zwei Tabs gleichzeitig mit 3D-Darstellung:** ein noch laufender anderer Tab gilt nicht als
  Absturz; die Absturz-Erkennung überschreibt keinen Spielstand des anderen Tabs (Regel 4).
- **Automatik stuft hoch und wieder herunter:** eine Stufe, von der wegen Ruckelns
  heruntergestuft wurde, wird im laufenden Spiel nicht wieder versucht (Regel 4).
- **Rückwärts gegen Hindernis, Zaun oder über Start-/Ziellinie:** Pferd hält an, Linien zählen
  nicht (Regel 9).

## Plattform-Ausprägungen

Eine Ziel-Plattform (Web). Abweichungen nach Eingabeart:

| | Tastatur (PC) | Touch (Tablet/Handy) |
| --- | --- | --- |
| Tempo / Lenken | W/S, A/D (Regel 8) | Joystick: Tempo wie W/S, Lenken stufenlos (Regel 10) |
| Galopp | Shift halten | Button als Umschalter (Regel 10) |
| Springen, Pause, Kamera | Space, Esc, C | Buttons |
| Ausrichtung | beliebig | nur Querformat, ganze App, solange der Touch-Modus aktiv ist (Regel 12) |
| Geräte mit Tastatur + Touch | starten ohne Touch-Modus; an bei Berührung, aus bei Spieltaste (Regel 11) | |
