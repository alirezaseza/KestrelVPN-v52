# Architecture

```
 UI (Compose)            ViewModels            Domain / Repository            Engine
 ─────────────           ──────────            ───────────────────            ──────
 Home / Servers   ───►   HomeViewModel   ───►  ConfigRepository  ◄───────►   ChunkedTestScheduler
 Settings / Split        SettingsVM            (Room + DataStore)             HuntModeOrchestrator
 Onboarding / Privacy                                │                          │
                                                     ▼                          ▼
                         VpnConnectionManager ◄── KestrelVpnService      ProxyProbePipeline ── ProxyCore
                         (state, commands)         │  (TUN, failover)      (stage 0 → ping → speed)   │
                                                   ├─ TunnelHealthProbe + TunnelHealthTracker         │
                                                   ├─ SplitTunnelPlan                                 ▼
                                                   └─ ProxyCore ───────────────────────────►  SingBoxProxyCore
                                                                                              ├─ SingBoxConfigBuilder (JSON)
                                                                                              ├─ PlatformBridge (libbox callbacks)
                                                                                              └─ LibboxContract (fail-fast checks)
```

## Data flow
1. `SubscriptionFetcher` downloads the list (HTTPS only, capped, HTML rejected) → `ProxyLinkParser` → `ConfigNormalizer` (canonical key, dedup) → Room.
2. `ChunkedTestScheduler` / `HuntModeOrchestrator` probe servers in short chunks: stage 0 (IP-literal TCP/TLS) → ping through an authenticated loopback SOCKS core → optional speed test. `ServerScorer` turns results into a score and tier.
3. `KestrelVpnService` builds the TUN (IPv4 + IPv6, split-tunnel plan), starts sing-box through libbox, then watches health. Failover keeps the TUN open so traffic is black-holed, never leaked.

## Design rules
- Pure logic lives in small classes with unit tests (`FailoverPolicy`, `SplitTunnelPlan`, `TunnelHealthTracker`, `ServerScorer`, `PlatformBridge`).
- Anything that touches libbox goes through `LibboxContract` first, so an incompatible AAR fails with a readable message.
- Never log IPs, UUIDs or links; use `DiagnosticsLog` (redacting).
- Visual effects (blur, ambient animation) must honour `LocalReduceEffects`.
