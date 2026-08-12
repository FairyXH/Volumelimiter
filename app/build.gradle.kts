import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.github.fairyxh.volumelimiter"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    val gitVersion = GitVersion.getVersion()

    defaultConfig {
        applicationId = "io.github.fairyxh.volumelimiter"
        minSdk = 30
        //noinspection OldTargetApi
        targetSdk = 36
        versionName = gitVersion[0]
        versionCode = gitVersion[1].toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

tasks.register("printReleaseInfo") {
    doLast {
        println("versionCode=${android.defaultConfig.versionCode}")
        println("versionName=${android.defaultConfig.versionName}")
        println("发布时Tag应当写：(${android.defaultConfig.versionCode}-${android.defaultConfig.versionName})")
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit)
}
