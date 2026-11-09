plugins {
    java
}

val paperApiVersion: String by rootProject

dependencies {
    compileOnly(project(":moonsama-core"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(project(":moonsama-core"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
}

val pluginVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}
