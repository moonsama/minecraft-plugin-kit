plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

val paperApiVersion: String by rootProject

dependencies {
    implementation(project(":cosmetics-data"))
    compileOnly(project(":moonsama-paper"))
    compileOnly(project(":moonsama-skins"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(project(":moonsama-paper"))
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
    // Only the small cosmetics tables are needed here; the signed skins and compositor
    // layers ship with MoonsamaSkins / skin-compositor.
    exclude("moonsama/cosmetics/skins/**")
    exclude("moonsama/cosmetics/compositor/files/**")
    exclude("moonsama/cosmetics/compositor/components/**")
}

tasks.jar {
    archiveClassifier.set("unshaded")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
