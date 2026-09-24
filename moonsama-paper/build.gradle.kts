plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

val paperApiVersion: String by rootProject

dependencies {
    implementation(project(":portal-client"))
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Gson ships with Paper at runtime; tests need it on the classpath explicitly.
    testImplementation("com.google.code.gson:gson:2.13.1")
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
    mergeServiceFiles()
    relocate("com.fasterxml.jackson", "com.moonsama.minecraft.internal.jackson")
    relocate("org.sqlite", "com.moonsama.minecraft.internal.sqlite")
}

tasks.jar {
    archiveClassifier.set("unshaded")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
