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
    "skin-compositor",
    "moonsama-skins",
    "moonsama-items",
    "moonsama-wardrobe",
    "moonsama-gatekeeper",
    "moonsama-whale-buffs",
    "examples:offhand-demo",
    "resourcepack",
)
