$files = Get-ChildItem 'C:\Projects\baritone-enhanced-builder\run\screenshots' | Sort-Object LastWriteTime -Descending | Select-Object -First 5
$artDir = 'C:\Users\ItzZ0nk\.gemini\antigravity-ide\brain\0e1e5d42-f1fe-480c-be45-1b2192b0a929'
Copy-Item -Path $files[4].FullName -Destination (Join-Path $artDir 'charge_orbit_1.png') -Force
Copy-Item -Path $files[3].FullName -Destination (Join-Path $artDir 'charge_orbit_2.png') -Force
Copy-Item -Path $files[2].FullName -Destination (Join-Path $artDir 'charge_orbit_3.png') -Force
Copy-Item -Path $files[1].FullName -Destination (Join-Path $artDir 'charge_cast_nova.png') -Force
Copy-Item -Path $files[0].FullName -Destination (Join-Path $artDir 'charge_lightning.png') -Force
Write-Host "Done copying screenshots"
