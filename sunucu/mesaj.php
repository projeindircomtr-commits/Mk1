<?php
// Uygulama dakikada bir sorar: yeni mesaj var mi, surum yeterli mi?
define('MAKRO', 1);
require __DIR__ . '/ortak.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function cik(array $a): void { echo json_encode($a, JSON_UNESCAPED_UNICODE); exit; }

try {
    sema_guncelle();
    $token = preg_replace('/[^a-f0-9]/', '', $_GET['token'] ?? '');
    if (strlen($token) < 32) cik(['ok' => 0]);
    $pdo = db();
    $q = $pdo->prepare('SELECT id, aktif, bitis FROM makro_uyeler WHERE token = ?');
    $q->execute([$token]);
    $u = $q->fetch();
    if (!$u) cik(['ok' => 0]);

    // Uygulamadan gelen son hata kaydi
    $hata = trim(mb_substr($_GET['hata'] ?? '', 0, 500));
    if ($hata !== '') {
        $hz = (int)($_GET['hz'] ?? 0);
        $pdo->prepare('UPDATE makro_uyeler SET son_hata = ?, son_hata_zaman = ? WHERE id = ?')
            ->execute([$hata, $hz > 0 ? $hz : time(), $u['id']]);
    }

    $surum = (int)($_GET['surum'] ?? 0);
    $pdo->prepare('UPDATE makro_uyeler SET surum = ? WHERE id = ?')->execute([$surum, $u['id']]);
    $engel = surum_engeli($surum);

    $son = max(0, (int)($_GET['son'] ?? 0));
    $q = $pdo->prepare('SELECT id, metin, zaman FROM makro_mesajlar
        WHERE id > ? AND (uye_id IS NULL OR uye_id = ?) AND zaman > ? ORDER BY id LIMIT 10');
    // Sadece son 3 gunun mesajlari (yeni kurulanlara eski mesaj yagmasin)
    $q->execute([$son, $u['id'], time() - 3 * 86400]);
    $m = $q->fetchAll();

    cik([
        'ok' => 1,
        'aktif' => (int)$u['aktif'] === 1 && (int)$u['bitis'] > time(),
        'mesajlar' => array_map(function ($r) {
            return ['id' => (int)$r['id'], 'metin' => $r['metin'], 'zaman' => (int)$r['zaman']];
        }, $m),
        'guncelle' => $engel,
    ]);
} catch (Throwable $e) {
    cik(['ok' => 0]);
}
