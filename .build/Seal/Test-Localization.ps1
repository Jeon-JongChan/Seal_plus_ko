<#
.SYNOPSIS
세 언어의 번역 누락·중복·새 문구의 보간 형식과 기존 언어 유지를 검사합니다.
#>
$ErrorActionPreference = 'Stop'
$resourceRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app/src/main/res'
$translations = @{}
foreach ($folder in @('values', 'values-ko', 'values-es')) {
    $entries = @{}
    foreach ($file in Get-ChildItem (Join-Path $resourceRoot $folder) -Filter '*.xml') {
      [xml]$xml = Get-Content $file.FullName -Encoding UTF8 -Raw
      foreach ($node in $xml.resources.ChildNodes) {
        if ($node.LocalName -notin @('string', 'plurals', 'string-array')) { continue }
        if (-not $node.name) { continue }
        if ($entries.ContainsKey($node.name)) { throw "$folder 문자열 중복: $($node.name)" }
        $entries[$node.name] = $node
      }
    }
    $translations[$folder] = $entries
}
foreach ($name in $translations['values'].Keys) {
    $base = $translations['values'][$name]
    if ($base.translatable -eq 'false') { continue }
    foreach ($folder in @('values-ko', 'values-es')) {
        if (-not $translations[$folder].ContainsKey($name)) { throw "$folder 번역 누락: $name" }
        if ($name.StartsWith('loc_')) {
            $translated = $translations[$folder][$name].InnerText
            if ([string]::IsNullOrWhiteSpace($translated) -or $translated -match '\$\{') { throw "$folder 문구 오류: $name" }
            # 새 문구의 위치·숫자/문자·소수 형식이 언어별로 동일해야 런타임 형식 오류를 방지합니다.
            $format = '%(?:\d+\$)?(?:\.\d+)?[dsf]'
            $expected = @([regex]::Matches($base.InnerText, $format) | ForEach-Object { $_.Value } | Sort-Object)
            $actual = @([regex]::Matches($translated, $format) | ForEach-Object { $_.Value } | Sort-Object)
            if (($expected -join ',') -ne ($actual -join ',')) { throw "$folder 보간 형식 불일치: $name" }
        }
    }
}
$languageSource = Get-Content (Join-Path (Split-Path -Parent $resourceRoot) 'java/com/junkfood/seal/util/LanguageSettings.kt') -Encoding UTF8 -Raw
foreach ($language in @('KOREAN', 'ENGLISH', 'SPANISH', 'FRENCH', 'JAPANESE')) {
    if ($languageSource -notmatch ('to ' + $language)) { throw "기존 지원 언어 누락: $language" }
}
if ($languageSource -match 'filterKeys') { throw '기존 언어 목록이 제한되어 있습니다.' }
Write-Output "3개 언어 번역 누락·중복·보간 형식 검사 통과 ($($translations['values'].Count)개 리소스)."
