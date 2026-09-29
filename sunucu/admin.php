<?php
// Uye yonetim paneli (telefondan kullanima uygun)
define('MAKRO', 1);
require __DIR__ . '/ortak.php';
session_start();

if (!file_exists(GIZLI . '/admin.hash')) exit('Önce kurulum.php ile kurulum yap.');

if (isset($_GET['cikis'])) { session_destroy(); header('Location: admin.php'); exit; }

if (empty($_SESSION['admin'])) {
    $h = '';
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        usleep(400000);
        if (password_verify($_POST['sifre'] ?? '', trim(file_get_contents(GIZLI . '/admin.hash')))) {
            session_regenerate_id(true);
            $_SESSION['admin'] = 1;
            $_SESSION['csrf'] = bin2hex(random_bytes(16));
            header('Location: admin.php'); exit;
        }
        $h = 'Şifre yanlış';
    }
    ?><!doctype html><html lang="tr"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1"><title>Makro Panel</title>
    <style>body{background:#12161c;color:#fff;font-family:sans-serif;padding:16px}
    input{width:100%;padding:14px;margin:8px 0;border-radius:10px;border:0;box-sizing:border-box;font-size:16px}
    button{background:#2e9e5b;color:#fff;border:0;padding:14px;border-radius:10px;width:100%;font-size:17px}</style></head>
    <body><h2>Makro Panel</h2><?php if ($h) echo '<p style="color:#ff7b7b">' . e($h) . '</p>'; ?>
    <form method="post"><input type="password" name="sifre" placeholder="Panel şifresi" autofocus><button>Giriş</button></form>
    </body></html><?php
    exit;
}

$pdo = db();
sema_guncelle();
$mesaj = '';
$yeniSifre = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($_SESSION['csrf'] ?? '', $_POST['csrf'] ?? '')) exit('Oturum hatası, sayfayı yenile.');
    $is = $_POST['is'] ?? '';
    $id = (int)($_POST['id'] ?? 0);
    $gun = max(0, min(3650, (int)($_POST['gun'] ?? 0)));
    try {
        if ($is === 'ekle') {
            $k = preg_replace('/[^a-zA-Z0-9_.]/', '', $_POST['kullanici'] ?? '');
            $isim = trim(mb_substr($_POST['isim'] ?? '', 0, 60));
            if ($k === '' || $gun < 1) $mesaj = 'Kullanıcı adı ve süre gerekli.';
            else {
                $yeniSifre = trim($_POST['sifre'] ?? '') ?: rastgele_sifre();
                $pdo->prepare('INSERT INTO makro_uyeler (kullanici, sifre_hash, isim, bitis, olusturma) VALUES (?,?,?,?,?)')
                    ->execute([$k, password_hash($yeniSifre, PASSWORD_DEFAULT), $isim, time() + $gun * 86400, time()]);
                $mesaj = "✅ '$k' eklendi ($gun gün). Şifre: $yeniSifre";
            }
        } elseif ($is === 'uzat' && $gun > 0) {
            $pdo->prepare('UPDATE makro_uyeler SET bitis = GREATEST(bitis, ?) + ? WHERE id = ?')
                ->execute([time(), $gun * 86400, $id]);
            $mesaj = "✅ $gun gün eklendi.";
        } elseif ($is === 'cihaz') {
            $pdo->prepare('UPDATE makro_uyeler SET cihaz = NULL WHERE id = ?')->execute([$id]);
            $mesaj = '✅ Cihaz sıfırlandı, yeni cihazdan giriş yapabilir.';
        } elseif ($is === 'durum') {
            $pdo->prepare('UPDATE makro_uyeler SET aktif = 1 - aktif WHERE id = ?')->execute([$id]);
            $mesaj = '✅ Durum değişti.';
        } elseif ($is === 'sifre') {
            $yeniSifre = rastgele_sifre();
            $pdo->prepare('UPDATE makro_uyeler SET sifre_hash = ? WHERE id = ?')
                ->execute([password_hash($yeniSifre, PASSWORD_DEFAULT), $id]);
            $mesaj = "✅ Yeni şifre: $yeniSifre";
        } elseif ($is === 'mesaj') {
            $metin = trim(mb_substr($_POST['metin'] ?? '', 0, 500));
            $kime = (int)($_POST['kime'] ?? 0);
            if ($metin === '') $mesaj = 'Mesaj boş olamaz.';
            else {
                $pdo->prepare('INSERT INTO makro_mesajlar (uye_id, metin, zaman) VALUES (?, ?, ?)')
                    ->execute([$kime > 0 ? $kime : null, $metin, time()]);
                $mesaj = '✅ Mesaj gönderildi. En geç 1 dakika içinde görünür.';
            }
        } elseif ($is === 'mesaj_sil') {
            $pdo->prepare('DELETE FROM makro_mesajlar WHERE id = ?')->execute([$id]);
            $mesaj = '✅ Mesaj silindi.';
        } elseif ($is === 'surum') {
            ayar_yaz('min_surum', (string)max(0, (int)($_POST['min_surum'] ?? 0)));
            ayar_yaz('apk_url', trim($_POST['apk_url'] ?? ''));
            ayar_yaz('surum_mesaj', trim(mb_substr($_POST['surum_mesaj'] ?? '', 0, 300)));
            $mesaj = '✅ Sürüm ayarı kaydedildi. Eski sürümler en geç 1 dakika içinde durur.';
        } elseif ($is === 'sil') {
            $pdo->prepare('DELETE FROM makro_uyeler WHERE id = ?')->execute([$id]);
            $mesaj = '✅ Silindi.';
        }
    } catch (PDOException $ex) {
        $mesaj = ($ex->getCode() == 23000) ? 'Bu kullanıcı adı zaten var.' : 'Veritabanı hatası.';
    }
}

