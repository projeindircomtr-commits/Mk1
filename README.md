# Makro (Projeindirpedal)

Erisilebilirlik servisi ile calisan oyun makrosu. Root / ADB / PC gerekmez.

## HyperOS / Android 16 — makro + video ayni anda

Android 14+ (HyperOS dahil) **ayni anda iki ekran yakalama iznine izin vermez**.
Eski surum ikinci bir "ekran kaydi izni" isteyince birincisini kesiyor, makro kor kaliyordu.

Simdi tek MediaProjection / tek VirtualDisplay var: ayni kare hem bota hem H.264 kayda gider.

Kullanim:
1. Erisilebilirlik iznini ac (HyperOS: Ayarlar → Uygulamalar → Projeindirpedal → **Kisitli ayarlar**).
2. Pil: **Kisitsiz**. Arka planda acilir pencerelere izin ver.
3. Oyunu ac, panelden ▶ (ekran izninde **Tum ekran** sec, tek uygulama degil).
4. Makro calisirken menu → **Video kaydi baslat**. Ikinci izin cikmaz; kayit baslar.
5. Video: Galeri → Videolar → **Projeindirpedal**.

Oyun Turbo / Game Turbo'da ucuncu taraf arac / ekran kaydini engelleme.

APK: GitHub > Actions > son calisma > Artifacts > makro-apk
