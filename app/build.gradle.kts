plugins {
    id("com.android.application")
}

val releaseBannerId = providers.gradleProperty("yandexBannerId")
    .orElse(providers.environmentVariable("YANDEX_BANNER_ID")).orElse("")
val uploadKeyPath = providers.environmentVariable("UPLOAD_KEYSTORE_FILE").orNull
val uploadValues = listOf("UPLOAD_STORE_PASSWORD", "UPLOAD_KEY_ALIAS", "UPLOAD_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "com.jonkryl.packinglist"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jonkryl.packinglist"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://jonkryl.github.io/packing-list/privacy/\"")
    }
    signingConfigs {
        if (uploadKeyPath != null && uploadValues.values.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(uploadKeyPath)
                storePassword = uploadValues["UPLOAD_STORE_PASSWORD"]
                keyAlias = uploadValues["UPLOAD_KEY_ALIAS"]
                keyPassword = uploadValues["UPLOAD_KEY_PASSWORD"]
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "APPMETRICA_API_KEY", "\"\"")
            buildConfigField("String", "BANNER_ID", "\"demo-banner-yandex\"")
            buildConfigField("boolean", "ADS_TEST_MODE", "true")
        }
        release {
            buildConfigField("String", "APPMETRICA_API_KEY", "\"3600cdda-c5ed-4eb6-9a9a-14139c510bad\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "BANNER_ID", "\"${releaseBannerId.get()}\"")
            buildConfigField("boolean", "ADS_TEST_MODE", "false")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        check(Regex("R-M-\\d+-\\d+").matches(releaseBannerId.get())) {
            "Release needs a real Yandex R-M banner ID; demo IDs are allowed only in debug builds."
        }
        check(uploadKeyPath != null && file(uploadKeyPath).isFile && uploadValues.values.all { !it.isNullOrBlank() }) {
            "Release needs UPLOAD_KEYSTORE_FILE, UPLOAD_STORE_PASSWORD, UPLOAD_KEY_ALIAS and UPLOAD_KEY_PASSWORD."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseConfiguration)
}

dependencies {
    implementation("io.appmetrica.analytics:analytics:8.5.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.yandex.android:mobileads:8.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
