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

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.144.3+26.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
