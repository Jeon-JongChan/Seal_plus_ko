# 공용 설정 중 현재 Seal 프로젝트에서 달라지는 값만 덮어씁니다.
@{
    BuildTask = ':app:assembleGenericDebug'
    TestTask = ':app:testGenericDebugUnitTest'
    ApkDirectory = 'app/build/outputs/apk/generic/debug'
    ApplicationId = 'com.junkfood.seal.debug'
    AvdName = 'SealApi35'
    AndroidUserDirectory = 'Seal/android-user'
    AvdDirectory = 'Seal/avd'
    LogDirectory = 'Seal/logs'
    TempDirectory = 'Seal/tmp'
}
