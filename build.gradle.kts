import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.allopen") version "2.2.21"
    id("me.champeau.jmh") version "0.7.3"
    id("io.github.reyerizo.gradle.jcstress") version "0.9.0"
}

group = "lab4"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    // Pin the compile/test toolchain to JDK 21 so the build doesn't silently use the launcher JDK
    // (a JDK < 21 fails at test time with "class file version 65.0"); bytecode still targets Java 21.
    jvmToolchain(21)
    sourceSets {
        named("jmh") {
            kotlin.srcDir("src/jmh/kotlin")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // Must match jcstress Gradle plugin / annotation processor (0.16 drops FootprintEstimator used by generated runners).
    jcstressImplementation("org.openjdk.jcstress:jcstress-core:0.15")
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
    }
}

tasks.named<KotlinJvmCompile>("compileJmhKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

jmh {
    jmhVersion.set("1.37")
    warmupIterations.set(5)
    iterations.set(5)
    fork.set(2)
    resultFormat.set("JSON")
    val jmhResultsRel =
        (project.findProperty("jmh.resultsFile") as String?) ?: "results/full/jmh-results.json"
    resultsFile.set(layout.projectDirectory.file(jmhResultsRel).asFile)
    // Full scaling runs: `./gradlew jmh -Pjmh.heap=28g`
    val jmhHeap = (project.findProperty("jmh.heap") as String?) ?: "8g"
    jvmArgsAppend.set(
        listOf(
            "-Djmh.ignoreLock=true",
            "-Xmx$jmhHeap",
            "-Xms256m",
            "-XX:+UseG1GC",
        ),
    )
    // Quick smoke (no Mixed param matrix, no 10s/iter defaults from benchmark classes):
    // `./gradlew jmh -Pjmh.light=true -Pjmh.heap=1536m`
    if (project.hasProperty("jmh.light")) {
        includes.set(
            listOf(
                "ReadBenchmark.read_thr01|ReadBenchmark.read_thr08|" +
                    "WriteBenchmark.write_thr01|WriteBenchmark.write_thr08|" +
                    "ReadLatencyBenchmark.getSample|" +
                    "ReadBenchmarkUnsafe.readOwnSingle_thr01|ReadBenchmarkUnsafe.readUnsafe_thr01|" +
                    "WriteBenchmarkUnsafe.writeOwnSingle_thr01|WriteBenchmarkUnsafe.writeUnsafe_thr01",
            ),
        )
        fork.set(1)
        warmupIterations.set(1)
        iterations.set(2)
        warmup.set("1s")
        timeOnIteration.set("1s")
    } else if (project.hasProperty("jmh.report")) {
        // Shorter report-oriented run (still runs all @Param values from benchmark sources, including full scaling entries).
        // `./gradlew jmh -Pjmh.report=true -Pjmh.heap=8g`
        fork.set(1)
        warmupIterations.set(1)
        iterations.set(5)
        warmup.set("1s")
        timeOnIteration.set("2s")
    }
    // Profile / flame: `./gradlew jmh -Pjmh.profile=true -Pjmh.includes=WriteBenchmark.write_thr16 -Pjmh.impl=OWN`
    if (project.hasProperty("jmh.profile")) {
        (project.findProperty("jmh.includes") as String?)?.let { includes.set(listOf(it)) }
        (project.findProperty("jmh.impl") as String?)?.let { impl ->
            val implValues = objects.listProperty(String::class.java)
            implValues.set(listOf(impl))
            benchmarkParameters.set(mapOf("impl" to implValues))
        }
        fork.set(1)
        warmupIterations.set(1)
        iterations.set(3)
        warmup.set("5s")
        timeOnIteration.set("15s")
        (project.findProperty("jmh.flameFile") as String?)?.let { flameFile ->
            val lib = (project.findProperty("jmh.asyncProfilerLib") as String?)
                ?: "/opt/async-profiler/lib/libasyncProfiler.so"
            jvmArgsAppend.set(
                jvmArgsAppend.get() +
                    listOf(
                        "-agentpath:$lib=start,event=cpu,file=$flameFile",
                        "-XX:+UnlockDiagnosticVMOptions",
                        "-XX:+DebugNonSafepoints",
                    ),
            )
        }
    }
}

tasks.named("jmh") {
    doFirst {
        val jmhResultsRel =
            (project.findProperty("jmh.resultsFile") as String?) ?: "results/full/jmh-results.json"
        layout.projectDirectory.file(jmhResultsRel).asFile.parentFile.mkdirs()
    }
}

jcstress {
    mode = "quick"
}
