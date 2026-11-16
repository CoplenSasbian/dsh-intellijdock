import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dsh"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Since 2025.3 there is one IDEA distribution for both editions; the
        // `intellijIdeaCommunity` alias stops resolving there.
        intellijIdea("2025.3")
        // No bundledPlugin("com.intellij.modules.jcef") here: that module id is
        // not resolvable in the 2025.3 distribution (JCEF still lives in the
        // platform jars). The plugin descriptor declares it optional instead, so
        // the artifact loads on 2025.3 and still links to the bundled JCEF
        // plugin on 2026.2+.
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // The platform runs on JBR 21; compile against the same language level.
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

/*
 * The plugin is written in Kotlin, and the 2025.3 distribution does not put
 * `kotlin-stdlib` on the platform classpath - the only copy ships inside the
 * bundled Kotlin plugin (`plugins/Kotlin/kotlinc/lib`), which this plugin does
 * not depend on. The IntelliJ Platform Gradle Plugin strips the stdlib from the
 * plugin's `lib/` folder anyway, so it is merged into the plugin jar here.
 * Without it: `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` on the
 * first tool window, which is exactly the class of bug that only shows up on a
 * clean IDE.
 */
val bundledKotlinStdlib by configurations.creating {
    isTransitive = false
}

dependencies {
    bundledKotlinStdlib(kotlin("stdlib"))
}

tasks.named<Jar>("jar") {
    from(bundledKotlinStdlib.map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "META-INF/versions/**")
        exclude("**/*.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // No upper bound: a new major release must not need a rebuild, and the
            // JCEF dependency keeps the plugin honest.
            untilBuild = provider { null }
        }
        changeNotes = provider { file("CHANGELOG.md").readText() }
    }
    // Action index generation hangs on IDE builds whose name contains a space;
    // nothing here needs it.
    buildSearchableOptions = false
    sandboxContainer = layout.buildDirectory.dir("sandbox")
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
