import java.security.MessageDigest
import java.util.HexFormat

plugins {
    base
}

val resourcePack by tasks.registering(Zip::class) {
    group = "build"
    description = "Builds the sample Minecraft resource pack."
    from(layout.projectDirectory.dir("src"))
    archiveFileName.set("resourcepack.zip")
    destinationDirectory.set(layout.buildDirectory)
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    notCompatibleWithConfigurationCache("Writes a SHA-1 sidecar after packaging")

    doLast {
        val archive = archiveFile.get().asFile
        val sha1 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-1").digest(archive.readBytes())
        )
        layout.buildDirectory.file("resourcepack.sha1").get().asFile.writeText("$sha1\n")
        logger.lifecycle("Resource pack SHA-1: $sha1")
    }
}

tasks.assemble {
    dependsOn(resourcePack)
}
