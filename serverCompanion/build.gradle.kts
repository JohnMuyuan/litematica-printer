plugins {
    id("net.fabricmc.fabric-loom")
}

group = modMavenGroup
version = fullProjectVersion

base {
    archivesName.set("litematica-printer-server")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net")
}

// -Pserver_mc=26.3 builds the companion for another Minecraft version
val serverMc = providers.gradleProperty("server_mc").getOrElse("26.1.2")
val (serverLoader, serverFabricApi, serverMcRange) = when (serverMc) {
    "26.3" -> Triple("0.19.5", "0.161.0+26.3", ">=26.3")
    else -> Triple("0.19.1", "0.144.3+26.1", ">=26.1.2 <26.2")
}

dependencies {
    minecraft("com.mojang:minecraft:$serverMc")
    implementation("net.fabricmc:fabric-loader:$serverLoader")
    implementation("net.fabricmc.fabric-api:fabric-api:$serverFabricApi")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

if (serverMc != "26.1.2") {
    tasks.withType<Jar>().configureEach {
        archiveVersion.set("$fullProjectVersion+mc$serverMc")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_dependency", serverMcRange)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_dependency" to serverMcRange)
    }
}
