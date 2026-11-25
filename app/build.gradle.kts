import com.android.build.api.variant.FilterConfiguration
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
fun signingProp(propKey: String, envKey: String): String? =
keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
    ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
val versionMajor = 1
val versionMinor = 0
val versionPatch = 0
val baseVersionCode = (versionMajor * 10000) + (versionMinor * 100) + versionPatch
val semanticVersionName = "$versionMajor.$versionMinor.$versionPatch"
val abiVersionCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2
)
val isDevEmulator = project.findProperty("devEmulator")?.toString()?.toBoolean() == true
android {
    namespace = "com.kestrel.vpn"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kestrel.vpn"
        minSdk = 24
        targetSdk = 36
        versionCode = baseVersionCode * 10
        versionName = semanticVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf("en", "fa")
    }
    signingConfigs {
        create("release") {
            val storePath = signingProp("storeFile", "RELEASE_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
    splits {
        abi {
            isEnable = !isDevEmulator
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val abiOffset = abiVersionCodes[abi] ?: 0
            output.versionCode.set((baseVersionCode * 10) + abiOffset)
        }
    }
}
room {
    schemaDirectory("$projectDir/schemas")
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.haze)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
