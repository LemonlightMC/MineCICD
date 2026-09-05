plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow") version libs.versions.shadow
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    mavenCentral()
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.eclipse.jgit)
    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

group = "com.lemonlightmc"
version = "3.0.0"
description = "A CI/CD plugin for Minecraft Servers and Networks"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

sourceSets {
    main {
        resources {
            srcDir("src/main/java/resources")
        }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("thin")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["shadow"])
    }
}

tasks.register("printVersion") {
    doLast {
        println(version)
    }
}