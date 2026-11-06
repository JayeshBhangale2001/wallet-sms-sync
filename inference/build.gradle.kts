plugins { id("com.android.library") }
android {
    namespace = "io.github.jayeshb.walletsmssync.inference"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
// A small Java facade isolates the pinned native API and resource lifecycle from app/learning logic.
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.8.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
}
