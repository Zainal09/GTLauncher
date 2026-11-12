pluginManagement {
    repositories {
        maven("https://maven.google.com")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.google.com")
        google()
        mavenCentral()
    }
}

rootProject.name = "GTLauncher"
include(":app")
