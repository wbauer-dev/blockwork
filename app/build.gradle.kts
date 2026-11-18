import com.android.build.api.variant.BuildConfigField
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import org.gradle.process.ExecOperations

// Release version: CI passes -PappVersionName=1.2.3 -PappVersionCode=10203 (derived from the git tag).
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.0.0-dev")
val appVersionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(1)

// Release signing from environment variables (GitHub Actions secrets or a local shell).
val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull
val signingConfigured = signingStoreFile != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.blockwork"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.blockwork"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "Blockwork"
    }

    signingConfigs {
        if (signingConfigured) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Separate app id: a development build can be installed next to a release.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Blockwork Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Shown in Settings → About. Value sources run on every build, so the configuration cache does not
// freeze them. The date has no time of day: a timestamp would regenerate BuildConfig on every build.
abstract class GitCommitSource : ValueSource<String, GitCommitSource.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: Property<String>
    }

    @get:Inject abstract val exec: ExecOperations

    private fun git(vararg args: String): String? = try {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(listOf("git", "-C", parameters.repoDir.get()) + args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString().trim() else null
    } catch (e: Exception) {
        null // no git installed
    }

    /** "47f6244ac0", "47f6244ac0-dirty" with uncommitted changes to tracked files, "unknown" outside a repo. */
    override fun obtain(): String {
        val hash = git("rev-parse", "--short=10", "HEAD") ?: return "unknown"
        val dirty = !git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()
        return if (dirty) "$hash-dirty" else hash
    }
}

abstract class BuildDateSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = LocalDate.now(ZoneOffset.UTC).toString()
}

val gitCommit = providers.of(GitCommitSource::class) { parameters.repoDir.set(rootDir.absolutePath) }
val buildDate = providers.of(BuildDateSource::class) {}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put("GIT_COMMIT", gitCommit.map { BuildConfigField("String", "\"$it\"", "Built commit") })
        variant.buildConfigFields?.put("BUILD_DATE", buildDate.map { BuildConfigField("String", "\"$it\"", "Build date (UTC)") })
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":ble"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
