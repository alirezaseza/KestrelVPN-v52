# Security policy
Report vulnerabilities privately (GitHub "Report a vulnerability"), not in public issues.

Design rules this project follows:
- The local SOCKS proxy used for benchmarking binds to 127.0.0.1 on a random port and requires random per-instance credentials, so other apps cannot use or fingerprint it.
- In VPN mode traffic goes through a TUN inbound; no unauthenticated local proxy is exposed.
- Subscriptions are fetched over HTTPS only (redirects to HTTP are refused), size-capped, HTML responses rejected, and parsed defensively.
- `allowBackup=false` and every data domain is excluded from cloud backup and device transfer; configs never leave the device. No analytics.

## Threat model: public subscriptions
The default subscription is a third-party public list. Its server operators can observe (and the list owner can alter) traffic that is not end-to-end encrypted. Treat it as untrusted: use HTTPS sites only, avoid logins on sensitive accounts, and prefer your own subscription URL (Settings).

## Known limits
- Always-on VPN is supported (system start connects to the best cached server); "Block connections without VPN" (lockdown) is enforced by Android, not the app.
- If a failover cannot start, the TUN stays open and traffic is blocked (never leaked) until the user reconnects or disconnects.
- Dead-core detection relies on the tunnel health probe (~30 s), not on a libbox callback.
- The sing-box JSON uses fields deprecated in 1.12+ (sniff, dns-out, legacy DNS address); the build is pinned to v1.11.15. Migrate before bumping.
- The Stage-0 prefilter resolves proxy host names with the system resolver (visible to the ISP resolver).
