<# .SYNOPSIS 실제 앱 클래스를 빌드한 뒤 프레임워크 없는 JVM 회귀 검사를 실행합니다. #>
. "$PSScriptRoot/Environment.ps1"
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$checkRoot = Join-Path $env:TEMP 'optimization-check'
New-Item -ItemType Directory -Force -Path $checkRoot | Out-Null
$checkInit = Join-Path $checkRoot 'classpath.init.gradle'
# 실제 Kotlin 컴파일 입력을 재사용해 별도 의존성이나 가짜 앱 모델을 만들지 않습니다.
$checkGradle = @'
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app == null) return
    app.tasks.register('optimizationClasspath') {
        dependsOn('compileGenericDebugJavaWithJavac')
        doLast {
            def paths = app.tasks.getByName('compileGenericDebugKotlin').libraries.files.collect { it.absolutePath }
            paths += app.file('build/tmp/kotlin-classes/genericDebug').absolutePath
            paths += app.file('build/intermediates/javac/genericDebug/compileGenericDebugJavaWithJavac/classes').absolutePath
            def output = gradle.rootProject.file('.build/tmp/optimization-check/classpath.txt')
            output.setText(paths.join(File.pathSeparator), 'UTF-8')
        }
    }
}
'@
[IO.File]::WriteAllText($checkInit, $checkGradle, [Text.UTF8Encoding]::new($false))
Push-Location $ProjectRoot
try {
    $ErrorActionPreference = 'Continue'
    & "$ProjectRoot/gradlew.bat" ':app:optimizationClasspath' '-I' $checkInit '--offline' '--no-daemon' '--no-configuration-cache' '--console=plain' '--max-workers=2' '-Pkotlin.compiler.execution.strategy=in-process' '-PnoSplits' '-PABI_FILTERS=x86_64' 2>&1 |
        Tee-Object -FilePath "$LogRoot/optimization-check-build.log" | Select-Object -Last 12
    $checkExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($checkExitCode -ne 0) { throw "검사용 앱 클래스 빌드 실패 ($checkExitCode)" }
    $checkClasspath = [IO.File]::ReadAllText((Join-Path $checkRoot 'classpath.txt')).Replace('\', '/')
    $checkSource = (Join-Path $PSScriptRoot 'Check-Optimizations.java').Replace('\', '/')
    $checkArguments = Join-Path $checkRoot 'java.args'
    # Windows 명령 길이 제한과 공백·한글 경로를 Java의 인자 파일로 처리합니다.
    [IO.File]::WriteAllText($checkArguments, "-ea`n-Dfile.encoding=UTF-8`n--class-path`n`"$checkClasspath`"`n`"$checkSource`"`n", [Text.UTF8Encoding]::new($false))
    $ErrorActionPreference = 'Continue'
    & "$env:JAVA_HOME/bin/java.exe" "@$checkArguments" 2>&1 | Tee-Object -FilePath "$LogRoot/optimization-check.log" | Select-Object -Last 15
    $checkExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($checkExitCode -ne 0) { throw "최적화 회귀 검사 실패 ($checkExitCode)" }
} finally { Pop-Location }
