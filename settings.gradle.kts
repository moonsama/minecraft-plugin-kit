pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "moonsama-minecraft-plugin-kit"

include(
    "portal-client",
    "moonsama-paper",
    "cosmetics-data",
    "moonsama-skins",
    "examples:offhand-demo",
    "resourcepack",
)
