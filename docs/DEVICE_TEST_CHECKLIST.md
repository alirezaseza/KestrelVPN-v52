# Device test checklist (run before every release)
Devices: Android 8, 11, 14+; Wi-Fi and two mobile operators.
1. Fresh install -> onboarding -> first scan -> connect (best server).
2. Exit-IP check differs from the real IP; no DNS/IPv6 leak (browserleaks / ipleak).
3. Kill the proxy server mid-session -> failover within ~30 s; if none left, traffic stays blocked + alert.
4. Settings > VPN > Always-on ON (+ block connections) -> reboot -> app reconnects by itself.
5. Quick-settings tile connect / disconnect; notification Disconnect button.
6. Split tunnel include / exclude; Iran-direct on / off.
7. Airplane mode toggle while connected and while scanning.
8. Release (minified) build: connect, scan, hunt, language switch fa/en, RTL layout, font scale 200%, TalkBack.
9. Memory: scan 80 servers on a low-RAM device; no OOM.
10. Settings > Reduce visual effects ON/OFF: blur and drifting background disappear/return; scrolling stays smooth on a low-end device.
11. Split tunnel "exclude" with this app in the list: exit-IP check inside the app still shows the VPN address.
12. Captive-portal Wi-Fi: subscription refresh shows a clean error (no crash, no bogus servers).
13. Libbox swap: install a deliberately wrong AAR -> the app shows the "incompatible libbox" message instead of crashing.
