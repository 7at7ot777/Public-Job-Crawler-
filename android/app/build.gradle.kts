plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * The bot's settings come from the repo's own .env, the one the server reads,
 * so nothing is typed on the phone. They end up inside the APK: keep the APK
 * as private as the repo.
 */
val env: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() }?.readLines()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("\"") }
    ?: emptyMap()

fun envField(key: String) = "\"" + (env[key] ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.jobcrawler.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jobcrawler.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", envField("TELEGRAM_BOT_TOKEN"))
        buildConfigField("String", "TELEGRAM_CHAT_ID", envField("TELEGRAM_CHAT_ID"))
        // Where the Log screen's Send goes; blank sends to TELEGRAM_CHAT_ID.
        buildConfigField("String", "TELEGRAM_LOG_CHAT_ID", envField("TELEGRAM_LOG_CHAT_ID"))
        // The group topic each region's jobs go to (Topics.kt); blank posts to TELEGRAM_CHAT_ID.
        buildConfigField("String", "TELEGRAM_TOPIC_EGYPT", envField("TELEGRAM_TOPIC_EGYPT"))
        buildConfigField("String", "TELEGRAM_TOPIC_GULF", envField("TELEGRAM_TOPIC_GULF"))
        buildConfigField("String", "TELEGRAM_TOPIC_WORLDWIDE", envField("TELEGRAM_TOPIC_WORLDWIDE"))
        buildConfigField("String", "TELEGRAM_TOPIC_FREELANCE", envField("TELEGRAM_TOPIC_FREELANCE"))
        // The posts search is a paid Google API; without a key it is skipped.
        buildConfigField("String", "SERPER_API_KEY", envField("SERPER_API_KEY"))
        // Job search APIs abroad (free keys); each is skipped until its key is set.
        buildConfigField("String", "ADZUNA_APP_ID", envField("ADZUNA_APP_ID"))
        buildConfigField("String", "ADZUNA_APP_KEY", envField("ADZUNA_APP_KEY"))
        buildConfigField("String", "JOOBLE_API_KEY", envField("JOOBLE_API_KEY"))
        buildConfigField("String", "CAREERJET_API_KEY", envField("CAREERJET_API_KEY"))
        // Applying: who you are, and the Gmail account that sends CVs. All
        // optional first defaults: each is entered and changed in Settings.
        // The password is a Google "app password", never the account's own.
        buildConfigField("String", "APPLY_NAME", envField("APPLY_NAME"))
        buildConfigField("String", "APPLY_PHONE", envField("APPLY_PHONE"))
        buildConfigField("String", "APPLY_GMAIL", envField("APPLY_GMAIL"))
        buildConfigField("String", "APPLY_GMAIL_APP_PASSWORD", envField("APPLY_GMAIL_APP_PASSWORD"))
    }

    buildFeatures {
        buildConfig = true
    }

    /*
     * One key in the repo, with a password that is deliberately not a secret
     * — the same idea as Android's own debug key. All it has to do is stay the
     * same: an APK signed with another key will not install over the old one,
     * and uninstalling wipes the dedup database.
     */
    // The keystore is not in the public repository: put your own at
    // android/app/signing.keystore (alias and passwords below), or the build
    // signs with Android's debug key.
    val repoKeystore = file("signing.keystore")
    signingConfigs {
        create("repo") {
            storeFile = file("signing.keystore")
            storePassword = "jobcrawler"
            keyAlias = "jobcrawler"
            keyPassword = "jobcrawler"
        }
    }

    buildTypes {
        debug {
            signingConfig = if (repoKeystore.exists()) signingConfigs.getByName("repo") else signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (repoKeystore.exists()) signingConfigs.getByName("repo") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // android.util.Log is a stub on the JVM; let it do nothing in tests.
    // Robolectric tests (Store, Settings, screens) also need the merged resources.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // The parser fixtures the Node app checks too (FixturesTest).
        unitTests.all { it.systemProperty("fixtures", rootProject.file("../fixtures").absolutePath) }
    }
}

dependencies {
    // The whole runtime: a scheduler, an HTTP client, an HTML parser. No
    // AppCompat, no Compose, no Room — the app sleeps between runs, and what
    // it does load should be small.
    // 2.10: stops foreground work cleanly when Android 15's daily data-sync allowance runs out.
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; unit tests need the real one.
    testImplementation("org.json:json:20240303")
    // Real SQLite, SharedPreferences and Activities on the JVM, for the tests
    // that are marked @RunWith(RobolectricTestRunner::class). Test-only: the APK is unchanged.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // A synchronous WorkManager for Robolectric tests that read the schedule (HomeStateTest).
    testImplementation("androidx.work:work-testing:2.10.0")
}

/*
 * What's new in the app: the commit history of android/, written into the
 * APK as an asset that Changes.kt reads. Without git (or in a shallow clone)
 * the list is just shorter; the build never fails over it.
 */
abstract class WriteChanges : DefaultTask() {
    @get:Internal
    abstract val repo: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // New commits are not a Gradle input; reading the log is cheap.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun write() {
        val log = runCatching {
            val process = ProcessBuilder("git", "log", "--no-merges", "--format=%cI%x1f%h%x1f%B%x1e", "--", ".")
                .directory(repo.get().asFile)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) text else ""
        }.getOrDefault("")
        outputDir.get().file("changes.txt").asFile.apply {
            parentFile.mkdirs()
            writeText(log)
        }
    }
}

val writeChanges = tasks.register<WriteChanges>("writeChanges") {
    repo.set(rootProject.layout.projectDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/changes"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(writeChanges, WriteChanges::outputDir)
    }
}
