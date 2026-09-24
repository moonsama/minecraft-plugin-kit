// Pure-Java port of the Composer's 2D Minecraft skin compositor. No Paper dependency, so it
// can be unit tested against the archived legacy renders and reused outside the server.
plugins {
    `java-library`
}

dependencies {
    api(project(":cosmetics-data"))
    // Paper ships Gson; plugins that shade this module must not bundle a second copy.
    compileOnly("com.google.code.gson:gson:2.13.1")
    testImplementation("com.google.code.gson:gson:2.13.1")
}

tasks.test {
    // Optional: byte-compare every default composition with the archived legacy renders.
    systemProperty(
        "moonsama.compositor.archive",
        rootProject.layout.projectDirectory.dir("references/archive/composer-skins").asFile.absolutePath
    )
    maxHeapSize = "1g"
}
