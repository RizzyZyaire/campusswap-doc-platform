Add-Type -AssemblyName System.Drawing
$src = 'D:\Six Gods\Desktop\河师相关UI'
$out = 'D:\DevEnv\logs\assets'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Resize-Image {
    param([string]$In, [string]$OutFile, [int]$W, [int]$H, [string]$Fmt, [int]$Quality)
    $img = [System.Drawing.Image]::FromFile($In)
    $bmp = New-Object System.Drawing.Bitmap $W, $H
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    if ($Fmt -eq 'png') { $g.Clear([System.Drawing.Color]::Transparent) }
    $g.DrawImage($img, 0, 0, $W, $H)
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
}

# 1) 校徽（方版）128 / 64，白底转 PNG
Resize-Image -In (Join-Path $src '学校LOGO.jpg') -OutFile (Join-Path $out 'emblem-128.png') -W 128 -H 128 -Fmt png
Resize-Image -In (Join-Path $src '学校LOGO.jpg') -OutFile (Join-Path $out 'emblem-64.png')  -W 64  -H 64  -Fmt png

# 2) 校园风景（1920x500）压到 1600 宽 JPEG q80
Resize-Image -In (Join-Path $src '风景照.jpg') -OutFile (Join-Path $out 'campus-1600.jpg') -W 1600 -H 417 -Fmt jpg -Quality 80

# 3) 校训题字（709x709）压到 320 宽 PNG（保留透明）
Resize-Image -In (Join-Path $src 'Title.png') -OutFile (Join-Path $out 'motto-320.png') -W 320 -H 320 -Fmt png

# 4) 校徽+校名（328x299）压到 260 宽，登录页用
Resize-Image -In (Join-Path $src 'Logo&Name.png') -OutFile (Join-Path $out 'brand-260.png') -W 260 -H 237 -Fmt png

Write-Host '--- 产出 ---'
Get-ChildItem $out -File | ForEach-Object {
    Write-Host ("{0,-20} {1,8} KB" -f $_.Name, [math]::Round($_.Length / 1KB, 1))
}

# 5) 采样官方标准色（校徽外环 / 中心徽记）
$img = [System.Drawing.Image]::FromFile((Join-Path $src '学校LOGO.jpg'))
$bmp = New-Object System.Drawing.Bitmap $img
$pts = @(
    @{ n = 'ring-top';    x = 135; y = 14 },
    @{ n = 'ring-left';   x = 20;  y = 137 },
    @{ n = 'center-seal'; x = 120; y = 130 },
    @{ n = 'text-motto';  x = 112; y = 112 },
    @{ n = 'bg';          x = 6;   y = 6 }
)
Write-Host '--- 校徽采样色 ---'
foreach ($p in $pts) {
    $c = $bmp.GetPixel($p.x, $p.y)
    Write-Host ("{0,-14} #{1:X2}{2:X2}{3:X2}" -f $p.n, $c.R, $c.G, $c.B)
}
$bmp.Dispose(); $img.Dispose()

# 6) 生成 base64 片段（供 HTML 内联，避免外链）
foreach ($f in @('emblem-128.png', 'emblem-64.png', 'campus-1600.jpg', 'motto-320.png', 'brand-260.png')) {
    $path = Join-Path $out $f
    $bytes = [IO.File]::ReadAllBytes($path)
    $b64 = [Convert]::ToBase64String($bytes)
    $mime = if ($f -like '*.jpg') { 'image/jpeg' } else { 'image/png' }
    $uri = 'data:' + $mime + ';base64,' + $b64
    [IO.File]::WriteAllText((Join-Path $out ($f + '.b64.txt')), $uri, [Text.UTF8Encoding]::new($false))
    Write-Host ("{0,-20} base64 长度 {1}" -f $f, $uri.Length)
}
