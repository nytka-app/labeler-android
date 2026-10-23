# Nytka Labeler

An Android app for tapping what kind of audio a short clip is. It is the phone half of the labelling tool of a
self-hosted [Nytka](https://github.com/Egoushka) server: an Omi pendant records the owner's day and the server
transcribes it. The server picks the clips its models are least sure about, the owner listens (best through
headphones) and taps one answer per clip, and the labels train and evaluate those models. A session is about 30
clips, around five minutes, and it works on a train with no signal.

## What it does

- **Tasks.** Device or live voice, my voice, quiet lines, transcript, overlap. Each shows how many clips are open and
  the progress toward its goal.
- **A session.** The app leases 30 clips, downloads each with 4 seconds of context to private storage, then shows one
  card at a time: play and replay, a Context button, the line's text, and big buttons for the question the task asks.
  - Source: Me, Live person, Device (then an optional device type), Call, No voice, Can't tell, Several (pick two).
  - Transcript: Right, Small errors, Wrong words (a correction field with the text in it), Made up, Can't hear.
  - Overlap: One, Two, Three or more, Can't tell.
  - Optional on every card: quality flags, language, and "Lines differ" on a card with several lines.
  - Skip, Back, and "12 of 30" progress. A tick appears only after the server confirmed the answer.
- **Offline.** Answers wait in a local database and are sent in the background when there is a network. A refused
  answer shows the server's field message; a refused token asks for a new one.
- **A reminder.** Every 6 hours the app asks how many clips wait and, if some do and you have not labelled today,
  notifies "N uncertain clips wait". It can be switched off on the task screen.
- **Headphones.** Playback takes audio focus, pauses when the headphones are unplugged, and the headset's play/pause
  button works on the current clip. The task screen says so when no headphones are connected.

## Set up

You need a Nytka server that has the labeling routes (`/api/v1/info` lists `labeling` in `features`) and a token of
the **`labeling`** scope. That scope reaches `/api/v1/labeling/*` and nothing else, so a lost phone exposes no
conversations. Create it in the server's token settings.

1. Install the app (build below).
2. Enter the server address (`https://...`) and paste the token. Plain `http://` is refused unless you switch on the
   private-network option, for a server on your own LAN.
3. Allow notifications on Android 13 or later if you want the reminder.

The token is stored encrypted with a key in the Android Keystore, sent only in the `Authorization` header, and never
written to a log.

## Privacy

- The app never records: it has no `RECORD_AUDIO` permission and only plays clips from your server.
- Clips live in the app's private no-backup storage, are excluded from cloud backup and device transfer, and are
  deleted when a session has ended and its answers have all been sent.
- No transcript text, audio or token goes to a log, an error message or a debug report.
- The only host the app talks to is your server. There is no analytics and no third party.
- This repository holds no real voices. The one audio file is a synthetic tone (`scripts/make-tone.sh`).

## Build

Needs JDK 21 and the Android SDK (put its path in `local.properties` as `sdk.dir=...`).

```bash
./gradlew check assembleDebug     # unit tests, lint, ktlint, detekt, then the debug APK
./gradlew installDebug            # to a connected phone
./gradlew ktlintFormat            # before a commit
```

The tests that matter most: `EveryAnswerSavesTest` taps every button of every task through the real screen and checks
the exact JSON each answer sends; `OutboxTest` covers process death, retries and the status codes; `OfflineSessionTest`
runs a session with no network and sends it when the network returns.

## Server contract

`docs/api.md` in the server's labeler folder is the only interface (labeling API v1). Axis values are fixed codes; new
ones are appended on the server and unknown keys in answers are ignored.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`.
