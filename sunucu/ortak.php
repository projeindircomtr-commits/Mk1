<?php
// Ortak yardimcilar. Dogrudan acilmaz.
if (!defined('MAKRO')) { http_response_code(403); exit; }

$CFG = require __DIR__ . '/config.php';
define('GIZLI', __DIR__ . '/gizli');

function db(): PDO {
    static $pdo = null;
    global $CFG;
    if ($pdo === null) {
        $pdo = new PDO(
            'mysql:host=' . $CFG['db_host'] . ';dbname=' . $CFG['db_name'] . ';charset=utf8mb4',
            $CFG['db_user'], $CFG['db_pass'],
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
        );
    }
    return $pdo;
}

function e($s): string { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

function rastgele_sifre(int $n = 8): string {
    $h = 'abcdefghjkmnpqrstuvwxyz23456789';
    $s = '';
    for ($i = 0; $i < $n; $i++) $s .= $h[random_int(0, strlen($h) - 1)];
    return $s;
}

function kalan_yazi(int $bitis): string {
    $k = $bitis - time();
    if ($k <= 0) return 'Süresi doldu';
    $g = intdiv($k, 86400);
    $s = intdiv($k % 86400, 3600);
    return $g > 0 ? "$g gün $s saat" : "$s saat " . intdiv($k % 3600, 60) . " dk";
}

/** Mesaj ve surum ozelligi icin gereken tablo/sutunlari bir kere ekler */
function sema_guncelle(): void {
    $kilit = GIZLI . '/sema4.lock';
    if (file_exists($kilit)) return;
    $pdo = db();
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN token VARCHAR(64) NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN surum INT NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN son_hata VARCHAR(500) NULL"); } catch (Throwable $e) {}
    try { $pdo->exec("ALTER TABLE makro_uyeler ADD COLUMN son_hata_zaman INT NULL"); } catch (Throwable $e) {}
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_mesajlar (
        id INT AUTO_INCREMENT PRIMARY KEY,
        uye_id INT NULL,
        metin VARCHAR(500) NOT NULL,
        zaman INT NOT NULL,
        INDEX (uye_id)
    ) CHARACTER SET utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS makro_ayar (
        anahtar VARCHAR(40) PRIMARY KEY,
        deger TEXT NOT NULL
    ) CHARACTER SET utf8mb4");
    file_put_contents($kilit, date('c'));
}

function ayar_al(string $k, string $vars = ''): string {
    $q = db()->prepare('SELECT deger FROM makro_ayar WHERE anahtar = ?');
    $q->execute([$k]);
    $v = $q->fetchColumn();
    return $v === false ? $vars : (string)$v;
}

function ayar_yaz(string $k, string $v): void {
    db()->prepare('REPLACE INTO makro_ayar (anahtar, deger) VALUES (?, ?)')->execute([$k, $v]);
}

/** Uygulama surumu yetersizse guncelleme bilgisini dondurur, yoksa null */
function surum_engeli(int $surum): ?array {
    $min = (int)ayar_al('min_surum', '0');
    if ($min <= 0 || $surum >= $min) return null;
    return [
        'ok' => 0,
        'guncelle' => ayar_al('apk_url', ''),
        'mesaj' => ayar_al('surum_mesaj', '') ?: 'Yeni sürüm çıktı. Devam etmek için güncelle.',
        'min' => $min,
    ];
}
