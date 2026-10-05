# Zoe's Horse Farm – Native App (KMP/CMP + Filament) – Architektur-Vertrag

Branch `kmp-cmp`. Ziel: die bestehende Web-App (`src/`, Spec
`docs/specs/springreiten-trainer/architecture.md`, Konzept `docs/features/springreiten-trainer/concept.md`)
**vollständig und verhaltensgleich** als native App für **Android und iOS** nachbauen:
Kotlin Multiplatform (KMP), UI mit Compose Multiplatform (CMP), 3D mit Google Filament über
[filament-kmp](https://github.com/Erkko68/filament-kmp) (`io.github.erkko68.filament:*`).
Die Web-App bleibt unverändert im Repo und ist die **fachliche Referenz** („Was“). Dieses Dokument
regelt das „Wie“ der nativen App. Abweichungen erst hier ändern.

## Grundsätze

- **Port, keine Neuerfindung.** Jede Regel, jeder Spielwert (`tuning.js` → `Tuning.kt`), jede
  Wertung, jede Freischaltung, jedes Pferd-/Reiter-Detail verhält sich wie in der Web-App. Die
  Vitest-Tests werden **mitportiert** (gleiche Fälle, Kotlin, `kotlin.test`); sie sind die Abnahme.
- **CLAUDE.md gilt unverändert:** Code, Kommentare, Testnamen, Logs auf Englisch; Texte nur in
  i18n (DE + EN); TDD für Domain/Application (beim Port: Test zuerst portieren, rot sehen, dann Code);
  Clean Architecture; Spielwerte nur in `Tuning.kt`.
- **Keine Asset-Dateien** (wie Web): Geometrie, Texturen, Materialien (Filament-Materialien werden zur
  Laufzeit mit `filamat`/`MaterialBuilder` aus Quelltext gebaut) und Klänge entstehen im Code.
  Ausnahme später: App-Icon.
- **Kein Allokieren pro Frame** in Sim-, Animations- und Render-Pfaden (Scratch-Objekte wie in JS).
  Nachweis per JVM-Allokationstest (`jvmTest`, `ThreadMXBean.getCurrentThreadAllocatedBytes`); die Tests
  laufen ohne Escape-Analyse (`-XX:-DoEscapeAnalysis`), damit verstecktes Boxing (Funktionstypen mit
  primitiven Parametern, generische Ranges) sichtbar wird wie auf Kotlin/Native und ART.
- **Zeit/Zufall injiziert** (Ports `Clock`, `Rng`), nie `System.currentTimeMillis()`/`Random` in
  Domain/Application.

## Plattformen und Ziele (Kotlin-Targets)

| Target | Zweck |
| --- | --- |
| `jvm` | schnelle Unit-Tests auf jedem Rechner/CI (nicht ausgeliefert) |
| `iosArm64`, `iosSimulatorArm64` | iOS-App (klibs lassen sich auf Linux kompilieren, Linken braucht macOS/Xcode) |
| `androidTarget` | Android-App; kommt dazu, sobald Google Maven (`dl.google.com`) und Android SDK erreichbar sind |

Stand der Build-Umgebung (Cloud-Container): `dl.google.com` und `download.jetbrains.com` sind
gesperrt. Damit fehlen Android Gradle Plugin, androidx und Compose Multiplatform (hängt an androidx).
Bis dahin werden alle Module ohne Compose/Android gebaut und geprüft; die Compose-UI (`:adapters:ui`)
und die Plattform-Hüllen folgen, sobald die Hosts erreichbar sind. Die Bildschirm-Logik liegt dafür
schon toolkit-frei in `:adapters:presentation`, die Verdrahtung in `:app`.

## Module (Gradle = Schichtgrenzen)

Projekt unter `mobile/`. Jedes Modul nutzt die Konvention `zhf.kmp-library` (`mobile/build-logic`).
Ein Modul sieht nur, was es als Abhängigkeit deklariert: die Abhängigkeitsrichtung ist damit
vom Build erzwungen (ersetzt `no-restricted-imports`).

| Modul | Paket | Inhalt (Web-Vorlage) | Darf nutzen |
| --- | --- | --- | --- |
| `:core:shared` | `app.zoeshorsefarm.shared` | `src/shared/` (Events, Math, Spring, Reach) | – |
| `:core:domain` | `app.zoeshorsefarm.domain.{sim,course,progress,horse}` | `src/domain/` | shared |
| `:core:application` | `app.zoeshorsefarm.application` | `src/application/` | domain, shared |
| `:core:domain-testing`, `:core:application-testing` | `…testing` | Test-Hilfen aus `tests/support/` (Autopilot, Layout-Prüfer, Fake-Store/-Uhr …); nur als `commonTest`-Abhängigkeit | domain bzw. application |
| `:adapters:scene` | `app.zoeshorsefarm.scene` | renderer-neutrales Szenenmodell („three-lite“, siehe unten), Mathe, Geometrie-Bausteine, `Raster2D` (Canvas-Ersatz für Texturen), Szenen-Statistik | shared |
| `:adapters:view3d` | `app.zoeshorsefarm.view3d` | `src/adapters/view3d/` (Welt, Hindernisse, Pferd, Reiter, Kamera, Grafikstufen, Engine-Logik) auf dem Szenenmodell | scene, application, domain, shared |
| `:adapters:render-filament` | `app.zoeshorsefarm.render.filament` | Filament-Backend: überträgt das Szenenmodell auf Filament (Entities, Puffer, Materialien, Licht, Schatten, Kamera) | scene, filament (Tests zusätzlich view3d und domain, um die echte Welt zu zeichnen) |
| `:adapters:audio` | `app.zoeshorsefarm.audio` | `src/adapters/audio/` als PCM-Synthese; Ausgabe pro Plattform (`expect`/`actual`) | shared |
| `:adapters:storage` | `app.zoeshorsefarm.storage` | `src/adapters/storage/` (Key-Value + JSON) | application |
| `:adapters:platform` | `app.zoeshorsefarm.platform` | Lebenszyklus, Geräte-Infos, Version | application |
| `:adapters:input` | `app.zoeshorsefarm.input` | `src/adapters/input/` (Joystick-Mapping, Eingabezustand, Tastatur); die Touch-Bedienelemente gehören zu `:adapters:ui` | application, platform |
| `:adapters:i18n` | `app.zoeshorsefarm.i18n` | `src/adapters/ui/i18n/` (Texttabellen DE/EN, `t()`) | application |
| `:adapters:presentation` | `app.zoeshorsefarm.presentation` | Bildschirm-Logik ohne UI-Toolkit aus `src/adapters/ui/` (Navigation, Menü, Einstellungen, Hinweise, Parcours-Plan, HUD-, Profil- und Hilfe-Modelle, Design-Tokens); die Compose-UI zeigt diese Modelle an und meldet Aktionen zurück | application, i18n, input, platform, audio |
| `:adapters:ui` (später) | `app.zoeshorsefarm.ui` | Compose-Bildschirme | alles Innere |
| `:app` | `app.zoeshorsefarm.app` | Composition Root (verdrahtet Store, Dienste, Absturzwächter, i18n, Audio, Eingabe, Präsentation, Engine und Filament; Brücke `RideEnginePort` → Engine), später iOS-Framework | alles |
| `androidApp`, `iosApp` (später) | – | Plattform-Hüllen (Android-App, Xcode-Projekt); brauchen Google Maven bzw. macOS | alles |

Namenskonvention beim Port: JS-Datei `kebab-case.js` → Kotlin-Datei `PascalCase.kt` im passenden
Unterpaket (`domain/sim/riding-sim.js` → `domain/sim/RidingSim.kt`); Tests `XxxTest.kt` in
`src/commonTest/kotlin/...`. JS-Objekte mit festen Feldern → `data class`/`class`; String-Unions →
`enum class` (mit `id`, wo der String gespeichert/übersetzt wird); Factory-Funktionen
(`createX(...)`) dürfen Klassen werden. Test-Hilfen aus `tests/support/` liegen in
`:core:domain-testing` bzw. `:core:application-testing` (oder im `commonTest` des einzigen Moduls,
das sie braucht).

## Szenenmodell (`:adapters:scene`)

Die View-Logik der Web-App baut three.js-Objekte. Damit sie (a) ohne GPU testbar bleibt und (b)
das Filament-Backend austauschbar ist, gibt es ein schlankes, renderer-neutrales Modell:

- **Mathe:** `Vec3`, `Quat`, `Mat4`, `Euler` (mutable, three.js-Semantik und -Reihenfolgen),
  `Color` (sRGB-Eingabe, linear gespeichert wie three.js-ColorManagement).
- **Knoten:** `Node` (Position, Quaternion/Rotation, Skalierung, `visible`, Kinder, Welt-Matrix,
  `castShadow`, `receiveShadow`, `frustumCulled`, `renderOrder`), `Group`, `Mesh(geometry, material)`,
  `InstancedMesh(geometry, material, capacity)` mit Instanz-Matrizen, optionalen Instanz-Farben und
  `count`, `SkinnedMesh` mit `Skeleton` (Knochen sind `Node`s, Bind-Matrizen), `Points` (falls nötig).
- **Geometrie:** `Geometry` mit Attributen (`position`, `normal`, `color`, `uv`, `skinIndex`,
  `skinWeight`, freie Zusatzattribute) als `FloatArray`/`ShortArray`, Index (`IntArray`), `version`
  für Neu-Upload, `dynamic`-Flag. Bausteine nach Bedarf der Web-App (Box, Plane, Cylinder, Cone,
  Sphere, Circle, Lathe, Tube, Torus …, `mergeGeometries`, `computeVertexNormals`).
- **Material:** Beschreibungen, keine Shader: `StandardMaterial`, `LambertMaterial`, `BasicMaterial`
  (unlit), `SkyMaterial`, `PointsMaterial`, Felder wie three.js (Farbe, Roughness, Metalness, Map,
  Normal-Map, Vertex-Farben, Transparenz, Opacity, Seite, Emissive, Fog, Depth-Write) plus
  benannte Effekte statt `onBeforeCompile` (z. B. `wind`, Fell-Muster des Pferdes) als Parameter.
- **Textur:** `Texture` mit RGBA8-Pixeln (`ByteArray`), Größe, Wrap, Filter, Mipmaps, Anisotropie,
  Farbraum, `version`. Gezeichnet wird mit `Raster2D` (Rechtecke, Kreise, Bögen, Pfade,
  Verläufe, Rauschen, Alpha-Blending). Text in Texturen kommt über den Port `TextRasterizer`
  (Plattform/Compose liefert später die echte Schrift).
- **Szene:** `Scene` (Wurzel, Hintergrund, Fog), `PerspectiveCamera`, `DirectionalLight` (mit
  Schatten-Kamera/-Mapgröße), `HemisphereLight`, Umgebungslicht-Beschreibung (statt PMREM).
- **Port `RenderBackend`:** `render(scene, camera)`, `setSize`, `setPixelRatio`, `compile(...)`,
  `info` (Draw Calls, Dreiecke, Programme), `dispose(...)`, Kontext-/Geräteverlust-Ereignis. Das
  Filament-Backend implementiert ihn; Tests nutzen ein Fake.
- **Szenen-Statistik** (Port von `tests/support/scene-stats.js` und `gpu-tracker.js`): Draw Calls,
  Dreiecke, Programme ohne GPU, damit die Budget-Tests der Web-App mitportiert werden können.
  Bewusst Produktionscode (anders als in der Web-App): Diagnose-Box und GPU-Speicher-Schätzung
  nutzen dieselben Zahlen; reine Test-Attrappen (Fake-Canvas, Fake-Backend) liegen in `commonTest`.

## Filament-Backend (`:adapters:render-filament`)

- filament-kmp 0.7.1 (Filament 1.77.x): gemeinsame API in `commonMain`, auf iOS Metal, auf Android
  OpenGL ES/Vulkan.
- Materialien zur Laufzeit mit `filamat` (`MaterialBuilder`): lit (Standard), „lambert“ (billig:
  lit mit Roughness 1 oder unlit mit eigener Diffus-Rechnung), unlit, Himmel, Varianten für
  Skinning, Instanzen (Instanz-Farben über `getInstanceIndex()`), Vertex-Farben, Wind.
- Vertex-Daten: Filament braucht Tangenten-Frames als Quaternion (`TANGENTS`) statt Normalen;
  Umrechnung im Backend.
- Grafikstufen bleiben (Pixel-Ratio/Dynamic Resolution, Schatten an/aus und Mapgröße, Materialart,
  Dichte); MSAA über `View.multiSampleAntiAliasingOptions`.

## Strom- und Wärme-Hebel der Engine (Abweichung von der Web-App, bewusst)

Ergebnis der Performance-Recherche (Tablets drosseln bei Wärme): Die Engine (`view3d/engine`) kann
Bilder sparen. **Standard: aus** – dann zeichnet sie jedes Bild wie die Web-App.

- `setPaused(true)` (Ritt pausiert): ein Bild noch zeichnen, dann nicht mehr; wieder zeichnen bei
  Größenänderung, Kamera-/Linien-Änderung, wiederhergestelltem Gerät, ausstehenden Grafik-Schritten.
  Ein neuer Ritt (`run`) beginnt nie pausiert.
- `setCapTo30Fps(true)`: höchstens ca. 30 Bilder/s (bei 144 Hz ca. 28,8); die Grafik-Automatik und der
  „Stufe zu hoch“-Hinweis messen dann nicht (30 fps sähen sonst wie ein langsames Gerät aus).
- `wantsFrames` / `demandListener`: sagt der Plattform-Hülle, ob sie den Display-Link (`CADisplayLink`,
  `Choreographer`) anhalten darf. Solange die Engine eigene Zeitgeber hat (Wiederherstellungs-
  Wächter, Kompilier-Sperre), will sie Bilder.
- Pflichten der Hülle: `frame(now)` pro Display-Bild, `setViewSize` bei Layout-Änderung,
  `setVisible(false)` **bevor** das Backend einen Verlust der Zeichenfläche im Hintergrund meldet (sonst
  zählt jeder App-Wechsel als Überlastung).

## Gates (lokal = CI)

Im Ordner `mobile/`:

```bash
./gradlew qa                     # alle Gates (lokal = CI)
./gradlew ktlintFormat           # Formatierung beheben
```

`qa` umfasst: ktlint (Formatierung; Module, Root-Skripte, build-logic), detekt 2.0 (statische
Analyse: Komplexität, mögliche Fehler, Benennung, Stil; Regeln in `mobile/config/detekt/detekt.yml`,
`MagicNumber` gilt für `core/` außer `Tuning.kt` und Tests), `jvmTest` aller Module, Kompilieren von
iOS-Haupt- und Testcode (`compileKotlinIos*`, `compileTestKotlinIos*`) und `forbiddenCallsCheck`
(Domain/Application dürfen weder `kotlin.random`/`Random` noch Systemuhren direkt nutzen, Ersatz für
die ESLint-Regeln der Web-App). Die Konvention bricht den Build ab, wenn Produktionscode ein
`*-testing`-Modul einbindet (Test-Hilfsmodule untereinander dürfen das). Warnungen sind Fehler (`allWarningsAsErrors`). Gradle 9.8 (Wrapper).
Die Web-Gates (`npm run …`) bleiben unverändert grün; ESLint und Prettier ignorieren `mobile/`.

## Arbeitsweise (parallele Agents)

- Jeder Agent arbeitet in einem eigenen Git-Worktree und **nur in seinen Modul-Ordnern**.
  `settings.gradle.kts`, `build-logic/`, `gradle/libs.versions.toml` und Build-Dateien fremder
  Module ändert nur die Koordination; braucht ein Agent eine Abhängigkeit, meldet er das.
- Nach jedem Agent-Ergebnis: alle drei Gates über das ganze Projekt, dann frischer Opus-QA-Review
  gegen CLAUDE.md und diese Spec; erst bei 0 blocker/major wird gemergt.
