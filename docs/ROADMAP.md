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

## Phase 4 — Release & distribution ✅ Done for now (targetSdk 36 deferred to a dedicated session)

- ~~**Signing/release flow.**~~ **Done** — added a "Release checklist" section
  to `CONTRIBUTING.md` (CI-green check, version bump, one-time
  `build-release.sh` setup from the template, build, sanity-check the output
  version, install-and-smoke-test on a device *before* uploading since release
  is the only build type that runs R8, upload, tag).
- ~~**ABI coverage.**~~ **Decided: leave as-is.** `abiFilters` stays
  `arm64-v8a` + `x86_64` only — minSdk 26 already excludes very old devices,
  and 32-bit-only Android phones are increasingly rare/EOL, so `armeabi-v7a`
  wasn't judged worth the APK-size/build-surface cost.
- ~~**Play compliance.**~~ **Predictive-back flag fixed; targetSdk 36 bump
  still open, time-sensitive.** Added `android:enableOnBackInvokedCallback="true"`
  to `<application>` — the Phase 1 `onBackPressed()` migration registered
  `OnBackPressedCallback`s on `EntryActivity`/`ExpandedActivity`, but without
  this manifest flag the system never actually activates the predictive-back
  gesture/preview animation, so that migration's real payoff was inert until
  now. Safe app-wide: activities that haven't migrated (plain `Activity` and
  AppCompatActivity screens with no back override) fall back to normal back
  handling unaffected, per Android's own compat behavior, just without the
  preview animation for those screens.
  **Checked Google Play's current target API policy (as of 2026-07-13):**
  by **August 31, 2026** every new app/update must target Android 16 (API
  36), and *existing* published apps must target at least API 35 to remain
  visible on Android 16/17 devices (extension available to Nov 1, 2026).
  Peek's targetSdk 35 clears the existing-app bar today, but any update
  submitted after Aug 31 needs targetSdk 36 — roughly 7 weeks out from today.
  compileSdk 36 platforms are already installed locally (`android-36`,
  `android-36.1`); current AGP is 8.5.2, and both a newer 8.x (8.13.2) and
  9.x (9.2.1) line support it. **Decided: not now** — deferred to a dedicated
  session with device access, since Android 16's behavior changes "activate
  the moment your app opts in" (edge-to-edge enforcement, predictive-back,
  notification/full-screen-intent changes) need real on-device verification
  before shipping, not a same-session bump. Revisit with enough runway before
  Aug 31, 2026 (or Nov 1 with the extension) to actually test on a device.
  [Target API level requirements for Google Play apps](https://developer.android.com/google/play/requirements/target-sdk)

---

# Round 2 — fresh audit, 2026-07-13

Everything above is done or deliberately deferred. This section is a second
pass over the codebase now that it's in much better shape, looking for what's
next. Concrete anchors from a fresh dependency/deprecation/test-coverage/
architecture sweep.

## Phase 5 — Dependency freshness ✅ Done (AGP capped mid-8.x, targetSdk/Coil3 still deferred)

- ~~**Kotlin 2.1.0 → 2.4.0**, KSP bumped in lockstep.~~ **Done** — Kotlin
  `2.4.0`, KSP `2.3.10` (KSP dropped its old `<kotlin>-<ksp>` combined
  versioning scheme somewhere after `2.2.21-2.0.5`; from `2.3.0` on it's a
  plain version that tracks Kotlin directly). This surfaced a real blocker:
  KSP `2.3.10`'s Android integration calls AGP's `addKspConfigurations()`
  API, which doesn't exist before **AGP 8.10** (confirmed via `javap` against
  locally-cached AGP jars — missing on 8.5.2/8.7.3, present from 9.0.1 on;
  KSP's own commit history pins its min runtime AGP to 8.10.0). So the Kotlin
  bump wasn't independent of AGP after all.
- ~~**AGP 8.5.2 → 9.x.**~~ **Landed on AGP `8.13.2` instead — not the 9.x
  jump.** There's a full `8.6`–`8.13` line this roadmap's first pass didn't
  notice; `8.13.2` (latest 8.x) satisfies KSP's AGP≥8.10 floor without the
  Gradle-major/deprecated-DSL migration a real 9.x jump needs. Gradle wrapper
  bumped `8.9`→`8.13` alongside it (AGP 8.13's own min/default). **The 9.x
  jump itself is still deferred**, bundled with targetSdk 36 as Phase 4
  already decided — this pass just moved the *safe* part (staying on 8.x)
  off that pile.
- ~~**`androidx.core:core` 1.13.1 → 1.19.0**~~ **Capped at `1.18.0`, not
  `1.19.0`.** `1.19.0`'s AAR metadata demands `minCompileSdk=37` +
  `minAndroidGradlePluginVersion=9.1.0` — both far past what this pass's AGP
  8.13.2 step covers, and API 37 isn't even installed locally. `1.18.0` only
  needs `minCompileSdk=36`/AGP≥8.9.1, both satisfied. **Bumped `compileSdk`
  35→36 to clear that floor** (platforms already installed locally per prior
  session) — `targetSdk` stays 35, untouched; compileSdk/targetSdk are
  independent per Android's own guidance, so this doesn't touch the deferred
  Phase 4 targetSdk-36/Android-16-behavior item at all.
- ~~**`com.google.android.material:material` 1.12.0 → 1.14.0**~~ **Done**
  (`minCompileSdk=1`, no floor issues).
- ~~**`org.jsoup:jsoup` 1.18.3 → 1.22.2**~~ **Done.**
- ~~**`androidx.recyclerview:recyclerview` 1.3.2 → 1.4.0**~~ **Done**
  (`minCompileSdk=35`, cleared by the compileSdk 36 bump above).
- Verified via AAR-metadata inspection before touching build files (each
  candidate version's `META-INF/.../aar-metadata.properties` was pulled and
  checked for `minCompileSdk`/`minAndroidGradlePluginVersion` before
  committing to a version), not just picked off "latest stable" — that's
  what caught the core 1.19.0 trap above before it hit CI.
- Already current, untouched: `room-runtime`/`room-compiler` (2.8.4),
  `kotlinx-coroutines-android` (1.11.0), `appcompat` (1.7.0, next is only
  1.8.0-alpha), `preference-ktx`.
- Effectively abandoned upstream, nothing to bump to: `androidx.palette`
  (1.0.0, 1.1.0 has been alpha for years), `org.slf4j:slf4j-android` (1.7.36,
  hasn't shipped since 2018).
- **Not a simple bump, evaluate separately (still deferred):** `io.coil-kt:coil`
  is at the final 2.x release (2.7.0); Coil 3.x lives under a new coordinate
  (`io.coil-kt.coil3:coil`, latest 3.5.0) with API changes — a deliberate
  migration, not routine maintenance.
- Verified with a clean `assemblePlaystoreDebug` + `testPlaystoreDebugUnitTest`
  (JBR `JAVA_HOME`) — both green, all 9 existing unit tests
  (`CircleTest` ×6, `ArticleTextExtractorTest` ×3) still pass.

## Phase 6 — Test coverage

Only 2 test files exist (`physics/CircleTest.kt`, `de/jetwick/snacktory/ArticleTextExtractorTest.kt`)
despite Phase 0 standing up the scaffold. Best next additions, smallest to
largest:
- **`physics/FlingTracker.kt`** (116 lines) — only touches `MotionEvent` for
  `.x`/`.y`/`.eventTime`, close to pure logic. Needs Robolectric (for
  `MotionEvent.obtain`), but a good first Robolectric test in this repo.
- **`db/HistoryDao.kt`/`FaviconDao.kt`** — ideal for an in-memory Room
  instance test (Room ships first-class support for this), also needs
  Robolectric for a real SQLite/Android env.
- **Pure-logic candidates already JVM-testable with plain JUnit, no
  Robolectric needed:** `util/Util.kt`'s `clamp`/`distance`/
  `clipLineSegmentToRectangle`/`closestPointToLineSegment`/`randInt`, and
  `adblock/ADBlockUtils.kt`'s `getDataVerNumber(url)`.
- **Needs real refactoring before testable at all:** `physics/DraggableHelper.kt`
  (Android view/window code tangled with the animation math), `MainController`/
  `Settings`/`ContentView` (see Phase 9) — not worth chasing without
  extracting pure logic first.

## Phase 7 — Remaining deprecations & possible functional bug

- **WebView/WebSettings deprecated no-ops** (low effort, mostly harmless
  already): `cookieManager.removeAllCookie()` (`MainController.kt:1340`,
  `SettingsActivity.kt:701`) → `removeAllCookies(callback)`;
  `webViewDatabase.clearFormData()`/`clearUsernamePassword()`
  (`SettingsActivity.kt:710,721`, no-ops since API 26); `savePassword`/
  `saveFormData` get/set (`WebViewRenderer.kt:522,574-579`, no-ops since
  API 18/26).
- **`Handler()` no-arg constructor** — 14 hits (deprecated, implicitly binds
  to the calling thread's `Looper`; mechanical fix is
  `Handler(Looper.getMainLooper())`): `SettingsDomainsActivity.kt:117`,
  `ContentView.kt:1007,1642`, `SettingsActivity.kt:114`, `EntryActivity.kt:25`,
  `Prompt.kt:39`, `ExpandedActivity.kt:51`, `PageInspector.kt:43`,
  `AppPoller.kt:94`, `WebViewRenderer.kt:140,488`,
  `org/mozilla/gecko/util/GeckoBackgroundThread.kt:19`.
- **`NetworkConnectivity.kt` (95 lines)** is built entirely on
  `ConnectivityManager.activeNetworkInfo`/`NetworkInfo`, deprecated API 29+ in
  favor of `NetworkCapabilities`/`registerNetworkCallback`. Medium effort — a
  callback-based rewrite, not a mechanical swap, since `isConnected`/
  `isConnectedWifi`/`isConnectedFast` are currently synchronous callers.
- **Possible existing functional bug, not just deprecated:**
  `AppPoller.kt:39,105` call `am.getRunningTasks(1)` — deprecated since API 21
  *and* effectively non-functional on real devices since Lollipop for
  non-system apps (only returns the calling app's own tasks). Worth
  confirming whether `AppPoller`'s foreground-app detection actually still
  works before deciding how to fix it (real replacement needs
  `UsageStatsManager`, which needs a user-granted special permission — a
  bigger change than a deprecation fix).
- **Misc smaller:** `MainApplication.kt:434` `vibrator.vibrate(10)`
  (deprecated API 26+, replace with `VibrationEffect.createOneShot`);
  `Display.getSize()`/`getMetrics()` in `Config.kt:45`,
  `BubbleFlowDraggable.kt:320` (deprecated API 30+, `BubbleFlowActivity.kt:26`
  doesn't count since that whole file is dead, see Phase 8).

## Phase 8 — Small cleanups

- **Delete `ui/BubbleFlowActivity.kt`** (69 lines) — fully dead code, its only
  manifest registration is commented out and nothing else references it.
  (Distinct from `BubbleFlowDraggable.kt`, which is very much alive.)
- **`lint.xml:6-9`** suppresses `HandlerLeak` for
  `src/main/java/com/linkbubble/util/AppPoller.java` and
  `src/main/java/com/linkbubble/DRM.java` — both paths are stale from before
  the `com.linkbubble`→`com.peek.browser` rename and Java→Kotlin migration;
  neither exists anymore (`DRM.kt` doesn't exist at all). The real
  `HandlerLeak` check isn't actually suppressed on today's `AppPoller.kt` —
  fold into the Phase 7 `Handler()` fix and drop the stale suppression
  entirely rather than just fixing the path.
- **`ui/BubbleTargetView.kt:86-109`** — four `@Suppress("UNREACHABLE_CODE")`
  guarding dead `Util.Assert(false, ...)` fallback branches after exhaustive
  `when` blocks over `HorizontalAnchor`/`VerticalAnchor`. Kotlin's
  exhaustiveness check already covers these; delete the dead branches and
  suppressions together.

## Phase 9 — Architecture (flagged only, not scoped)

Three god-class candidates, none touched by Round 1: `ui/ContentView.kt`
(2090 lines, 32 methods + 4 nested classes — WebView rendering, UI chrome,
and favicon loading all in one file), `MainController.kt` (1381 lines, 56
methods + 21 nested types — a strong god-object signal), `Settings.kt` (1349
lines, 74 methods — every preference read/write funnels through one
singleton). Worth a real look eventually, but each needs its own design pass,
not something to scope from a single audit pass.

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
5. ~~Phase 4 (release & distribution).~~ Done for now — release checklist
   written, predictive-back flag fixed, ABI coverage decided (leave as-is).
   **All four roadmap phases have had a pass.** What's left across all of
   them is deferred work needing something this repo/session doesn't have
   yet: device access (targetSdk 36 bump and its Android 16 behavior
   changes, haptics polish, `startActivityForResult`'s Activity Result API
   if a concrete reason shows up) or a product decision already made
   (Room's async/coroutines rewrite skipped in favor of preserving existing
   synchronous call sites). **Next up: pick one of those, or start a new
   pass now that the codebase is in much better shape than this roadmap's
   2026-07-13 baseline.**
6. ~~Phase 5 (dependency freshness).~~ Done — Kotlin 2.4.0, KSP 2.3.10, AGP
   8.5.2→8.13.2 (not the deferred 9.x jump), Gradle wrapper 8.9→8.13,
   compileSdk 35→36 (targetSdk untouched), core/material/jsoup/recyclerview
   all bumped (core capped at 1.18.0, not latest 1.19.0 — see Phase 5 for
   why). Clean build + all 9 unit tests green.

**Paused here (2026-07-13).** PR #3 (`roadmap-pass-2` → `main`) is open with
CI green, awaiting human merge — not merged yet, so `main` doesn't have
Phase 5 until that lands. The debug build was sideloaded onto a physical
device for a quick sanity check outside CI. **Next up when resuming:** merge
#3, then pick one of Phase 6 (test coverage), 7 (remaining deprecations), 8
(small cleanups), or 9 (architecture — needs its own design pass first).

*Not yet prioritized against user demand — treat ordering as engineering-risk
based, adjust once product goals are set.*
