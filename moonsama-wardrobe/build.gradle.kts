plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

val paperApiVersion: String by rootProject

dependencies {
    implementation(project(":skin-compositor"))
    compileOnly(project(":moonsama-paper"))
    compileOnly(project(":moonsama-skins"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(project(":moonsama-paper"))
    testImplementation(project(":moonsama-skins"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
}

val pluginVersion = project.version.toString()

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // The signed NFT skins ship with MoonsamaSkins; the wardrobe only needs the compositor data.
    exclude("moonsama/cosmetics/skins/**")
}

tasks.jar {
    archiveClassifier.set("unshaded")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
