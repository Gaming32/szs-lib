plugins {
    java
    `java-library`
    `maven-publish`
}

group = "io.github.gaming32"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("org.apache.commons:commons-compress:1.26.1")
    compileOnlyApi("org.jetbrains:annotations:24.1.0")
}
