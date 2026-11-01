# VideoDL — Android video downloader (Kotlin + Jetpack Compose)

A working project skeleton for a downloader app with:

- link input + **share-sheet intake** (`ACTION_SEND`),
- dynamic quality selection (HLS variants, direct files, optional yt-dlp resolver),
- **parallel, resumable** HTTP range downloads and parallel HLS segment downloads,
- **foreground service** with live progress in a notification,
- downloads published into `Downloads/VideoDL` via MediaStore.

> **Use responsibly.** Download only media you own, that is licensed for
> download, or where the platform's terms allow it. The app deliberately
> rejects encrypted/DRM HLS and does not bypass access controls. Fetching
> from YouTube/TikTok/Instagram generally conflicts with their terms of
> service — that is a legal/product decision for you, not a technical one.

---

## 1. Architecture

```
 Share sheet (ACTION_SEND)          Text field
        │                              │
        └──────────► MainActivity ◄────┘
                          │
                  DownloadViewModel
                    │            │
             analyze()      download()
                    │            │
                    ▼            ▼
            ExtractorRegistry  DownloadEngine ─────► DownloadStore (history JSON)
            ┌───────┼────────┐        │
     HlsExtractor  Direct   YtDlp      ├─ ChunkedDownloader  (progressive, Range)
            └───────┴────────┘        └─ HlsDownloader      (segments, parallel)
                    │                        │
              MediaInfo + streams            ▼
                                     cacheDir/<id>.download(.part*)
                                             │
                                       MediaSink.publish()
                                             ▼
                                 MediaStore → Downloads/VideoDL
                                             ▲
                              DownloadService (FGS, notifications)
```

Key split: **extraction** (URL → `MediaInfo` with a list of `MediaStream`)
is independent from **transfer** (`MediaStream` → file). Adding a new
platform means adding one `MediaExtractor`, not touching the downloader.

### Module map

| Path | Responsibility |
| --- | --- |
| `core/model/MediaModels.kt` | `MediaInfo`, `MediaStream`, `DownloadTask`, progress math |
| `core/extract/ExtractorRegistry.kt` | picks an extractor, normalizes URLs |
| `core/extract/DirectExtractor.kt` | HEAD/1-byte probe, content-type → stream |
| `core/extract/HlsExtractor.kt` | master playlist → variants, media playlist → segment stream |
| `core/extract/YtDlpRemoteResolver.kt` | HTTP client for a self-hosted yt-dlp service |
| `core/download/ChunkedDownloader.kt` | 4-way Range download, per-chunk resume, merge verify |
| `core/download/HlsDownloader.kt` | segment fetch (semaphore), byte ranges, init segment |
| `core/download/DownloadEngine.kt` | task state machine, cancellation, resume, history |
| `core/download/DownloadService.kt` | foreground service + notification mirror |
| `core/download/MediaSink.kt` | MediaStore (API 29+) / legacy Downloads publish |
| `ui/*` | Compose screen + `AndroidViewModel` |

---

## 2. Download semantics

**Progressive (mp4/webm/m4a/mp3)**

1. Size from the extractor hint or a `HEAD` request.
2. If size > 1 MB: split into `ceil(total/4)` byte ranges, one
   `target.partN` file each, downloaded concurrently.
3. A part resumes from its own file length:
   `Range: bytes=(start+have)-end`. Interrupted downloads resume for free.
4. If a server ignores `Range` (returns `200`), the partials are deleted and
   the file is fetched as a single stream.
5. Merge parts, then assert `merged.length == total` before publishing.

**HLS**

1. Master playlist → highest-bandwidth variant (quality selection happens
   earlier in `HlsExtractor`).
2. `#EXT-X-MAP` init segment is written first (fMP4).
3. Segments download concurrently (4 at a time) into
   `target.segments/00000.bin…`, then concatenate in playlist order.
4. `#EXT-X-BYTERANGE` is honored; `#EXT-X-KEY` (AES/DRM) is rejected.
5. Output is the raw transport stream. **No remux/transcode** (see limits).

**Pause vs cancel**

- Pause cancels the coroutine but keeps `.part` files → Resume continues.
- Cancel deletes the work files and marks the task `CANCELED`.

---

## 3. Build

Requirements: JDK 17, Android SDK 35, Android Studio Koala (or newer).

### Android Studio

1. **File → Open** → select this folder.
2. Let Gradle sync (it downloads AGP 8.6.1, Kotlin 2.0.21, Compose BOM).
3. Run `app` on a device/emulator with API 26+.

### Command line

The Gradle wrapper (scripts + `gradle-wrapper.jar`) is included, so no local
Gradle install is needed:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. The debug APK is signed with the auto-generated
debug keystore, so it installs on any device ("unknown developer" warning is
expected for sideloading).

Release build (unsigned until you add a keystore):

```bash
./gradlew :app:assembleRelease
```

CI: `.github/workflows/build.yml` builds `app-debug.apk` on every push to
`main` and uploads it as a downloadable artifact (Actions → run → Artifacts).

`local.properties` must point at your SDK (`sdk.dir=/path/to/Android/Sdk`);
Android Studio writes it automatically.

---

## 4. Runtime behavior / Android specifics

