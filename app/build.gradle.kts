plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "io.drivegrowth.app"
    compileSdk = 35
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    defaultConfig {
        applicationId = "io.drivegrowth.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug { applicationIdSuffix = ".simulation" }
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    compileOnly(files("${android.sdkDirectory}/platforms/android-35/optional/android.car.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":core")))
}
