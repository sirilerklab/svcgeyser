plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.sirilerklab"
version = project.findProperty("releaseVersion") as String? ?: "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public/")
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.13")
    compileOnly("org.geysermc.floodgate:api:core-repackage-2.2.5-SNAPSHOT")
    implementation("org.java-websocket:Java-WebSocket:1.5.7") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    // Self-signed certificate generation for the WSS (TLS) bridge endpoint.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    relocate("org.java_websocket", "com.sirilerklab.svcgeyser.libs.ws")
    relocate("org.bouncycastle", "com.sirilerklab.svcgeyser.libs.bc")
    // BouncyCastle jars are signed; their signature files break the merged fat jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/*/OSGI-INF/**")
    archiveClassifier = ""
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}
