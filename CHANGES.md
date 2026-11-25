# v52
- Hunt rounds 1 and 2 now use a worker pool (like Quick Scan) instead of lock-step waves: one slow server no longer stalls the whole wave. Progress is saved every few keys and on cancel, so resume still skips finished work.
- Network epoch guard: NetworkKeyProvider.networkEpoch() counts network changes (appear/lost/validated flip). A probe that failed while the epoch changed (Wi-Fi <-> mobile, short drop) is not recorded against the server and is re-tested.
- Hunt round 2: a failed download with no internet is measured again instead of silently skipped.
- Not changed on purpose: coreLock (native core is not safe for parallel create/close; a multi-outbound instance needs on-device testing) and splitting KestrelVpnService/HomeScreen (pure refactor, too risky without a compiler).

# v51
- Penalty fix: a failed probe is written to the database ONLY if the phone itself has internet (PeriodicConnectivityGate.confirmServerFault). During an outage nothing is saved, workers wait, and the server is re-tested. Applies to Quick Scan, Hunt (rounds 1 and 3), "Connect" benchmark and failover. Old scheme wrote up to 9 wrong DEAD results before noticing and double-counted them on re-test.
- Phone internet test: online check is cached 2 s (fewer HTTPS probes); new direct download measurement of the phone itself. If a server looks "too slow" but the phone's own link is below the minimum, the server is accepted.
- Defaults: mobile test mode now PING_AND_SPEED too (the stored-value fallback was PING_ONLY).
- Perf: end-of-scan tier counts via COUNT query (was loading every alive server); Home radar observes only the top 14 (was every server on every DB write); server-list mapping moved off the main thread; IPv4 regex hoisted.
- Tests: gate test + scheduler outage test.

