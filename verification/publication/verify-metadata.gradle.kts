import groovy.json.JsonSlurper
import java.io.DataInputStream
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

tasks.register("verifyPublicationMetadata") {
    doLast {
        val version = providers.gradleProperty("catnapVersion").get()
        val repository = file(providers.gradleProperty("catnapRepository").get())
        val modules =
            file("gradle/published-modules.txt").readLines().filter { it.isNotBlank() }.toSet()
        val xml =
            DocumentBuilderFactory.newInstance()
                .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder()
        fun File.one(suffix: String): File = listFiles()!!.single { it.name.endsWith(suffix) }
        fun Any?.objectMap(): Map<*, *> = this as Map<*, *>
        fun JarFile.verifyGuide(path: String, vararg required: String) {
            val entry = getJarEntry(path) ?: error("Missing $path in $name")
            val text = getInputStream(entry).bufferedReader().use { it.readText() }
            for (snippet in required) {
                check(snippet in text) { "$path in $name is missing $snippet" }
            }
        }
        val gradleBom = "com.github.gregwhitaker:catnap-bom:$version\""
        val mavenBom = "<catnap.version>$version</catnap.version>"
        for (module in modules + "catnap-bom") {
            val directory = repository.resolve("com/github/gregwhitaker/$module/$version")
            check(directory.isDirectory) { "Missing publication $module" }
            val pom = xml.parse(directory.one(".pom"))
            fun tag(name: String) = pom.getElementsByTagName(name)
            check(tag("groupId").item(0).textContent == "com.github.gregwhitaker")
            check(tag("artifactId").item(0).textContent == module)
            check(tag("version").item(0).textContent == version)
            listOf("name", "description", "url", "licenses", "developers", "scm").forEach {
                check(tag(it).length > 0 && tag(it).item(0).textContent.isNotBlank()) {
                    "$module lacks POM $it"
                }
            }
            val metadata = JsonSlurper().parse(directory.one(".module")).objectMap()
            check(metadata["formatVersion"] == "1.1")
            check(metadata["component"].objectMap()["version"] == version)
            val variants = (metadata["variants"] as List<*>).map { it.objectMap() }
            val runtime = variants.single { it["name"] == "runtimeElements" }
            if (module == "catnap-bom") {
                check(tag("packaging").item(0).textContent == "pom")
                val dependencies = tag("dependency")
                val managed =
                    (0 until dependencies.length)
                        .map { index ->
                            val dependency = dependencies.item(index) as org.w3c.dom.Element
                            check(
                                dependency.getElementsByTagName("groupId").item(0).textContent ==
                                    "com.github.gregwhitaker"
                            )
                            check(
                                dependency.getElementsByTagName("version").item(0).textContent ==
                                    version
                            )
                            dependency.getElementsByTagName("artifactId").item(0).textContent
                        }
                        .toSet()
                check(managed == modules) { "BOM POM must align exactly Catnap libraries" }
                val constraints =
                    (runtime["dependencyConstraints"] as List<*>).map { it.objectMap() }
                check(constraints.map { it["module"] }.toSet() == modules)
                constraints.forEach {
                    check(it["group"] == "com.github.gregwhitaker")
                    check(it["version"].objectMap()["requires"] == version)
                }
                check(runtime["dependencies"] == null) {
                    "Catnap BOM must not import framework platforms"
                }
                continue
            }
            val baseline = if (module == "catnap-micronaut") 25 else 17
            check(runtime["attributes"].objectMap()["org.gradle.jvm.version"] == baseline)
            for (classifier in listOf("sources", "javadoc")) {
                val variant = variants.single { it["name"] == "${classifier}Elements" }
                check(variant["attributes"].objectMap()["org.gradle.docstype"] == classifier)
                val files = (variant["files"] as List<*>).map { it.objectMap() }
                check(files.size == 1) {
                    "$module must publish exactly one $classifier artifact, without competing writers"
                }
                val artifact = directory.one("-$classifier.jar")
                // Maven stores snapshots under timestamped filenames; metadata uses logical names.
                check(files.single()["name"] == "$module-$version-$classifier.jar")
                check((files.single()["size"] as Number).toLong() == artifact.length())
                val sha256 =
                    java.util.HexFormat.of()
                        .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(artifact.readBytes())
                        )
                check(files.single()["sha256"] == sha256) {
                    "$module $classifier metadata must match the published artifact"
                }
            }
            val starter =
                module in listOf("catnap-spring-boot-starter", "catnap-spring-boot-starter-webflux")
            JarFile(directory.one("-sources.jar")).use { jar ->
                if (starter) {
                    jar.verifyGuide("README.md", gradleBom, mavenBom)
                } else {
                    check(
                        jar.entries().asSequence().any {
                            it.name.endsWith(".java") || it.name.endsWith(".kt")
                        }
                    ) {
                        "Empty sources: $module"
                    }
                }
            }
            JarFile(directory.one("-javadoc.jar")).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.toList()
                check("index.html" in names) { "Missing documentation index: $module" }
                if (starter) {
                    jar.verifyGuide("index.html", gradleBom)
                    jar.verifyGuide("SPRING_INTEGRATION.md", gradleBom)
                    jar.verifyGuide("INSTALLATION.md", gradleBom, mavenBom)
                } else {
                    check(names.count { it.endsWith(".html") } > 3) {
                        "Empty API documentation: $module"
                    }
                }
                if (module == "catnap-kotlinx-json")
                    check(names.any { it.endsWith("/-kotlinx-json/index.html") })
                if (module == "catnap-ktor")
                    check(names.any { it.endsWith("/respond-ndjson.html") })
            }
            val mainJar =
                directory.listFiles()!!.single {
                    it.extension == "jar" &&
                        !it.name.endsWith("-sources.jar") &&
                        !it.name.endsWith("-javadoc.jar")
                }
            JarFile(mainJar).use { jar ->
                jar.entries()
                    .asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry ->
                        DataInputStream(jar.getInputStream(entry)).use { input ->
                            check(input.readInt() == 0xCAFEBABE.toInt())
                            input.readUnsignedShort()
                            check(input.readUnsignedShort() <= baseline + 44) {
                                "$module bytecode exceeds Java $baseline"
                            }
                        }
                    }
                if (module == "catnap-quarkus") {
                    val properties =
                        java.util.Properties().apply {
                            jar.getInputStream(
                                    jar.getJarEntry("META-INF/quarkus-extension.properties")
                                )
                                .use { load(it) }
                        }
                    check(
                        properties.getProperty("deployment-artifact") ==
                            "com.github.gregwhitaker:catnap-quarkus-deployment:$version"
                    )
                }
                if (module == "catnap-testkit")
                    check(
                        jar.getJarEntry(
                            "com/github/gregwhitaker/catnap/conformance/v1/manifest.json"
                        ) != null
                    )
                if (module == "catnap-spring-boot-autoconfigure")
                    check(
                        jar.getJarEntry(
                            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
                        ) != null
                    )
            }
            if (module in listOf("catnap-core", "catnap-streams", "catnap-testkit")) {
                check(runtime["dependencies"] == null) {
                    "$module must have no runtime dependencies"
                }
                check(tag("dependencies").length == 0)
            }
        }
        println(
            "Verified ${modules.size} libraries and BOM: POM/Gradle metadata, Java baselines, sources, API documentation, and extension descriptors."
        )
    }
}