| Topic | Behavior |
| --- | --- |
| Share intake | `ACTION_SEND` + `text/plain` on `MainActivity` (singleTask) |
| Why not a `BroadcastReceiver` | Share-sheet intents are delivered to an activity; a receiver would never fire |
| Foreground service | `dataSync` type, declared `FOREGROUND_SERVICE_DATA_SYNC` |
| Android 13+ | Runtime `POST_NOTIFICATIONS` request in Compose |
| Storage | MediaStore `Downloads/VideoDL` on API 29+; legacy public Downloads below |
| File names | Sanitized, truncated to 80 chars |
| History | `filesDir/history.json`, capped at 200 entries |

**Note on Android 14+:** `dataSync` foreground services have runtime limits.
For hour-long downloads, move to `WorkManager` with
`setForeground(...)` (long-running worker) or use `userInitiated` data
transfer jobs. The engine is independent of the service, so this is a
drop-in change: enqueue the task, let the worker call `engine.launch(id)`.

---

## 5. Platform links: yt-dlp integration

A raw GET on `youtube.com/watch?v=…` returns HTML. There are three options:

### A. Remote resolver (implemented, recommended for a real app)

The app POSTs `{"url": …}` to `YTDLP_BASE_URL + "/resolve"` and expects:

```json
{
  "title": "…",
  "thumbnail": "…",
  "duration": 123.4,
  "formats": [
    {
      "format_id": "137",
      "url": "https://…",
      "ext": "mp4",
      "height": 1080,
      "vcodec": "avc1.640028",
      "acodec": "none",
      "filesize": 12345678,
      "tbr": 4500.0,
      "format_note": "1080p",
      "protocol": "https",
      "http_headers": { "User-Agent": "…", "Referer": "…" }
    }
  ]
}
```

Set the endpoint in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "YTDLP_BASE_URL", "\"https://resolver.example.com\"")
```

A minimal FastAPI service is in [`tools/ytdlp-resolver/`](tools/ytdlp-resolver/).
Run it close to your users; it needs `yt-dlp`, outbound network, and regular
package updates. Cleartext is only permitted for `127.0.0.1` / `localhost` /
`10.0.2.2` (see `network_security_config.xml`) so a local emulator service
works without weakening TLS.

**DASH caveat:** YouTube mostly serves separate video and audio streams.
`YtDlpRemoteResolver` marks video-only renditions (`acodec == "none"`) in the
quality label. This app does not mux tracks, so a "video only" download is
silent. For a production build, either prefer muxed formats
(`acodec != none`), add `MediaMuxer` muxing for MP4 tracks, or use
FFmpegKit. The resolver example defaults to preferring muxed formats.

### B. On-device Python (Chaquopy)

Add `com.chaquo.python` and `pip { install("yt-dlp") }`, then:

```kotlin
val py = Python.getInstance()
val ydl = py.getModule("yt_dlp")
val json = ydl.callAttr(
    "YoutubeDL", mapOf("quiet" to true, "skip_download" to true)
).callAttr("extract_info", url, false).callAttr("__str__").toString()
```

Cost: ~40–60 MB APK, ABI splits, slow cold start. It also still needs a JS
runtime for some YouTube signatures.

### C. NewPipe Extractor (Java, no Python)

Pure-Java extractors for YouTube/SoundCloud/etc. Integrate it behind the
same `MediaExtractor` interface. Be aware that its use for downloading
YouTube content conflicts with YouTube's ToS regardless of the library.

---

## 6. Test plan (manual)

| Case | Expected |
| --- | --- |
| Direct MP4: `https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4` | size shown, 4 parts, completes, file in Downloads/VideoDL |
| HLS master: `https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8` | variant list (e.g. 1080p/720p/…), segments counter, `.ts` output |
| Share a link from Chrome → VideoDL | app opens with URL filled and analyzing |
| Airplane mode mid-download, then Retry | resumes from `.part` offsets, not from zero |
| Cancel from notification | FGS notification disappears, partials deleted |
| Android 13 device, deny notifications | download still runs; no crash |
| Platform link without resolver | clear error explaining `YTDLP_BASE_URL` |

HLS segment resume note: finished segments are kept in
`target.segments/`, so an interrupted HLS download resumes at the segment
level.

---

## 7. Known limits / next steps

- **No muxing or transcoding.** HLS output is `.ts`/fMP4; "MP3" for a video
  source is not produced. Add FFmpegKit or `MediaMuxer` if needed.
- **No DRM**, and encrypted HLS is rejected explicitly.
- **One foreground notification** for the first running task (tasks still
  all run; the notification shows the first one).
- **No cross-process resume:** if Android kills the process, tasks are lost.
  Add task persistence + `WorkManager` for production.
- **DASH manifests** are not parsed; only progressive + HLS.
- **Quality labels** are whatever the extractor reports (`1080p`,
  `audio · 128 kbps`, `source`).
- Playlist/adaptive audio renditions are surfaced as separate streams, not
  muxed with video.

---

## 8. What was verified here

- Range-split math and HLS master/media parsing were mirrored in Python and
  tested across edge sizes (1 B … 123 MB) and playlists with `BYTERANGE`,
  `EXT-X-MAP`, and audio-only variants.
- All Kotlin files pass a brace/paren/bracket balance check; no placeholders.
- **Not compiled:** the sandbox has no JDK/Android SDK and the JVM cannot
  execute here, so `./gradlew assembleDebug` is the first real build.
  Expect to fix trivial import/version issues on first sync.
