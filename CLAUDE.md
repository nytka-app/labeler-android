# Nytka Labeler

Native Android app for the owner of a Nytka server (a self-hosted second brain: an Omi pendant records his day, a
.NET server transcribes it). It plays short clips (20 s or less, Ogg Opus) that the server's models are least sure
about and he taps what kind of audio each is. The labels train and evaluate the server's models. A session is about
30 clips. The only server interface is `/api/v1/labeling`: the contract is `docs/api.md` in the server's labeler
folder (`nytka/labeler/docs/api.md`), the design `nytka/labeler/DESIGN.md`. The web labeler uses the same routes.

## Stack

Kotlin 2.4 (AGP 9 built-in Kotlin) · Jetpack Compose, Material 3 · Hilt with KSP · Room · DataStore with a
Keystore-encrypted token · WorkManager · Media3 ExoPlayer and MediaSession · OkHttp, kotlinx.serialization · JUnit 4,
Robolectric, Compose UI tests, MockWebServer · ktlint, detekt. Versions and plugin setup are copied from the main
Nytka Android app (`nytka/android`); package root `io.github.nytka_labeler` mirrors its `io.github.nytka_app`.
One `:app` module.

| Package | Holds |
|---|---|
| `api/` | wire types (`LabelingModels`, `Answer`), `LabelingClient` and `LabelingApi` (OkHttp), `ServerUrl` |
| `settings/` | `Settings`, `SettingsStore` (DataStore), `TokenCipher` (Keystore AES-GCM) |
| `outbox/` | `Outbox` (Room), `OutboxSender` (the send rules), `SendWorker` and `SendScheduler` |
| `session/` | `SessionRepository` (lease and download), `ClipStore`, `SessionViewModel` |
| `play/` | `ClipPlayer` (ExoPlayer + MediaSession), `HeadphoneMonitor`: autoplay with sound only on headphones, else a tap (setting "Autoplay on speaker") |
| `remind/` | `Reminder`, `ReminderWorker`, notification |
| `ui/` | Compose screens and their view models; `SessionScreen` is the vertical feed (`VerticalPager`, `FeedPage` swipe card, `Rail`, `AnswerSheet`, `EndView`), design in `nytka/labeler/DESIGN.md` section 7 |

## Commands

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home   # CI uses Temurin 21
./gradlew check assembleDebug          # what CI runs
./gradlew :app:testDebugUnitTest --tests '*EveryAnswerSavesTest'
./gradlew ktlintFormat                 # before every commit
./gradlew installDebug && adb logcat | grep -iE "labeler|AndroidRuntime"
./scripts/make-tone.sh                 # rebuilds the synthetic test clip (needs ffmpeg)
```

`local.properties` needs `sdk.dir=...` (gitignored).

## Invariants

1. **A gesture or a tap is an answer.** Every value of the asked axis (the long-press sheet) and every optional row has
   a test that taps it through the Compose UI and compares the exact JSON that reaches a MockWebServer
   (`EveryAnswerSavesTest`); every task's swipes and rail buttons do the same, `answeredWith` included
   (`FeedGesturesTest`, `FeedRailTest`). An earlier labelling page silently lost every label to a button handler bug.
   Add a value, a gesture or a button, add its case. Never loosen those tests to make a change pass.
2. **An answer leaves the outbox only on a `200`.** `400` parks it with the field error, `404` parks it, `401` or
   `403` stops the run and sets `tokenRefused` until a new token is saved, anything else retries with backoff. The
   body is stored as sent, so a retry repeats the same `clientId`, and the server treats it as the same answer.
   An answer is queued with `notBefore` 4 s ahead (the undo window): `OutboxSender` sends only due rows, Undo
   deletes a pending row, and `SendWorker` waits for the next due time.
3. **A saved tick needs the `200`.** `Outbox.sent` is the only proof the UI reads; "no row in the outbox" is not.
4. **A session works offline.** `SessionRepository` downloads context 0 and 4 of every clip to
   `noBackupFilesDir/clips/<sessionId>/` before the first card. Clips are deleted when the session has ended and none
   of its answers is pending (`ClipStore.sweep`); parked answers do not hold them.
5. **HTTPS is enforced in code**, as in the main app: the network security config permits cleartext because Android
   cannot allow it per server; `ServerUrl` refuses `http://` unless the private-network switch is on. Redirects are
   not followed.
6. **Nothing sensitive in logs, errors or `toString`:** no audio, no transcript text, no token, no server address.
   `Settings`, `Answer`, `OutboxRow` and `ClipLine` print without them; a failure carries a fixed sentence or the
   system's message, never the server's text, except the field message of a `400`. The app has no log calls and no
   OkHttp logging interceptor.
7. **Never `RECORD_AUDIO`.** The app only plays clips.
8. **Real voices never enter this repository.** The only audio is `app/src/test/resources/tone.ogg`, a 0.5 s sine
   made by `scripts/make-tone.sh`. Test text is invented.
9. **The token is sent in the `Authorization` header only**, never in a URL, and stored Keystore-encrypted.
10. **The headphones hint, audio focus, pause on unplug and headset play/pause** come from `ExoClipPlayer`
    (`setHandleAudioBecomingNoisy`, audio focus on, a `MediaSession` over the player) and `HeadphoneMonitor`; they
    are not unit-tested (no audio stack in Robolectric): check them on a phone.

## Conventions

- AGP's built-in Kotlin: never apply `org.jetbrains.kotlin.android`; KSP, never kapt.
- Hand-written fakes, no mocking library (`support/Fakes.kt`, `FakeLabelingServer`). View models take interfaces
  (`LabelingClient`, `SessionSource`, `ClipPlayer`, `SendScheduler`, `SettingsSource`), so their tests need no
  Android beyond Robolectric's.
- Room schemas under `app/schemas/` are committed. A schema change bumps the database version and adds a
  migration, never a destructive fallback: an unsent answer cannot be given again.
- New axis values are appended on the server and never renamed (`taxonomyVersion`); the codes in `api/LabelingModels.kt`
  follow `docs/api.md`.
- Conventional commits. Every commit uses a GitHub noreply address.
