Add-Type -AssemblyName System.Drawing

$path = "h:\My_Andorid_Works\ANTI GRAVITY PROJECTS\flashlight_toolkit_orginal_AG1\app\src\main\res\drawable\img_bulb_tube.png"
$bmp = [System.Drawing.Bitmap]::FromFile($path)
$bmp.RotateFlip([System.Drawing.RotateFlipType]::Rotate90FlipNone)

$tmpPath = $path + ".tmp.png"
$bmp.Save($tmpPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Remove-Item $path -Force
Rename-Item $tmpPath "img_bulb_tube.png"
Write-Host "Tube light rotated vertically successfully!"
