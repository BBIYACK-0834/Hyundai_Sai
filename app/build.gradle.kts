import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "kr.co.sai.diary"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "kr.co.sai.diary"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.2"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sai-speech"))
    androidResources { noCompress += "bin" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val modelFile = layout.buildDirectory.file("generated/sai-speech/speech/ggml-tiny-q5_1.bin")
val modelLicense = layout.buildDirectory.file("generated/sai-speech/speech/LICENSE-whisper.txt")
val modelHash = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
val localModel = providers.gradleProperty("saiSpeechModel").map { file(it) }
val licenseFile = layout.projectDirectory.file("src/main/cpp/LICENSE-whisper.txt")
val prepareSpeechModel = tasks.register("prepareSpeechModel") {
    inputs.property("modelHash", modelHash)
    inputs.file(localModel).optional()
    inputs.file(licenseFile)
    outputs.files(modelFile, modelLicense)
    doLast {
        val target = modelFile.get().asFile
        target.parentFile.mkdirs()
        val staged = temporaryDir.resolve(target.name).toPath()
        try {
            val input = localModel.orNull?.inputStream()
                ?: URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin")
                    .toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }.getInputStream()
            input.use { Files.copy(it, staged, StandardCopyOption.REPLACE_EXISTING) }
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(staged))
                .joinToString("") { "%02x".format(it) }
            check(digest == modelHash) { "Whisper model SHA-256 does not match." }
            Files.move(staged, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            licenseFile.asFile.copyTo(modelLicense.get().asFile, overwrite = true)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareSpeechModel) }