$uyeler = $pdo->query('SELECT * FROM makro_uyeler ORDER BY bitis DESC')->fetchAll();
$mesajlar = $pdo->query('SELECT m.*, u.kullanici FROM makro_mesajlar m LEFT JOIN makro_uyeler u ON u.id = m.uye_id ORDER BY m.id DESC LIMIT 10')->fetchAll();
$minSurum = (int)ayar_al('min_surum', '0');
$enYeni = 0;
foreach ($uyeler as $u) $enYeni = max($enYeni, (int)($u['surum'] ?? 0));
$csrf = $_SESSION['csrf'];

function form(string $is, int $id, string $etiket, string $renk, bool $gunlu = false, string $onay = ''): string {
    global $csrf;
    $g = $gunlu ? '<select name="gun"><option>3</option><option>7</option><option>15</option><option selected>30</option></select>' : '';
    $o = $onay ? ' onsubmit="return confirm(\'' . e($onay) . '\')"' : '';
    return "<form method=\"post\" class=\"in\"$o><input type=\"hidden\" name=\"csrf\" value=\"$csrf\">"
        . "<input type=\"hidden\" name=\"is\" value=\"$is\"><input type=\"hidden\" name=\"id\" value=\"$id\">$g"
        . "<button style=\"background:$renk\">$etiket</button></form>";
}
?><!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Makro Panel</title>
<style>
body{background:#12161c;color:#fff;font-family:sans-serif;padding:12px;margin:0}
.k{background:#1c232d;padding:14px;border-radius:14px;margin:10px 0}
input,select{padding:11px;margin:4px 0;border-radius:9px;border:0;font-size:15px;box-sizing:border-box}
.tam{width:100%}
button{color:#fff;border:0;padding:10px 12px;border-radius:9px;font-size:14px;background:#2d3846}
.in{display:inline-block;margin:3px 2px}
.m{background:#2e9e5b33;border:1px solid #2e9e5b;padding:12px;border-radius:10px;word-break:break-all}
.gri{color:#9aa4b2;font-size:13px}.kirmizi{color:#ff7b7b}.yesil{color:#6fdc9a}
a{color:#e0b04a}
</style></head><body>
<h2 style="margin:6px 0">Makro Panel <a href="?cikis=1" style="font-size:14px;float:right">Çıkış</a></h2>
<?php if ($mesaj): ?><div class="m"><?= e($mesaj) ?></div><?php endif; ?>

<div class="k"><b>📢 Mesaj gönder</b>
<form method="post">
<input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="mesaj">
<select class="tam" name="kime">
<option value="0">Herkese</option>
<?php foreach ($uyeler as $u): ?><option value="<?= (int)$u['id'] ?>"><?= e($u['kullanici']) ?><?= $u['isim'] ? ' (' . e($u['isim']) . ')' : '' ?></option><?php endforeach; ?>
</select>
<textarea class="tam" name="metin" rows="3" maxlength="500" placeholder="Örn: Aydın okula gel / Yarın yeni sürüm geliyor" style="padding:11px;border-radius:9px;border:0;font-size:15px;margin:4px 0"></textarea>
<button class="tam" style="background:#2e9e5b;padding:13px">Gönder</button>
</form>
<?php if ($mesajlar): ?><div class="gri" style="margin-top:8px">Son mesajlar:</div>
<?php foreach ($mesajlar as $m): ?>
<div style="border-top:1px solid #33455c;padding:6px 0">
<span class="gri"><?= date('d.m H:i', (int)$m['zaman']) ?> • <?= $m['uye_id'] ? e($m['kullanici'] ?? '?') : 'Herkese' ?></span><br>
<?= e($m['metin']) ?> <?= form('mesaj_sil', (int)$m['id'], 'Sil', '#b33a3a') ?>
</div>
<?php endforeach; endif; ?>
</div>

<div class="k"><b>⬆ Sürüm</b>
<p class="gri" style="margin:4px 0">Üyelerde görülen en yeni sürüm: <b><?= $enYeni ?: '-' ?></b>.
"En düşük sürüm"den eski olanların makrosu durur ve güncellemeleri istenir. 0 = kontrol kapalı.</p>
<form method="post">
<input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="surum">
<input class="tam" type="number" name="min_surum" value="<?= $minSurum ?>" placeholder="En düşük sürüm (ör. 25)">
<input class="tam" name="apk_url" value="<?= e(ayar_al('apk_url', '')) ?>" placeholder="APK indirme linki (https://...)">
<input class="tam" name="surum_mesaj" value="<?= e(ayar_al('surum_mesaj', '')) ?>" placeholder="Mesaj (boş: Yeni sürüm çıktı...)">
<button class="tam" style="background:#8a6d1f;padding:13px">Kaydet</button>
</form></div>

<div class="k"><b>Yeni üye</b>
<form method="post">
<input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="is" value="ekle">
<input class="tam" name="kullanici" placeholder="Kullanıcı adı (ör. ahmet)" required>
<input class="tam" name="isim" placeholder="İsim (isteğe bağlı)">
<input class="tam" name="sifre" placeholder="Şifre (boş bırak: otomatik)">
<select class="tam" name="gun">
<option value="3">3 gün</option><option value="7">7 gün</option><option value="15">15 gün</option>
<option value="30" selected>1 ay</option><option value="90">3 ay</option><option value="3650">Süresiz (10 yıl)</option>
</select>
<button class="tam" style="background:#2e9e5b;padding:13px">Ekle</button>
</form></div>

<?php foreach ($uyeler as $u):
    $kalan = (int)$u['bitis'] - time();
    $durum = !(int)$u['aktif'] ? '<span class="kirmizi">Kapalı</span>'
        : ($kalan > 0 ? '<span class="yesil">' . e(kalan_yazi((int)$u['bitis'])) . '</span>' : '<span class="kirmizi">Süresi doldu</span>');
?>
<div class="k">
<b><?= e($u['kullanici']) ?></b> <?= $u['isim'] ? '<span class="gri">(' . e($u['isim']) . ')</span>' : '' ?><br>
<?= $durum ?> <span class="gri">• bitiş <?= date('d.m.Y H:i', (int)$u['bitis']) ?></span><br>
<span class="gri">Sürüm: <?= !empty($u['surum']) ? (int)$u['surum'] : '-' ?> • Cihaz: <?= $u['cihaz'] ? e($u['cihaz']) : 'henüz bağlanmadı' ?> • Son giriş: <?= $u['son_giris'] ? date('d.m H:i', (int)$u['son_giris']) : '-' ?></span><br>
<?php if (!empty($u['son_hata'])): ?>
<div style="background:#3a2226;border-left:3px solid #e06464;padding:6px 8px;margin:6px 0;border-radius:0 8px 8px 0;font-size:12px;word-break:break-all">
⚠ Son hata (<?= date('d.m H:i', (int)$u['son_hata_zaman']) ?>): <?= e($u['son_hata']) ?>
</div>
<?php endif; ?>
<?= form('uzat', (int)$u['id'], '+ Süre ekle', '#2e9e5b', true) ?>
<?= form('durum', (int)$u['id'], (int)$u['aktif'] ? 'Kapat' : 'Aç', '#8a6d1f') ?>
<?= form('cihaz', (int)$u['id'], 'Cihaz sıfırla', '#2d3846', false, 'Cihaz bağlantısı sıfırlansın mı?') ?>
<?= form('sifre', (int)$u['id'], 'Yeni şifre', '#2d3846', false, 'Yeni şifre oluşturulsun mu?') ?>
<?= form('sil', (int)$u['id'], 'Sil', '#b33a3a', false, 'Bu üye silinsin mi?') ?>
</div>
<?php endforeach; ?>
<?php if (!$uyeler): ?><p class="gri">Henüz üye yok.</p><?php endif; ?>
</body></html>
