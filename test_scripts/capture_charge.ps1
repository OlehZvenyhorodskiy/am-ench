Invoke-RestMethod -Uri 'http://localhost:25569/api/chat' -Method Post -Body '{"message":"/aquaenchant charge iceshtorm 3 AquaTester"}' -ContentType 'application/json'

Start-Sleep -Milliseconds 600
Invoke-RestMethod -Uri 'http://localhost:25569/api/screenshot' -Method Post -Body '{"filename":"charge_seq_1.png"}' -ContentType 'application/json'

Start-Sleep -Milliseconds 900
Invoke-RestMethod -Uri 'http://localhost:25569/api/screenshot' -Method Post -Body '{"filename":"charge_seq_2.png"}' -ContentType 'application/json'

Start-Sleep -Milliseconds 900
Invoke-RestMethod -Uri 'http://localhost:25569/api/screenshot' -Method Post -Body '{"filename":"charge_seq_3.png"}' -ContentType 'application/json'

Start-Sleep -Milliseconds 800
Invoke-RestMethod -Uri 'http://localhost:25569/api/screenshot' -Method Post -Body '{"filename":"charge_seq_4.png"}' -ContentType 'application/json'

Start-Sleep -Milliseconds 600
Invoke-RestMethod -Uri 'http://localhost:25569/api/screenshot' -Method Post -Body '{"filename":"charge_seq_5.png"}' -ContentType 'application/json'
