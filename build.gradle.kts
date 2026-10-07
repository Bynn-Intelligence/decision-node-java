plugins {
    `java-library`
    `maven-publish`
    // Only the tests are written in Kotlin: they prove the SDK reads well from Kotlin.
    // The library itself is plain Java with no Kotlin dependency.
    kotlin("jvm") version "2.2.20"
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "DecisionNode Java SDK",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.bynn.decisionnode",
        )
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("conformance")
    }
    systemProperty("decisionnode.version", project.version.toString())
}

// The language-neutral conformance suite: start its mock server and run every case.
// Point DECISIONNODE_CONFORMANCE_DIR at a checkout of the suite.
val conformance by tasks.registering(Test::class) {
    description = "Runs the DecisionNode conformance suite against its mock server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("conformance")
    }
    providers.environmentVariable("DECISIONNODE_CONFORMANCE_DIR").orNull?.let {
        systemProperty("decisionnode.conformance.dir", it)
    }
    providers.environmentVariable("PYTHON").orNull?.let {
        systemProperty("decisionnode.conformance.python", it)
    }
    outputs.upToDateWhen { false }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "decisionnode"
            from(components["java"])
            pom {
                name.set("DecisionNode")
                description.set("Java and Kotlin client for the DecisionNode API.")
                url.set("https://github.com/Bynn-Intelligence/decision-node-java")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                organization {
                    name.set("Bynn Intelligence, Inc.")
                }
                scm {
                    url.set("https://github.com/Bynn-Intelligence/decision-node-java")
                }
            }
        }
    }
}
