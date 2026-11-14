plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.journeymanager.pleos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.journeymanager.pleos"
        minSdk = 28          // Pleos Connect: API 28 or higher (signing-key rotation requirement)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // The web app the shell shows. Debug builds may point at the Vite dev server on the host (10.0.2.2 = the emulator's host).
        buildConfigField("String", "APP_URL", "\"https://journey-manager-main.vercel.app/\"")
        // The car this build is installed in (the SDK does not tell the model; CarInfo gives only the CRN).
        buildConfigField("String", "CAR_MAKER", "\"${project.findProperty("carMaker") ?: "현대"}\"")
        buildConfigField("String", "CAR_MODEL", "\"${project.findProperty("carModel") ?: "아이오닉 6"}\"")
        // -PsoftwareWebView=true draws the WebView without the GPU: the emulator's software GL crashed Chrome's in-process GPU thread.
        buildConfigField("boolean", "SOFTWARE_WEBVIEW", "${project.findProperty("softwareWebView") == "true"}")
    }
    buildTypes {
        debug { buildConfigField("String", "APP_URL", "\"${project.findProperty("appUrl") ?: "https://journey-manager-main.vercel.app/"}\"") }
        // 공모전 제출용 release: 실행할 수 있게 이 기기의 디버그 키로 서명 (키 파일은 저장소에 넣지 않음)
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { buildConfig = true }
    // The standard Android Car API (android.car.CarInfoManager) for the car's make and model.
    useLibrary("android.car")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
