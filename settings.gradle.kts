pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android (connexion au débogage sans fil)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Declic"
include(":app")
