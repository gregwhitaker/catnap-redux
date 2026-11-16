plugins { id("catnap.jvm-conventions") }

val catalog = extensions.findByType<VersionCatalogsExtension>()?.find("libs")?.orElse(null)

if (catalog != null)
    dependencies {
        testImplementation(platform(catalog.findLibrary("junit-bom").get()))
        testImplementation(catalog.findLibrary("junit-jupiter").get())
        testRuntimeOnly(catalog.findLibrary("junit-launcher").get())
    }
