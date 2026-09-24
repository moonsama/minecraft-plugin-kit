plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

val paperApiVersion: String by rootProject

dependencies {
    implementation(project(":cosmetics-data"))
    compileOnly(project(":moonsama-paper"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(project(":moonsama-paper"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
}

val pluginVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Only the cosmetics data JAR is bundled; Paper provides Gson and MoonsamaCore provides the API.
}

tasks.jar {
    archiveClassifier.set("unshaded")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
