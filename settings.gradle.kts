pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
// Credenziali GitHub Packages per karoo-ext: gpr.user/gpr.key in local.properties
// (mai committato), con fallback su ~/.gradle/gradle.properties e variabili d'ambiente.
val localProps = java.util.Properties().also { props ->
    rootDir.resolve("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}
val gradleUserProps = java.util.Properties().also { props ->
    File(gradle.gradleUserHomeDir, "gradle.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}

fun gprUser() = localProps["gpr.user"] as String?
    ?: gradleUserProps["gpr.user"] as String?
    ?: System.getenv("GPR_USER")
    ?: ""

fun gprKey() = localProps["gpr.key"] as String?
    ?: gradleUserProps["gpr.key"] as String?
    ?: System.getenv("GPR_TOKEN")
    ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()   // KarooSwitches until it is on JitPack
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprUser()
                password = gprKey()
            }
        }
    }
}

rootProject.name = "PoiAhead"
include(":app")
