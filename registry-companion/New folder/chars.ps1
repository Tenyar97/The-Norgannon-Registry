$total = $characters.Count
$current = 0

foreach ($characterId in $characters) {
    $current++
    Write-Progress -Activity "Downloading characters" -Status "$current / $total" -PercentComplete ($current / $total * 100)
    Invoke-WebRequest -Uri "https://node.norgannon-registry.com/character/$characterId" `
        -OutFile "$characterId.json"
}