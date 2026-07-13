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

## Phase 1 — API modernization ✅ Mostly done (startActivityForResult deferred)

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
- ~~**Legacy theming.**~~ **Done** — `<application>`'s default theme moved
  from `@android:style/Theme.Holo.Light` to `@style/AppTheme` (only
  `DefaultBrowserResetActivity` — an empty trampoline class with no
  `onCreate` at all — was inheriting it, so this was a no-visual-risk
  change). `TransparentTheme`/`ExpandedModeBaseTheme` were already moved off
  Holo in the `onBackPressed()` migration above, so `EntryActivity` + the 5
  `Notification*Activity`s and `ExpandedActivity` were already covered.
  Deleted the confirmed-dead Holo styles (`ActiveTheme`,
  `ContentThemeBase`/`ContentTheme`, `ActionBarStyle.Translucent`/
  `.TitleTextStyle` — zero references anywhere). One survey miss corrected:
  `SectionHeaderTheme` looked dead but is actually live (`view_section_header.xml`,
  used by the sticky-header dialogs from Phase 2) — its `Theme.Holo.Light`
  parent was vestigial regardless (applied via `style=` on a plain
  `LinearLayout`, never consulted as an `android:theme`), so the parent was
  dropped rather than the whole style. No remaining `Theme.Holo` references
  outside the dead, already-commented-out `BubbleFlowActivity` manifest block.
- ~~**`android.enableJetifier=true`** in `gradle.properties`~~ **Done** —
  flipped to `false`. `:Peek:dependencies` confirmed zero `com.android.support`
  artifacts anywhere in the resolved runtime classpath (declared or
  transitive); a clean `assemblePlaystoreDebug` + `testPlaystoreDebugUnitTest`
  with the flag off both pass. (`assemblePlaystoreRelease` wasn't usable as a
  cross-check either way — it fails before Jetifier even enters the picture,
  on the missing `PEEK_KEYSTORE_*` env vars from the Phase 4 release-signing
  item; confirmed identical failure with the flag untouched.)

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

## Phase 3 — Product & UX ✅ Mostly done (haptics polish deferred)

- **TODO backlog.** Actual count is **20** markers, not 23 (stale estimate).
  ~~`BubbleFlowDraggable`'s `// TODO: Implement me` (line 82)~~ **Done** —
  vertical swipe on the centered tab now closes it via
  `MainController.closeTab(tabView, true, true)`, matching the app's other
  close-tab call sites (animated, with the undo prompt). ~~OOM handling in
  `ContentView.AppForUrl.getIcon()` (line 170)~~ **Done** — `loadIcon()` is now
  wrapped in `try/catch (OutOfMemoryError)`. The `DatabaseHelper` "refactor db
  code in future"/`closeZZZ()` markers were mostly noise (`closeZZZ()` was in
  dead, fully-commented-out code) but surfaced two real bugs while looking —
  see Database layer below. Remaining ~17 are minor cosmetic/naming nits or in
  vendored code (`de/jetwick/snacktory`, `org/mozilla/gecko/favicons`) — not
  worth one-off fixes; a couple (duplicate "Twitter class name" TODO in
  `MainApplication.kt`/`Util.kt`, the `SwipeDismiss*` "ensure this is a
  finger" gesture nits) are candidates if the Database/WebView/Bubble items
  below get picked up.
- ~~**Database layer.**~~ **Done** — `DatabaseHelper` migrated to Room
  (`androidx.room:room-runtime:2.8.4` + KSP `room-compiler`). Kept the exact
  same public API (`DatabaseHelper(context)`, same method signatures) so the
  6 external consumer files (`MainApplication`, `HistoryActivity`,
  `SettingsActivity`, `BubbleView`, `LoadFaviconTask`,
  `SearchURLSuggestionsContainer`) needed zero changes — it's now a thin
  facade over `HistoryDao`/`FaviconDao` (new `PeekDatabase`,
  `HistoryRecordEntity`, `FaviconEntity`; `HistoryRecord` itself untouched,
  since other files construct it directly). Real version-2 `Migration(1, 2)`
  replaces the old destructive `onUpgrade()` drop-and-recreate — rebuilds
  both tables to Room's expected schema (matching entity nullability: `time`/
  `imageSize` `NOT NULL`, `title`/`url`/`host`/`pageUrl`/`data` nullable)
  while preserving existing rows, rather than assuming the old hand-rolled
  schema already happens to match. Verified the migration's raw SQL directly
  against a scratch SQLite file seeded with the legacy schema: data survived
  intact and `PRAGMA table_info` confirmed the resulting `NOT NULL` flags
  match the entities exactly. Kept `.allowMainThreadQueries()` — several call
  sites (`HistoryActivity`, `SettingsActivity`,
  `SearchURLSuggestionsContainer`, `MainApplication.saveUrlInHistory`) call
  this synchronously, some from the main thread, same as the old
  `SQLiteOpenHelper` always allowed; converting to suspend/Flow DAOs and
  threading coroutines through all 6 call sites was judged out of scope for
  this pass (bigger, harder to verify without a device this session) — worth
  revisiting separately if those call sites ever need to move off the main
  thread anyway. No Robolectric/instrumented test added (none exists in this
  project yet) — verification leaned on Room's compile-time query validation
  (KSP passed clean) plus the manual SQLite migration check above.
