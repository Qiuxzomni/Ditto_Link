import java.util.Properties

plugins { id("com.android.application") }

val releaseSigningProperties = Properties().apply {
    val signingFile = rootProject.file("signing/upload-key.properties")
    if (signingFile.exists()) signingFile.inputStream().use { load(it) }
}

android {
    namespace = "com.vibedevdrop.dittolink"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.vibedevdrop.dittolink"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.7.9"
    }
    signingConfigs {
        if (releaseSigningProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.register<Copy>("copyVersionedDebugApk") {
    dependsOn("assembleDebug")
    val versionedApkDirectory = layout.buildDirectory.dir("versioned-apk")
    val currentApkName = "DittoLink.v${android.defaultConfig.versionName}.apk"
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(versionedApkDirectory)
    rename { currentApkName }
    doFirst {
        val directory = versionedApkDirectory.get().asFile
        val olderApks = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("DittoLink.v") && it.extension.equals("apk", ignoreCase = true) && it.name != currentApkName }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        olderApks.drop(1).forEach {
            if (!it.delete()) throw GradleException("Could not remove old APK: ${it.name}")
        }
    }
}
