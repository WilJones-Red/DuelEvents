plugins {
    java
}

group = "dev.duelevent"
version = "1.2.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApi")}")
    testImplementation("io.papermc.paper:paper-api:${property("paperApi")}")
    testImplementation("org.mockito:mockito-core:5.20.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveBaseName.set("DuelEvent")
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test { failOnNoDiscoveredTests = false }
val integrationTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.duelevent.DuelIntegrationTest")
}
tasks.check { dependsOn(integrationTest) }
