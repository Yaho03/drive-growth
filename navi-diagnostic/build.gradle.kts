plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "io.drivegrowth.navidiagnostic"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.drivegrowth.navidiagnostic"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.1-issue80-followup"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        create("noNaviPermission") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "debug"
        }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    testImplementation("junit:junit:4.13.2")
}
