import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials live outside version control (see keystore.properties.example
// for the format) - this file only exists on machines that actually need to produce a signed
// release build, so a plain debug build still works with none of this present.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.mhmh2.englishbite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mhmh2.englishbite"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0"

        // Google sign-in: the OAuth *web* client ID the ID token is issued for (the server checks it
        // as EB_GOOGLE_CLIENT_ID). Public, like an API key id. `-PgoogleWebClientId=` (empty) hides
        // the "구글로 계속하기" button. It only works once an Android OAuth client for this package
        // and the signing key's SHA-1 exists in the same Google Cloud project.
        val googleClient = (project.findProperty("googleWebClientId") as String?)
            ?: "865652175610-pfqom7oppf92bob56eb5c19ac7el80p3.apps.googleusercontent.com"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClient\"")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        // Debug builds talk to the dev server (same machine as production, separate data and
        // code - see backend/deploy/README.md) and ignore the remote config file, so trying
        // something never touches testers' data. Release builds use production and follow
        // docs/app-config.json.
        debug {
            // A different application id (and name, see src/debug/res/values/strings.xml) so this
            // build installs NEXT TO the Play/release app instead of replacing it - the two are
            // signed with different keys, so Android refuses to install one over the other.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "DEFAULT_API_BASE", "\"https://dev.184-193-203-68.sslip.io/\"")
            buildConfigField("boolean", "USE_REMOTE_CONFIG", "false")
        }
        release {
            buildConfigField("String", "DEFAULT_API_BASE", "\"https://184-193-203-68.sslip.io/\"")
            buildConfigField("boolean", "USE_REMOTE_CONFIG", "true")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // AGP 8.7.2's bundled lint crashes running this specific detector against this
        // Kotlin version (a KaCallableMemberCall/interface mismatch inside the lint tool
        // itself, not a real issue in this codebase) - disabling just this one check lets
        // release builds finish instead of failing on an unrelated tooling bug.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.youtube.player.core)
    implementation(libs.coil.compose)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.google.id)
}
