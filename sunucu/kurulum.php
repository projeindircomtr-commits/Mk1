<?php
// BIR KERE ac: tablolari kurar, imza anahtarini ve yonetici sifresini olusturur.
define('MAKRO', 1);
require __DIR__ . '/ortak.php';

if (file_exists(GIZLI . '/kuruldu.lock')) {
    exit('Zaten kurulu. Güvenlik için bu dosyayı (kurulum.php) sunucudan sil.');
}

$hata = '';
$bitti = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $s1 = $_POST['s1'] ?? '';
    $s2 = $_POST['s2'] ?? '';
    if (strlen($s1) < 8) $hata = 'Şifre en az 8 karakter olmalı.';
    elseif ($s1 !== $s2) $hata = 'Şifreler aynı değil.';
    else {
        try {
            db()->exec("CREATE TABLE IF NOT EXISTS makro_uyeler (
                id INT AUTO_INCREMENT PRIMARY KEY,
                kullanici VARCHAR(40) NOT NULL UNIQUE,
                sifre_hash VARCHAR(255) NOT NULL,
                isim VARCHAR(60) NOT NULL DEFAULT '',
                bitis INT NOT NULL,
                cihaz VARCHAR(40) NULL,
                aktif TINYINT NOT NULL DEFAULT 1,
                son_giris INT NULL,
                olusturma INT NOT NULL
            ) CHARACTER SET utf8mb4");
            db()->exec("CREATE TABLE IF NOT EXISTS makro_denemeler (
                ip VARCHAR(45) NOT NULL,
                zaman INT NOT NULL,
                INDEX (ip, zaman)
            ) CHARACTER SET utf8mb4");

            if (!is_dir(GIZLI)) mkdir(GIZLI, 0700, true);
            file_put_contents(GIZLI . '/.htaccess', "Require all denied\nDeny from all\n");
            file_put_contents(GIZLI . '/index.html', '');

            $key = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
            if (!$key) throw new Exception('Sunucu EC anahtar oluşturamadı (openssl).');
            openssl_pkey_export($key, $pem);
            file_put_contents(GIZLI . '/ozel.pem', $pem);
            chmod(GIZLI . '/ozel.pem', 0600);
            $pub = openssl_pkey_get_details($key)['key'];
            $pubB64 = preg_replace('/-----[^-]+-----|\s+/', '', $pub);

            file_put_contents(GIZLI . '/admin.hash', password_hash($s1, PASSWORD_DEFAULT));
            file_put_contents(GIZLI . '/kuruldu.lock', date('c'));

            $proto = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
            $url = $proto . '://' . $_SERVER['HTTP_HOST'] . rtrim(dirname($_SERVER['SCRIPT_NAME']), '/') . '/api.php';
            $bitti = json_encode(['url' => $url, 'pub' => $pubB64], JSON_UNESCAPED_SLASHES);
        } catch (Throwable $ex) {
            $hata = 'Hata: ' . $ex->getMessage();
        }
    }
}
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Makro Kurulum</title>
<style>body{background:#12161c;color:#fff;font-family:sans-serif;padding:16px}
input,textarea{width:100%;padding:12px;margin:6px 0;border-radius:10px;border:0;box-sizing:border-box;font-size:16px}
button{background:#2e9e5b;color:#fff;border:0;padding:14px;border-radius:10px;width:100%;font-size:17px}
.k{background:#1c232d;padding:14px;border-radius:14px;margin:10px 0}.h{color:#ff7b7b}textarea{height:130px;font-size:12px}</style>
</head><body>
<h2>Makro Kurulum</h2>
<?php if ($bitti): ?>
<div class="k"><b>✅ Kurulum tamam.</b><br><br>
1) Aşağıdaki komutun tamamını kopyala, <b>Termux</b>'a yapıştır:<br>
<textarea readonly onclick="this.select()">echo '<?= e($bitti) ?>' > ~/MakroApp/app/src/main/assets/lisans.json</textarea>
2) Sonra Termux'ta: <code>cd ~/MakroApp && git add -A && git commit -m lisans && git push</code><br><br>
3) Güvenlik için <b>kurulum.php</b> dosyasını sunucudan sil.<br><br>
4) Yönetim paneli: <a style="color:#e0b04a" href="admin.php">admin.php</a></div>
<?php else: ?>
<?php if ($hata): ?><p class="h"><?= e($hata) ?></p><?php endif; ?>
<form method="post" class="k">
Yönetim paneli şifresi belirle:
<input type="password" name="s1" placeholder="Şifre (en az 8 karakter)">
<input type="password" name="s2" placeholder="Şifre tekrar">
<button>Kur</button></form>
<?php endif; ?>
</body></html>