- ~~**WebView hardening.**~~ **`WebSettings` defaults audited, JS-bridge
  hardened.** 17 files touch `WebView`/`WebSettings` (close to the "~18"
  estimate). Settings themselves are already reasonably safe: mixed content
  is on the API-21+ default (`MIXED_CONTENT_NEVER_ALLOW`, never overridden),
  `allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs` are never
  touched (both default `false`), the reader-mode WebView
  (`ArticleRenderer`) never enables JS at all, and `WebViewAssetLoader` isn't
  needed since nothing serves local assets via `file://` today.
  `PageInspector`'s `JSEmbedHandler` is the one `@JavascriptInterface` bridge,
  installed on the main browsing WebView (JS enabled for all pages) - audited
  and fixed two real issues: (1) `onSelectElementInteract` had no guard
  against a malicious page calling it directly and repeatedly (bypassing the
  app's own `SelectElements.js`), spamming native `AlertDialog`s with
  attacker-chosen text - now dismisses any dialog already showing before
  creating a new one. (2) `fetchHtml`'s `html` argument is fully
  JS-controlled and was unbounded, letting a page force repeated
  jsoup/snacktory extraction on arbitrary-size payloads - capped at 5MB.
  Traced `fetchHtml`'s other argument, `windowUrl` (also JS-supplied,
  unauthenticated): confirmed it only feeds the local
  `tryForArticleContent()` eligibility check - the domain actually
  *displayed* in reader mode comes from `WebViewRenderer`'s own tracked
  `getUrl()`, not this parameter, so origin spoofing isn't possible there.
  Expanded the class's existing security comment to document that
  `addJavascriptInterface` exposes these methods to *any* script in the
  page's top-level window, not just the app's own injected scripts - the
  underlying reason both issues above were reachable.
- ~~**Bubble/overlay UX.**~~ **Suspect assertion + gesture nits fixed; haptics
  not started.** Traced `DraggableHelper.clearTargetPos()`'s self-noted
  "This probably fires… should be fixed" assertion to its root cause:
  `setTargetPos()` sets `mAnimationListener` unconditionally, then for
  near-instant durations (`tIn < 0.0001f`) calls `clearTargetPos()` — whose
  assertion expects no pending listener. Worse, that branch never actually
  invoked the listener's `onAnimationComplete()` at all, a real dropped-
  callback bug independent of the assertion noise. Fixed by nulling
  `mAnimationListener` before `clearTargetPos()` and firing the callback
  explicitly after the snap, mirroring the normal animation-completion path
  in `update()`. (Checked every current `setTargetPos()` call site first —
  none currently combine a near-zero duration with a non-null listener, so
  this wasn't actively firing today, but the fix closes the landmine for any
  future call site.) Also fixed the two "ensure this is a finger" TODOs in
  `SwipeDismissTouchListener`/`SwipeDismissListViewTouchListener` (ignore
  stylus/mouse/eraser `MotionEvent.getToolType()`, not just plain touch) and
  the "use an ease-out interpolator" TODO (both files' linear alpha-fade
  during swipe now runs through a `DecelerateInterpolator`, applied to both
  files for consistency even though only one had the comment). Haptics is
  still open — some already exist (`MainApplication.handleBubbleAction()`
  vibrates on most bubble actions), but adding more (e.g. on
  swipe-dismiss/snap) is a product decision about which interactions should
  get feedback, not a bug fix, and needs a physical device to feel right —
  left for a separate pass.

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
3. ~~Phase 1 (AsyncTask→coroutines, onBackPressed/theming/Jetifier) — clears
   the biggest deprecation cluster.~~ Done, except `startActivityForResult`
   (deferred — not actually compiler-flagged deprecated on either affected
   class, so no concrete reason to touch the Activity Result API yet).
4. ~~Interleave Phase 3 product work against the TODO backlog.~~ Done, except
   haptics polish (deferred — a product decision needing a physical device,
   not a bug fix) and the Room async/coroutines rewrite (deferred —
   preserved existing synchronous/main-thread call sites instead).
5. **Phase 4 (release & distribution) — next up**, or revisit the deferred
   items above once there's a concrete reason to (device testing available,
   product direction on haptics, etc.).

*Not yet prioritized against user demand — treat ordering as engineering-risk
based, adjust once product goals are set.*
