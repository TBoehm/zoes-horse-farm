# Zoe's Horse Farm – native App (Android + iOS)

Kotlin Multiplatform, Compose Multiplatform und Google Filament (über filament-kmp). Nachbau der
Web-App aus `../src/`. Architektur und Stand: [docs/specs/kmp-cmp/architecture.md](../docs/specs/kmp-cmp/architecture.md).

Voraussetzung: JDK 21. iOS-App bauen und starten geht nur auf macOS mit Xcode.

## Quality Gates

```bash
./gradlew qa                     # alle Gates: ktlint, detekt, Unit-Tests, iOS-Kompilierung, Zeit/Zufall-Prüfung
./gradlew ktlintFormat           # Formatierung beheben
```
