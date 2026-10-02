Add-Type -AssemblyName System.Drawing

$drawablePath = "h:\My_Andorid_Works\ANTI GRAVITY PROJECTS\flashlight_toolkit_orginal_AG1\app\src\main\res\drawable"
$files = Get-ChildItem -Path $drawablePath -Filter "img_bulb_*.jpg"

foreach ($file in $files) {
    Write-Host "Processing $($file.Name)..."
    $srcBmp = [System.Drawing.Bitmap]::FromFile($file.FullName)
    
    # Create new ARGB bitmap
    $pngBmp = New-Object System.Drawing.Bitmap($srcBmp.Width, $srcBmp.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    
    for ($y = 0; $y -lt $srcBmp.Height; $y++) {
        for ($x = 0; $x -lt $srcBmp.Width; $x++) {
            $pixel = $srcBmp.GetPixel($x, $y)
            $r = $pixel.R
            $g = $pixel.G
            $b = $pixel.B
            
            # Max brightness among RGB components
            $maxC = [Math]::Max($r, [Math]::Max($g, $b))
            
            if ($maxC -lt 30) {
                # Completely transparent background
                $pngBmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
            } elseif ($maxC -lt 70) {
                # Smooth alpha transition at edges
                $alpha = [int]((($maxC - 30) / 40.0) * 255)
                $alpha = [Math]::Min(255, [Math]::Max(0, $alpha))
                # Boost brightness of edge pixels so they don't look muddy
                $factor = 255.0 / [Math]::Max(1, $maxC)
                $nr = [Math]::Min(255, [int]($r * $factor))
                $ng = [Math]::Min(255, [int]($g * $factor))
                $nb = [Math]::Min(255, [int]($b * $factor))
                $pngBmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($alpha, $nr, $ng, $nb))
            } else {
                # Preserve light bulb outline, filament, and base pixels
                $pngBmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $r, $g, $b))
            }
        }
    }
    
    $outName = $file.Name.Replace(".jpg", ".png")
    $outPath = Join-Path $drawablePath $outName
    $pngBmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $srcBmp.Dispose()
    $pngBmp.Dispose()
    Write-Host "Saved $outName successfully!"
}
