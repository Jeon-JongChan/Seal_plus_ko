<# .SYNOPSIS 배포 APK 검사용 순수 Java 계측 APK를 프로젝트 임시 경로에서 만듭니다. #>
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../../Environment.ps1"
$probeRoot = Join-Path $env:TEMP 'mediapipe-probe'
New-Item -ItemType Directory -Force -Path "$probeRoot/classes", "$probeRoot/assets" | Out-Null
Copy-Item -LiteralPath "$ProjectRoot/app/src/androidTest/assets/face-sort/portrait.jpg" -Destination "$probeRoot/assets/portrait.jpg"
$manifest = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.plugin.validation.mediapipe.release"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35"/><application android:label="MediaPipe 검사"/><instrumentation android:name="com.plugin.validation.MediaPipeProbe" android:targetPackage="com.junkfood.seal.release.devicecheck"/></manifest>'
[IO.File]::WriteAllText("$probeRoot/AndroidManifest.xml", $manifest, [Text.UTF8Encoding]::new($false))
$platform = "$env:ANDROID_HOME/platforms/android-35/android.jar"
$buildTools = "$env:ANDROID_HOME/build-tools/35.0.0"
& javac -Xlint:-options -encoding UTF-8 -source 8 -target 8 -classpath $platform -d "$probeRoot/classes" "$PSScriptRoot/MediaPipeProbe.java"
if ($LASTEXITCODE) { throw '검사 Java 컴파일 실패' }
& jar cf "$probeRoot/probe.jar" -C "$probeRoot/classes" .
& "$buildTools/d8.bat" --lib $platform --output "$probeRoot" "$probeRoot/probe.jar"
if ($LASTEXITCODE) { throw '검사 DEX 생성 실패' }
& "$buildTools/aapt.exe" package -f -M "$probeRoot/AndroidManifest.xml" -I $platform -A "$probeRoot/assets" -F "$probeRoot/probe.apk"
Push-Location $probeRoot
try { & "$buildTools/aapt.exe" add probe.apk classes.dex } finally { Pop-Location }
$signing = ConvertFrom-StringData -StringData (Get-Content -LiteralPath "$ProjectRoot/keystore.properties" -Raw)
$probeKey = if ([IO.Path]::IsPathRooted($signing.storeFile)) { $signing.storeFile } else { Join-Path "$ProjectRoot/app" $signing.storeFile }
# 비밀번호는 환경으로만 전달해 도구 출력과 명령줄에 노출하지 않습니다.
$env:PROBE_STORE_PASSWORD = $signing.storePassword
$env:PROBE_KEY_PASSWORD = $signing.keyPassword
try {
    & "$buildTools/apksigner.bat" sign --ks $probeKey --ks-key-alias $signing.keyAlias --ks-pass env:PROBE_STORE_PASSWORD --key-pass env:PROBE_KEY_PASSWORD "$probeRoot/probe.apk"
} finally { $env:PROBE_STORE_PASSWORD = $null; $env:PROBE_KEY_PASSWORD = $null }
if ($LASTEXITCODE) { throw '검사 APK 서명 실패' }
Write-Output "$probeRoot/probe.apk"
