<?php
// Uygulamanin giris/lisans kontrolu. Cevap sunucu anahtariyla imzalanir.
define('MAKRO', 1);
require __DIR__ . '/ortak.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function hata(string $m, bool $sunucu = false): void {
    // sunucu=1: gecici arıza; uygulama makroyu durdurmaz
    if ($sunucu) http_response_code(503);
    echo json_encode(['ok' => 0, 'mesaj' => $m, 'sunucu' => $sunucu ? 1 : 0], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') hata('Geçersiz istek');

$k = trim($_POST['kullanici'] ?? '');
$s = (string)($_POST['sifre'] ?? '');
$c = preg_replace('/[^A-F0-9]/', '', strtoupper($_POST['cihaz'] ?? ''));
$n = preg_replace('/[^a-f0-9]/', '', strtolower($_POST['nonce'] ?? ''));
if ($k === '' || $s === '' || strlen($c) < 8 || strlen($n) < 16 || strlen($k) > 40) hata('Eksik bilgi');

$ip = $_SERVER['REMOTE_ADDR'] ?? '';
$simdi = time();

try {
    $pdo = db();
    // Kaba kuvvet korumasi: 10 dakikada 10 hatali deneme
    $pdo->prepare('DELETE FROM makro_denemeler WHERE zaman < ?')->execute([$simdi - 600]);
    $q = $pdo->prepare('SELECT COUNT(*) FROM makro_denemeler WHERE ip = ?');
    $q->execute([$ip]);
    if ((int)$q->fetchColumn() >= 10) hata('Çok fazla hatalı deneme. 10 dakika sonra tekrar dene.');

    $q = $pdo->prepare('SELECT * FROM makro_uyeler WHERE kullanici = ?');
    $q->execute([$k]);
    $u = $q->fetch();
    if (!$u || !password_verify($s, $u['sifre_hash'])) {
        $pdo->prepare('INSERT INTO makro_denemeler (ip, zaman) VALUES (?, ?)')->execute([$ip, $simdi]);
        hata('Kullanıcı adı veya şifre yanlış');
    }
    if (!(int)$u['aktif']) hata('Üyelik kapatılmış');
    if ((int)$u['bitis'] <= $simdi) hata('Üyelik süresi doldu');

    // Surum kontrolu (eski surumler calismaz)
    sema_guncelle();
    $surum = (int)($_POST['surum'] ?? 0);
    $pdo->prepare('UPDATE makro_uyeler SET surum = ? WHERE id = ?')->execute([$surum, $u['id']]);
    $engel = surum_engeli($surum);
    if ($engel) { echo json_encode($engel, JSON_UNESCAPED_UNICODE); exit; }

    if (empty($u['cihaz'])) {
        $pdo->prepare('UPDATE makro_uyeler SET cihaz = ? WHERE id = ?')->execute([$c, $u['id']]);
    } elseif ($u['cihaz'] !== $c) {
        hata('Bu üyelik başka bir cihaza bağlı');
    }
    $token = bin2hex(random_bytes(24));
    $pdo->prepare('UPDATE makro_uyeler SET son_giris = ?, token = ? WHERE id = ?')->execute([$simdi, $token, $u['id']]);

    $isim = str_replace('|', '', $u['isim'] ?: $u['kullanici']);
    $bitis = (int)$u['bitis'];
    $metin = "v1|1|$k|$c|$bitis|$isim|$n|$simdi";
    $ozel = openssl_pkey_get_private(file_get_contents(GIZLI . '/ozel.pem'));
    if (!$ozel || !openssl_sign($metin, $imza, $ozel, OPENSSL_ALGO_SHA256)) hata('Sunucu imza hatası', true);

    echo json_encode([
        'ok' => 1, 'isim' => $isim, 'bitis' => $bitis, 'zaman' => $simdi, 'token' => $token,
        'imza' => base64_encode($imza)
    ], JSON_UNESCAPED_UNICODE);
} catch (Throwable $ex) {
    hata('Sunucu hatası', true);
}
