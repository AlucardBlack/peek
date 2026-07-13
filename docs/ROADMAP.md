# Peek — Development Roadmap

Draft roadmap derived from the codebase as of 2026-07-13 (v1.9.58). Peek is a
Kotlin/AndroidX WebView browser that opens links in a floating overlay bubble.
The Java→Kotlin migration is **complete** (110 `.kt`, 0 `.java`), so the work
below is about paying down remaining debt and moving forward, not finishing the
port.

Phases are ordered by risk/leverage: get the ground stable, then modernize,
then build. Each item lists the concrete anchors found in the tree.

---

## Phase 0 — Repo & build hygiene (fast wins)

- **CI pipeline.** No `.github/` — add a workflow that runs
  `npm install --ignore-scripts` → `copyJNIFiles` → `./gradlew assemblePlaystoreDebug`
  on PRs so builds can't rot. Codify the fresh-clone setup (manifest copy,
  `local.properties`, JNI copy) that the README documents by hand.
- **Fix the JNI setup script.** `scripts/copyJNIFiles.js` has a `.js` name but is
  a shell script; its C-style license header errors on run
  (`Application: command not found`). Rename to `.sh` or make it real JS, and
  fold `--ignore-scripts` into `postinstall` so plain `npm install` works
  (currently the native gyp build of transitive deps fails on Node 24).
- **Duplicate signingConfig.** `Peek/build.gradle` sets
  `signingConfig signingConfigs.release` twice in the `release` buildType —
  drop one.
- **No automated tests (0 test files).** Stand up a minimal JVM unit-test
  scaffold and cover the pure-logic units first: `Config`, `Constant`,
  URL/search handling in `Util`, the ad-block whitelist collector.

## Phase 1 — API modernization

- **Retire `AsyncTask`** (deprecated). Live usages in `MainApplication`
  (`InitWhiteListCollectorAsyncTask`, `DownloadAdBlockDataAsyncTask`) and the
  custom `org.mozilla.gecko.util.UiAsyncTask` used by favicon/bitmap loading.
  Move to coroutines (`kotlin-stdlib` already present; add
  `kotlinx-coroutines-android`).
- **`onBackPressed()` / `startActivityForResult`** — ~58 hits across the UI
  layer against deprecated APIs. Migrate to `OnBackPressedDispatcher` and the
  Activity Result API. Start with `ContentView` (four `onBackPressed`
  overrides).
- **Legacy theming.** `AndroidManifest` still applies
  `@android:style/Theme.Holo.Light` to `MainApplication` and some activities,
  despite the Material3 restyle. Finish moving everything to `AppTheme` and
  verify dynamic-color/dark-mode coverage.
- **`android.enableJetifier=true`** in `gradle.properties` — audit whether any
  dependency still needs it; dropping Jetifier speeds builds.

## Phase 2 — Dependencies & security

- ~~**jsoup 1.7.3 (2013).** Article-mode / snacktory HTML parsing runs on a
  decade-old jsoup with known CVEs. Bump to current 1.18.x and fix the
  `de.jetwick.snacktory` call sites.~~ **Done** — bumped to jsoup 1.18.3; the
  only breaking call site was `StringUtil.join` in `ExpandedActivity` (removed
  `org.jsoup.helper.StringUtil`, replaced with `joinToString`). Added a
  runtime extraction test (`ArticleTextExtractorTest`).
- **Unmaintained UI libs.** `se.emilsjolander:stickylistheaders` and
  `com.timehop.stickyheadersrecyclerview` are abandoned. Plan replacement with
  RecyclerView sticky-header patterns (RecyclerView already a dependency).
- **Dead analytics.** `util/Analytics` is a fully stubbed no-op still carrying a
  Universal Analytics property id (`UA-49396039-1`, a dead product). Either
  delete the plumbing (`Analytics.*` call sites in `MainApplication`,
  `MainController`, `Settings`) or wire a privacy-respecting replacement.
- **Ad-block data source.** Confirm the `DownloadAdBlockData` endpoint and
  filter lists (abp-filter-parser-cpp JNI engine) are still live and current.

## Phase 3 — Product & UX

- **TODO backlog (23 markers).** Notable functional gaps:
  `BubbleFlowDraggable` (`// TODO: Implement me`, line 82), OOM handling in
  `ContentView` (line 170), and DB cleanup in `DatabaseHelper`
  ("refactor db code in future", `closeZZZ()`).
- **Database layer.** `DatabaseHelper` is raw `SQLiteOpenHelper` with
  self-flagged tech debt. Evaluate migrating to Room for type safety and
  migrations.
- **WebView hardening.** ~18 files touch `WebView`/`WebSettings`. Audit for safe
  defaults (JS enablement scope, mixed content, file access, safe-browsing) and
  modern `WebViewAssetLoader` where applicable.
- **Bubble/overlay UX.** The custom physics engine (`physics/DraggableHelper`,
  self-noted "This probably fires… should be fixed") and gesture handling
  (`SwipeDismiss*`, `OnSwipeTouchListener`) are ripe for polish and haptics.

## Phase 4 — Release & distribution

- **Signing/release flow.** `build-release.sh` is templated with
  `PEEK_KEYSTORE_*` env vars — document a repeatable release checklist
  (CONTRIBUTING only covers bumping `versionPatch`).
- **ABI coverage.** `abiFilters` ships `arm64-v8a` + `x86_64` only. Decide
  whether `armeabi-v7a` (older 32-bit devices, minSdk is 26) is worth including.
- **Play compliance.** targetSdk 35 is current. Track the next required-target
  bump and the SDK 35+ edge-to-edge / predictive-back requirements (ties into
  the `onBackPressed` migration in Phase 1).

---

### Suggested sequencing

1. Phase 0 (CI + tests + build fixes) — unblocks safe iteration.
2. Phase 2 jsoup bump — highest security/effort ratio.
3. Phase 1 AsyncTask→coroutines — clears the biggest deprecation cluster.
4. Interleave Phase 3 product work against the TODO backlog.

*Not yet prioritized against user demand — treat ordering as engineering-risk
based, adjust once product goals are set.*
