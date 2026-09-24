import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
}

group = "com.moonsama"
// Release builds pass the tag: ./gradlew build -PkitVersion=0.1.0 (see .github/workflows/release.yml).
version = providers.gradleProperty("kitVersion").orElse("0.1.0-SNAPSHOT").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            withSourcesJar()
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testImplementation"("org.assertj:assertj-core:3.27.7")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// build/dist: every plugin JAR under its plugin name plus the resource pack - what an operator
// drops into plugins/, what compose.yaml mounts, and what a GitHub release ships.
evaluationDependsOnChildren()

val pluginProjects = linkedMapOf(
    ":moonsama-paper" to "MoonsamaCore",
    ":moonsama-skins" to "MoonsamaSkins",
    ":moonsama-items" to "MoonsamaItems",
    ":moonsama-wardrobe" to "MoonsamaWardrobe",
    ":moonsama-gatekeeper" to "MoonsamaGatekeeper",
    ":moonsama-whale-buffs" to "MoonsamaWhaleBuffs",
    ":examples:offhand-demo" to "MoonsamaOffhandDemo",
)

val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Collects plugin JARs (stable names) and the resource pack into build/dist."
    val target = layout.buildDirectory.dir("dist")
    into(target)
    pluginProjects.forEach { (path, pluginName) ->
        val tasks = project(path).tasks
        val jarTask = tasks.findByName("shadowJar") ?: tasks.getByName("jar")
        from(jarTask) {
            into("plugins")
            rename { "$pluginName.jar" }
        }
    }
    from(project(":resourcepack").tasks.named("resourcePack"))
    from(layout.projectDirectory.files("LICENSE", "LICENSE-ASSETS", "NOTICE"))
    doLast {
        val root = target.get().asFile
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val lines = root.walkTopDown()
            .filter { it.isFile && it.name != "SHA256SUMS" }
            .sortedBy { it.relativeTo(root).path }
            .map { file ->
                val hash = java.util.HexFormat.of().formatHex(digest.digest(file.readBytes()))
                "$hash  ${file.relativeTo(root).invariantSeparatorsPath}"
            }
            .toList()
        root.resolve("SHA256SUMS").writeText(lines.joinToString("\n") + "\n")
    }
}

tasks.build {
    dependsOn(dist)
}
