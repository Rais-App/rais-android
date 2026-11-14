import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// The version of the SDK: one place (also sent to the checkout page, and shown in its user agent).
val raisVersion = "1.0.0"
group = "app.rais"
version = raisVersion

android {
    namespace = "app.rais.checkout"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "RAIS_SDK_VERSION", "\"$raisVersion\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions.unitTests.isReturnDefaultValues = true
    lint.abortOnError = false
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // A library is compiled for the Kotlin its HOST apps use, not for the newest: an app on Kotlin 2.0 to 2.3 (React Native and Expo
        // apps among them) cannot read classes or a standard library made for 2.4 ("compiled with an incompatible version of Kotlin").
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
    // and depends on that standard library, not on the compiler's own
    coreLibrariesVersion = "2.0.21"
    explicitApi()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.activity)     // ActivityResultCaller / ComponentActivity: the constructor of RaisCheckout takes them
    implementation(libs.androidx.webkit)                // the web checkout (Style.Web, and the fallback of the native one)
    implementation(libs.kotlinx.coroutines.android)     // the native checkout's calls (already brought in by activity-ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) // lifecycleScope (already brought in by activity-ktx)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)  // the platform's org.json does not exist on the JVM that runs the unit tests
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "app.rais"
            artifactId = "rais-android"
            version = raisVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Raïs Android SDK")
                description.set("The Raïs checkout inside your Android app, drawn with native views.")
                url.set("https://rais.app/sdk")
            }
        }
    }
}
