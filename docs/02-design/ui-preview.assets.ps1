Add-Type -AssemblyName System.Drawing
$src = 'D:\Six Gods\Desktop\河师相关UI'
$sc  = Join-Path $src '校园风光'
$out = 'D:\DevEnv\logs\assets4'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Process {
    param([string]$In, [string]$OutFile, [int]$Cx, [int]$Cy, [int]$Cw, [int]$Ch,
          [int]$W, [int]$H, [string]$Fmt = 'jpg', [int]$Quality = 82)
    $img = [System.Drawing.Image]::FromFile($In)
    if (($Cx + $Cw) -gt $img.Width -or ($Cy + $Ch) -gt $img.Height) {
        throw ("crop out of range for " + $In + " (" + $img.Width + "x" + $img.Height + ")")
    }
    $bmp = New-Object System.Drawing.Bitmap $W, $H
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $dest = New-Object System.Drawing.Rectangle 0, 0, $W, $H
    $srcR = New-Object System.Drawing.Rectangle $Cx, $Cy, $Cw, $Ch
    $g.DrawImage($img, $dest, $srcR, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    if ($Fmt -eq 'png') {
        $bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
    } else {
        $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
        $ps = New-Object System.Drawing.Imaging.EncoderParameters 1
        $ps.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), $Quality
        $bmp.Save($OutFile, $enc, $ps)
    }
    $bmp.Dispose(); $img.Dispose()
    Write-Host ("  OK  {0,-22} {1}x{2} <- crop {3},{4} {5}x{6}" -f (Split-Path $OutFile -Leaf), $W, $H, $Cx, $Cy, $Cw, $Ch)
}

Write-Host '--- 首页横幅照片（760x289 ≈ 2.63:1，与显示槽位同比例，避免二次裁切）---'
Process -In (Join-Path $sc '时光塔.jpg')     -OutFile (Join-Path $out 'hero-hebtu.jpg')   -Cx 0 -Cy 60  -Cw 1000 -Ch 380 -W 760 -H 289
Process -In (Join-Path $sc '校内行道.jpg')   -OutFile (Join-Path $out 'hero-gingko.jpg')  -Cx 0 -Cy 140 -Cw 1000 -Ch 380 -W 760 -H 289
Process -In (Join-Path $sc '多花.jpg')       -OutFile (Join-Path $out 'hero-celadon.jpg') -Cx 500 -Cy 150 -Cw 1000 -Ch 380 -W 760 -H 289
Process -In (Join-Path $sc '时光塔下.jpg')   -OutFile (Join-Path $out 'hero-ink.jpg')     -Cx 0 -Cy 180 -Cw 1000 -Ch 380 -W 760 -H 289
Process -In (Join-Path $sc '东门黎明.jpg')   -OutFile (Join-Path $out 'hero-jiang.jpg')   -Cx 0 -Cy 45  -Cw 1000 -Ch 380 -W 760 -H 289
Process -In (Join-Path $sc '教学楼夜景.jpg') -OutFile (Join-Path $out 'hero-night.jpg')   -Cx 0 -Cy 90  -Cw 1000 -Ch 380 -W 760 -H 289

Write-Host '--- 登录页背景（900x1000 竖构图，源自 2000px 大图，缩放比 < 1 所以清晰）---'
Process -In (Join-Path $sc '多花.jpg') -OutFile (Join-Path $out 'login-900.jpg') -Cx 420 -Cy 0 -Cw 1180 -Ch 1310 -W 900 -H 1000

Write-Host '--- 设计系统画廊用的小图 ---'
Process -In (Join-Path $sc '花.jpg') -OutFile (Join-Path $out 'gal-flower.jpg') -Cx 0 -Cy 0 -Cw 1000 -Ch 667 -W 520 -H 347

Write-Host '--- 校徽 / 题字（沿用上一轮，重新生成 base64）---'
Process -In (Join-Path $src '学校LOGO.jpg') -OutFile (Join-Path $out 'emblem-128.png') -Cx 0 -Cy 0 -Cw 270 -Ch 274 -W 128 -H 128 -Fmt png
Process -In (Join-Path $src '学校LOGO.jpg') -OutFile (Join-Path $out 'emblem-64.png')  -Cx 0 -Cy 0 -Cw 270 -Ch 274 -W 64  -H 64  -Fmt png
Process -In (Join-Path $src 'Title.png')    -OutFile (Join-Path $out 'motto-320.png')  -Cx 0 -Cy 0 -Cw 709 -Ch 709 -W 320 -H 320 -Fmt png

Write-Host '--- base64 片段 ---'
$total = 0
foreach ($f in (Get-ChildItem $out -File | Sort-Object Name)) {
    $bytes = [IO.File]::ReadAllBytes($f.FullName)
    $mime = if ($f.Extension -eq '.jpg') { 'image/jpeg' } else { 'image/png' }
    $uri = 'data:' + $mime + ';base64,' + [Convert]::ToBase64String($bytes)
    [IO.File]::WriteAllText((Join-Path $out ($f.Name + '.b64.txt')), $uri, [Text.UTF8Encoding]::new($false))
    $total += $uri.Length
    Write-Host ("  {0,-22} {1,7} KB 图片 -> {2,8} 字符 base64" -f $f.Name, [math]::Round($f.Length/1KB,1), $uri.Length)
}
Write-Host ("base64 合计 = " + [math]::Round($total/1KB,1) + " KB")
