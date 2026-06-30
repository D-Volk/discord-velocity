plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "ru.dvolk"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    // log4j-core is bundled with Velocity at runtime — compileOnly only.
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    compileOnly("org.apache.logging.log4j:log4j-api:2.22.1")

    implementation("net.dv8tion:JDA:5.2.2") {
        exclude(module = "opus-java")
    }
    implementation("org.yaml:snakeyaml:2.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching(listOf("config.yml.default", "messages.yml.default")) {
        expand("version" to project.version)
    }
}

val generatePluginSources = tasks.register<Copy>("generatePluginSources") {
    from("src/main/java-templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expand("version" to project.version.toString())
    inputs.property("version", project.version.toString())
}

sourceSets {
    main {
        java.srcDir(generatePluginSources)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    val shaded = "ru.dvolk.discordvelocity.shaded"
    relocate("net.dv8tion.jda", "$shaded.jda")
    relocate("com.neovisionaries", "$shaded.neovisionaries")
    relocate("okhttp3", "$shaded.okhttp3")
    relocate("okio", "$shaded.okio")
    relocate("kotlin", "$shaded.kotlin")
    relocate("com.fasterxml.jackson", "$shaded.jackson")
    relocate("org.apache.commons.collections4", "$shaded.commons.collections4")
    relocate("org.yaml.snakeyaml", "$shaded.snakeyaml")
    mergeServiceFiles()
    minimize {
        exclude(dependency("net.dv8tion:JDA:.*"))
        exclude(dependency("org.yaml:snakeyaml:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}
