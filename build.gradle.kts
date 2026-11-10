// Root build file. The plugins are resolved from google()/mavenCentral() on a normal
// machine (see settings via pluginManagement defaults in Gradle) AND by the offline
// pipeline in buildtools/build_apk.sh.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
