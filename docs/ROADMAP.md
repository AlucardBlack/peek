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

- ~~**Retire `AsyncTask`** (deprecated). Live usages in `MainApplication`
  (`InitWhiteListCollectorAsyncTask`, `DownloadAdBlockDataAsyncTask`) and the
  custom `org.mozilla.gecko.util.UiAsyncTask` used by favicon/bitmap loading.
  Move to coroutines (`kotlin-stdlib` already present; add
  `kotlinx-coroutines-android`).~~ **Done** — added
  `kotlinx-coroutines-android:1.11.0`. Converted the 4 real
  `android.os.AsyncTask` classes found (`MainApplication`'s two, plus
  `DownloadImage.DownloadImageTask` and `ArticleContent.BuildContentTask`,
  the latter needing `Job`-backed `cancel()` support for
  `WebViewRenderer`'s cleanup path) to direct `CoroutineScope(...).launch {
  withContext(Dispatchers.IO) { ... } }` calls. `UiAsyncTask` itself (used by
  `LoadFaviconTask`'s chaining logic and `BitmapUtils.getDrawable()`) was
  reimplemented on coroutines internally — `Handler.asCoroutineDispatcher()`
  preserves the exact background thread callers already pass in, and
  cancellation stayed a plain `@Volatile` flag (not `Job.cancel()`) so
  `onCancelled()` keeps firing to clean up `LoadFaviconTask`'s in-flight-loads
  map, matching the original Handler-based semantics exactly. Zero call-site
  changes needed for `LoadFaviconTask`/`BitmapUtils`/`Favicons`.
- ~~**`onBackPressed()` / `startActivityForResult`**~~ **`onBackPressed()` done,
  `startActivityForResult` deferred.** Actual count was **16** hits across 6
  files (the "~58" estimate above was stale), not as large as first thought.
  Of those, only 2 were real deprecated `Activity.onBackPressed()` overrides —
  `EntryActivity` and `ExpandedActivity` (the `ContentView.kt`/`WebRenderer`/
  `ArticleRenderer` `onBackPressed()`s are this app's own internal
  back-handling protocol for the floating-bubble overlay window, which has no
  `Activity` to hand a callback to, and aren't part of the deprecated Android
  API at all). Both `EntryActivity`/`ExpandedActivity` extended plain
  `android.app.Activity`, so using `OnBackPressedDispatcher` meant migrating
  them to `AppCompatActivity` first — done, along with moving
  `TransparentTheme`/`ExpandedModeBaseTheme` off `Theme.Holo.Light` onto
  `Theme.Material3.Light.NoActionBar` (`ExpandedActivity`'s native action bar
  was hidden immediately in `onCreate()` and never shown again, so it was
  dropped rather than ported to a support Toolbar; `ExpandedModeActionBar`
  style deleted as dead with it). `startActivityForResult` isn't actually
  flagged deprecated by the compiler on either class (`ExpandedActivity`'s
  WebView file-chooser flow, `EntryActivity`'s vestigial one) — left as-is,
  revisit only if/when there's a concrete reason to touch the Activity Result
  API.
- **Legacy theming.** `AppTheme` is already Material3-modernized. Gap is the
  `<application>` default theme and 6 activities (`EntryActivity` + 5
  `Notification*Activity`s via `TransparentTheme`, plus `ExpandedActivity` via
  `ExpandedModeTheme`) still on `Theme.Holo.Light`-derived styles. Also found
  4 apparently-dead Holo styles in `styles.xml` (`ActiveTheme`,
  `ContentThemeBase`/`ContentTheme`, `SectionHeaderTheme`) with zero
  references — cleanup candidates alongside the migration.
- **`android.enableJetifier=true`** in `gradle.properties` — no
  `com.android.support` dependency anywhere in the declared dependency list,
  so it looks safe to disable on paper, but transitive dependencies could
  still need it; confirming requires an actual build with the flag off, not
  just static analysis.

## Phase 2 — Dependencies & security ✅ Done

- ~~**jsoup 1.7.3 (2013).** Article-mode / snacktory HTML parsing runs on a
  decade-old jsoup with known CVEs. Bump to current 1.18.x and fix the
  `de.jetwick.snacktory` call sites.~~ **Done** — bumped to jsoup 1.18.3; the
  only breaking call site was `StringUtil.join` in `ExpandedActivity` (removed
  `org.jsoup.helper.StringUtil`, replaced with `joinToString`). Added a
  runtime extraction test (`ArticleTextExtractorTest`).
- ~~**Unmaintained UI libs.** `se.emilsjolander:stickylistheaders` and
  `com.timehop.stickyheadersrecyclerview` are abandoned.~~ **Done** —
  `com.timehop.stickyheadersrecyclerview` was unused, dropped outright. Wrote
  a small reusable `StickyHeaderItemDecoration`/`StickyHeaderInterface` pair
  (`util/`) implementing sticky headers as a RecyclerView `ItemDecoration`,
  and migrated the two real usages: `FAQDialog` (now backed by a
  `RecyclerView.Adapter`) and `ActionItem.getConfigureBubbleAlert()` (new
  `ActionItemRecyclerAdapter`; the other 3 `ActionItem` dialogs keep the
  plain `ArrayAdapter`/`ListView` they always used, since they never rendered
  headers). Removed the `stickylistheaders` Gradle dependency, its
  `attrs.xml` styleable block, and the now-unused `view_faq.xml` layout.
- ~~**Dead analytics.** `util/Analytics` is a fully stubbed no-op still
  carrying a Universal Analytics property id (`UA-49396039-1`, a dead
  product).~~ **Done** — deleted the no-op tracking functions
  (`init`/`trackOpenUrl`/`trackTimeSaved`/`trackScreenView`/
  `trackUpgradePromptDisplayed`/`trackUpgradePromptClicked`), the dead
  `UPGRADE_PROMPT_*` constants (zero call sites), and the GA property id.
  The `OPENED_URL_FROM_*` constants were actually load-bearing source tags
  (drive real branching in `MainController.openUrl`, not just telemetry), so
  they moved to a new `util/SourceTag` object rather than being deleted.
- ~~**Ad-block data source.** Confirm the `DownloadAdBlockData` endpoint and
  filter lists (abp-filter-parser-cpp JNI engine) are still live and
  current.~~ **Done** — verified `https://easylist.to/easylist/easylist.txt`
  and `.../easyprivacy.txt` both return `200` with working ETags (matches
  `ADBlockUtils`' caching logic); no code change needed.

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

1. ~~Phase 0 (CI + tests + build fixes) — unblocks safe iteration.~~ Done.
2. ~~Phase 2 (jsoup bump, dead UI libs, dead analytics, ad-block source
   check) — highest security/effort ratio.~~ Done.
3. **Phase 1 AsyncTask→coroutines — next up.** Clears the biggest
   deprecation cluster.
4. Interleave Phase 3 product work against the TODO backlog.

*Not yet prioritized against user demand — treat ordering as engineering-risk
based, adjust once product goals are set.*
