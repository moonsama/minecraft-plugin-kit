// Packages the static cosmetics data (signed NFT skins, item skin mappings, compositor
// layers) as a plain resources JAR so plugins can bundle it without any network access.
plugins {
    java
}

sourceSets {
    main {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
}

tasks.processResources {
    from(layout.projectDirectory) {
        include("collections.json", "item-skins.json", "game-passes.json", "whale-buffs.json", "README.md")
        include("skins/**", "compositor/**")
        into("moonsama/cosmetics")
    }
}

tasks.named<Jar>("sourcesJar") {
    enabled = false
}
