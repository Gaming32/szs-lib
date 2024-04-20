plugins {
    java
    `java-library`
    `maven-publish`
}

group = "io.github.gaming32"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    api("org.apache.commons:commons-compress:1.26.1")
    compileOnlyApi("org.jetbrains:annotations:24.1.0")
}

java {
    withSourcesJar()
}

publishing {
    repositories {
        fun maven(name: String, releases: String, snapshots: String) {
            maven {
                this.name = name
                url = uri(if (version.toString().endsWith("-SNAPSHOT")) snapshots else releases)
                credentials(PasswordCredentials::class)
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }

        maven(
            "gaming32",
            "https://maven.jemnetworks.com/releases",
            "https://maven.jemnetworks.com/snapshots"
        )
    }

    publications {
        create<MavenPublication>("maven") {
            artifactId = project.name
            groupId = project.group.toString()
            version = project.version.toString()

            from(components["java"])

            pom {
                name = project.name
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/Gaming32/j21-generators/blob/main/LICENSE"
                    }
                }
                developers {
                    developer {
                        id = "Gaming32"
                    }
                }
            }
        }
    }
}