# v50 (pre-connect check, adjustable min speed, uTLS override, Mbit units)
- Settings > Speed: new "Check before connecting" (Auto / Ping only / Ping + Speed) for the server you tap. Auto follows Wi-Fi/mobile test mode and Data saver.
- New "Minimum speed" switch + slider (0.05 / 0.1 / 0.25 / 0.5 / 1 / 2 / 5 Mbit/s, default 0.05). Off = no minimum (tapped server and auto-pick accept any speed).
- New "TLS fingerprint (uTLS)" setting: force chrome/firefox/safari/ios/android/edge/random for REALITY/TLS servers (default: use the link's).
- Diagnostics log now records the REALITY parameters really used (sni, fp + source, flow, sid, short pbk) on tap-check and VPN start.
- Speeds in the server list and home card are now shown in Mbit/s (they were MB/s).
- Not compiled here (no Android SDK/network): please build once and watch for import/typing errors.

# v35 (device-log driven fix)
- Root cause of "connected but nothing loads": sing-box logged `no available network interface` for every DNS query and outbound connection. On Android 11+ the core cannot enumerate interfaces itself, and the bridge answered `false` to usePlatformDefaultInterfaceMonitor / usePlatformInterfaceGetter.
- New `PlatformNetwork` / `AndroidPlatformNetwork`: reports the default physical network (ConnectivityManager) to libbox and implements getInterfaces.
- sing-box log lines are now written to DiagnosticsLog with tag `singbox` (they were dropped); network events use tag `net`.
- Still NOT compiled here: libbox method names are matched by reflection and any mismatch is logged with tag `net`.

# v16 (final polish)
- Service logic extracted and tested: `TunnelHealthTracker` (failover rule) and `SplitTunnelPlan`. Fix: "exclude" split mode can no longer exclude this app itself (that made the health probe test the direct network).
- `LibboxContract` now pins exact signatures (`setup(SetupOptions)`, `newService(String, PlatformInterface)`, setters) and the SetupOptions no-arg constructor.
- Parser: 6 more tests (Reality, allowInsecure scope, case-insensitive schemes, comments/invalid counting, remark cap).
- Backup/transfer rules now exclude every data domain (DataStore holds the subscription URL and favourites).
- New docs/ARCHITECTURE.md; Persian README summary; extended device checklist.

# v15 (quality pass)
- libbox bridge: `PlatformBridge` extracted and unit-tested; java.lang.Object methods (equals/hashCode/toString) now behave normally; unknown libbox methods are logged once to DiagnosticsLog.
- Probe: local SOCKS start retries on a fresh port (port race), instead of marking the server dead.
- Scoring: weights and tier thresholds are named constants; new boundary/monotonicity tests.
- Subscription fetch: https->http redirects refused (client + check), HTML (captive portal) responses rejected.
- UI: "Reduce visual effects" setting (also forced on low-RAM devices): no backdrop blur, no ambient animation; film grain moved to its own layer so it is no longer redrawn on every orb frame.
- Tests: 52 -> 68 (PlatformBridge, TUN/DNS/Iran-bypass JSON, scorer, subscription config).
- Build: gradlew verifies `distributionSha256Sum` when present (KESTREL_REQUIRE_SHA=1 makes it mandatory); build-libbox.sh supports SINGBOX_COMMIT / LIBBOX_SHA256 pins; Go pinned to 1.24.x; job timeouts; .editorconfig/.gitattributes.
- i18n check now compares format placeholders too.
- Formatting: 59 flat (un-indented) Kotlin/Gradle files re-indented (whitespace only; verified token-identical).
- Still NOT compiled or device-tested here.

# v14 (final polish)
- Exit-IP check moved from the UI into SettingsViewModel (uses the tunnel client); privacy text discloses all contacted endpoints.
- Added docs/DEVICE_TEST_CHECKLIST.md; README status section.
- Server list search + favorites-first; Settings rows have roles and 48dp targets.

# v12 changes
- Stage-0 prefilter no longer uses the system resolver (IP literals only); local probe core resolves via encrypted DNS.
- FailoverPolicy extracted + tested; probe concurrency capped at 8 sing-box instances.
- i18n parity script in CI, gradlew.bat, CONTRIBUTING.md, DAO/Socks5/migration tests.

# v9 changes
- Always-on VPN: system/null-intent starts now connect to the best cached server (START_STICKY).
- Failover failure keeps the TUN open (traffic blocked, alert shown) instead of leaking.
- Failover guarded against concurrent runs; failed-key set is thread-safe; health check every 10 s.
- libbox PlatformInterface bridge: explicit defaults for monitor/getter/notification methods.
- CI uses setup-gradle (no gradlew in zip); notification channels localized; schemas dir added.
- Corrected v8 claim: dead-core detection is the health probe, not isRunning().
- New tests: ConfigRepositoryImplTest, ConnectionManager-independent contract checks.

# v8 changes
- Hardening: LibboxContract (+tests) fails fast on incompatible libbox; TunLeakInvariantsTest; redacted DiagnosticsLog + Settings copy; CI assembleRelease job.
- Haze 1.6.0 added: real backdrop blur behind every liquidGlass surface (AuroraBackground is the hazeSource).
- Polish: liquidGlass adapts to light theme (frosted white), brace/paren sanity-checked.
- Background rebuilt (calm mesh + grain + halos), glass lens, chips with checks, data totals, glass hunt button.
- UI v3 "Liquid Glass": translucent rim-lit glass surfaces, vivid drifting orbs, glow progress ring, glass slide control.
- UI v2 "Falcon HUD": telemetry tick ring, HUD corner-bracket panels, mono numerics.
- Icon/theme colors moved to Ember palette; Settings: exit-IP check.
- Leak fixes: health probe no longer sends direct requests over the physical network; direct DNS now DoH; dead-core detection triggers failover; Always-on/lockdown shortcut in Settings.
- UI: Obsidian & Ember theme, dot-grid background, bento cards, tracked typography, protection chips.

# Fixes applied

Static fixes only — nothing was compiled here (no Android SDK / network). Build once and run the unit tests.

## Critical
1. Added `KestrelApp.kt` (@HiltAndroidApp, notification channels, WorkManager `Configuration.Provider`).
2. Added `di/Qualifiers.kt` + `di/CoroutinesModule.kt` (`@IoDispatcher`, `@DefaultDispatcher`, `@ApplicationScope`).
3. `libbox.aar` and the Gradle wrapper jar still must be produced locally (see "You still need to").
4. TUN DNS: rule `outbound:any -> dns-direct` so proxy-server domains are never resolved through the proxy; removed the invalid `detour: direct`; direct resolver is now 8.8.8.8.
5. Reality now always gets uTLS (`chrome` fallback).

## Logic
6. EMA is updated once per probe (`updateEma=false` for speed stage, Hunt rounds 2 and 3).
7. Ping/jitter: every sample uses a fresh connection; ping is the lower median; jitter = max-min of comparable samples.
8. Dead servers (stage-0 failure) no longer count as "timeouts" for AIMD; AIMD floor is 4.
9. Stage-0 timeout 3 s; DEAD cooldown 15 min -> 1 h -> 6 h -> 24 h.
10. Hunt uses `getHuntCandidates` (includes recently-tested alive servers).
11. Quick Scan uses a worker pool; no new probe after the deadline; ping stage has a 10 s budget; speed stage has its own 12 s box.
12. One scoring formula (unmeasured speed = neutral 50); speed clock starts at the first byte.
13. OfflineDetector: only a clean 204 (or Apple "Success") counts, returns on the first success.

## Security / stability
14. IPv6 address + `::/0` route in the VPN interface and TUN config.
15. MTU 1400; TUN stays open during failover (kill-switch); libbox gets its own dup'ed fd.
16. See 4.
17. `allowInsecure` from links is ignored unless the new Settings switch "Allow invalid TLS certificates" is on.
18. Inactive configs older than 7 days (and orphan results) are deleted; `importedAtMs` is preserved.
19. Home observes a single best server (`observeBestServer`) instead of the whole list.
20. Network key looks through the VPN to the physical network.
21. Probe clients use a private non-keeping connection pool.
22. Added `httpupgrade` and `h2` transports; subscription cap raised to 16 MB. Still unsupported: xhttp, SS plugins (sing-box limitation).
23. `startForeground()` is always called before `stopSelf()`.

## UI redesign (Aurora)
- Theme: deep-ink dark palette + teal/violet accents, refined light theme, rounded shape scale.
- New components: AuroraBackground, GlassCard, GlassIconButton, StatTile, GradientButton.
- PowerButton: canvas-drawn orb with glow, rings and rotating progress arc.
- Home and Server list redesigned. ViewModels and logic untouched.

## Fast connect / auto scan
- Power button connects instantly to the cached best server (unchanged). If nothing is cached it now scans in ping-only 20 s chunks (max 4) and connects as soon as the first EXCELLENT/GOOD server appears (`QuickScanRequest.stopAfterUsableTarget`), then connects to the best available.
- After every subscription refresh/app open a silent ping-only Quick Scan runs (max 3 chunks), skipped when connected, offline, or Data Saver is on.
- Set `SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL` to your own repo.

## Project hardening
- Parser/Base64/mirror-URL unit tests, CI (build libbox, unit tests, lint), Dependabot, SECURITY.md, README, issue template.
- `scripts/validate_subscription.py` + `docs/config-repo-workflow.yml`: clean the config repo before publishing.

## UI v2 – "Falcon Radar"
- Home: live radar of REAL tested servers (distance = latency, colour = tier), sweep while idle/connecting, ripples when connected.
- Slide-to-connect control (RTL-aware, accessible) replaces the power button; live throughput sparkline + latency/time tiles.

## UI polish pass
- Settings, Privacy, Onboarding, Server list empty state now use the Aurora/Glass design system; section titles accented; privacy text split into cards.
- Screen transitions: fade + subtle scale (direction-neutral, fine for RTL).
