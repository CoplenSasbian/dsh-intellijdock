pluginManagement {
    repositories {
        // cache-redirector mirrors the Gradle Plugin Portal and is reachable from
        // networks where plugins.gradle.org is not.
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "dsh-intellijdock"
