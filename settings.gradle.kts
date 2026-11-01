pluginManagement {
    repositories {
        // this machine cannot reach gradlePluginPortal/google/mavenCentral from the JVM
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // aliyun mirrors, see android-env notes for this machine
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        // used for OCR
        maven { setUrl("https://jitpack.io") }
    }
}
rootProject.name = "Translate You"
include(":app")
include(":translation-engines")
