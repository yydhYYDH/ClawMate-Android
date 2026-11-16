import com.google.protobuf.gradle.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf") version "0.9.5"
}

kotlin { jvmToolchain(17) }

val traceSchemas by configurations.creating { isTransitive = false }
val extractTraceSchemas by tasks.registering(Sync::class) {
    from({ zipTree(traceSchemas.singleFile) }) {
        include(
            "opentelemetry/proto/common/v1/common.proto",
            "opentelemetry/proto/resource/v1/resource.proto",
            "opentelemetry/proto/trace/v1/trace.proto",
            "opentelemetry/proto/collector/trace/v1/trace_service.proto",
        )
    }
    into(layout.buildDirectory.dir("trace-schemas"))
}

sourceSets.main { proto.srcDir(extractTraceSchemas) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().configureEach { builtins { named("java") { option("lite") } } }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    traceSchemas("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")
}

val knowledgeIndex by tasks.registering(JavaExec::class) {
    val resources = file("src/main/resources")
    val output = layout.buildDirectory.dir("generated/knowledge-index")
    inputs.dir(resources.resolve("knowledge"))
    inputs.dir(resources.resolve("skills"))
    outputs.dir(output)
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    mainClass.set("com.wangzan101.mika.core.knowledge.KnowledgeIndex")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(resources.absolutePath, output.get().file("knowledge/index.json").asFile.absolutePath)
    })
}

sourceSets.main { resources.srcDir(knowledgeIndex) }

tasks.processResources {
    from(listOf("../LICENSE", "../NOTICE", "../licenses/THIRD_PARTY_NOTICES.md")) { into("META-INF/mika") }
}
