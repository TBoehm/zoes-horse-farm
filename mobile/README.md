# Zoe's Horse Farm – native App (Android + iOS)

Kotlin Multiplatform, Compose Multiplatform und Google Filament (über filament-kmp). Nachbau der
Web-App aus `../src/`. Architektur und Stand: [docs/specs/kmp-cmp/architecture.md](../docs/specs/kmp-cmp/architecture.md).

Voraussetzung: JDK 21. iOS-App bauen und starten geht nur auf macOS mit Xcode.

## Quality Gates

```bash
./gradlew qa                     # alle Gates: ktlint, detekt, Unit-Tests, iOS-Kompilierung, Zeit/Zufall-Prüfung
./gradlew ktlintFormat           # Formatierung beheben
```

## Stand

Fertig portiert und getestet (JVM-Tests, iOS-Kompilierung): Domain, Application, Speicher, Plattform,
Eingabe, i18n, Audio (PCM-Synthese, iOS-Ausgabe), Szenenmodell, 3D-Logik (Welt, Pferd, Reiter,
Grafikstufen, Engine), Filament-Backend, Bildschirm-Logik (`:adapters:presentation`) und die
Verdrahtung (`:app`, inkl. iOS-Framework). Ein Ende-zu-Ende-Test spielt Parcours 1 vom leeren
Spielstand bis zum Ergebnis durch den ganzen Stapel.

Noch offen (braucht Google Maven `dl.google.com` bzw. einen Mac):
- Compose-UI (`:adapters:ui`), die die Modelle aus `:adapters:presentation` zeichnet.
- Android-Target, Android-Actuals (SharedPreferences, AudioTrack, Geräte-Infos, Lebenszyklus) und App.
- Xcode-Projekt der iOS-App; Test auf echten Geräten (Metal, Audio, Material-Cache sind nur kompiliert).
