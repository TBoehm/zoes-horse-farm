# `:adapters:storage` – the save game on the device

Port of `src/adapters/storage/local-store.js`. Package `app.zoeshorsefarm.storage`, depends on
`:core:application` (the `Store` port and the save sections) and kotlinx.serialization.

| Kotlin | Web | Role |
| --- | --- | --- |
| `LocalStore` | `createStore` | The `Store`: sanitized `get`/`update`, `updateThrough` (one section against the persisted state), `flush`, `onChange`, `onSaveFailed`, `canSave`, `shouldShowSaveNotice` |
| `KeyValueBackend` | `localStorage` | Port for persistence: `getString` / `setString` / `remove`, may throw |
| `ProcessSession.backend` | `sessionStorage` | In-memory backend that lives as long as the app process ("notice once per session") |
| `NoticeMarker` | `history.state` | Optional second "notice shown" mark when no session backend works |
| `decodeJsonTree` / `encodeJsonTree` | `JSON.parse` / `JSON.stringify` | Save text <-> `Map`/`List`/`String`/`Long`/`Double`/`Boolean`/`null` tree |

Unknown sections and unknown fields stay in the tree, so a save written by a newer version is not
damaged. A backend that throws on read counts as "no save", one that throws on write sets `canSave`
to false and emits `saveFailed`; the game then runs from memory.

## Backends

| Platform | Class | Notes |
| --- | --- | --- |
| iOS (`iosMain`) | `UserDefaultsKeyValueBackend` | `NSUserDefaults`; the system persists it, write errors are not reported |
| JVM (`jvmMain`) | `FileKeyValueBackend(dir)` | One file per key, atomic replace; tests and desktop development |
| any | `MemoryKeyValueBackend` | Tests, session backend |
| Android | not yet | `SharedPreferences` backend comes with the Android target (needs the Android Gradle Plugin): `getString`/`edit().putString(...).commit()` (use `commit()`, not `apply()`, so a failed write is reported) |

Not ported: `requestPersistentStorage` (browser only).

The store is not thread-safe: use it from the thread that runs the game logic.
