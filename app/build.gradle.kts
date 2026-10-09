plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val allowUntested = (findProperty("allowUntested") as String?).toBoolean()
val reportUrl = ((findProperty("pbosReportUrl") as String?) ?: System.getenv("PBOS_REPORT_URL") ?: "").trim()
require(reportUrl.isEmpty() || reportUrl.matches(Regex("https://[A-Za-z0-9./_-]+"))) { "pbosReportUrl must be a plain https URL" }

/**
 * Copies the pages the app shows in pop-ups into the APK: docs/ABOUT.md,
 * docs/DEVICE-REPORT.md and docs/LICENSES.md, plus the full licence texts from
 * LICENSES/ under licenses/.
 * So each build shows them as they were when it was built.
 */
abstract class CopyAppDocs : DefaultTask() {
    @get:InputFiles abstract val docs: ConfigurableFileCollection
    @get:InputFiles abstract val licenses: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        File(out, "licenses").mkdirs()
        docs.forEach { it.copyTo(File(out, it.name)) }
        licenses.forEach { it.copyTo(File(out, "licenses/${it.name}")) }
    }
}

android {
    namespace = "org.projectbarry.pbosinstaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.projectbarry.pbosinstaller"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "ALLOW_UNTESTED", allowUntested.toString())
        buildConfigField("String", "RELEASES_REPO", "\"project-barry/pb-os\"")
        // Device-report relay (relay/worker.js), kept out of the repo: set pbosReportUrl in
        // ~/.gradle/gradle.properties or PBOS_REPORT_URL. Without it the report button is hidden.
        buildConfigField("String", "REPORT_URL", "\"$reportUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Ed25519 for the SSH signature on SHA256SUMS (lightweight API only).
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // QR code for the Discord invite in "What's in the report?".
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyAppDocs>("copy${variant.name.replaceFirstChar { it.uppercase() }}AppDocs") {
            docs.from(
                rootProject.file("docs/ABOUT.md"),
                rootProject.file("docs/DEVICE-REPORT.md"),
                rootProject.file("docs/LICENSES.md"),
            )
            licenses.from(rootProject.fileTree("LICENSES") { include("*.txt") })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyAppDocs::outputDir)
    }
}
