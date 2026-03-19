// ============================================================
// Root build.gradle.kts — CloudStream 3 Extensions Repository
// ============================================================
// Based on: https://github.com/recloudstream/cloudstream-extensions
// Replace "yourname" / "YourGitHub" with your actual username.
// ============================================================

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
        // CloudStream gradle plugin — builds .cs3 plugin files
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// ── shared defaults for every sub-project ────────────────────────────────────
fun Project.configurePlugin() {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")

    extensions.getByName<com.android.build.gradle.LibraryExtension>("android").apply {
        compileSdkVersion(33)
        defaultConfig {
            minSdk = 21
            targetSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    dependencies {
        // CloudStream 3 API (provided at runtime, not bundled)
        "implementation"("com.github.recloudstream:cloudstream3:-SNAPSHOT")
        // HTTP client
        "implementation"("com.github.Blatzar:NiceHttp:0.4.11")
        // HTML parser (optional, useful for Jsoup selectors)
        "implementation"("org.jsoup:jsoup:1.18.3")
        // Kotlin stdlib
        "implementation"(kotlin("stdlib"))
    }
}

subprojects {
    afterEvaluate {
        configurePlugin()
    }
}
