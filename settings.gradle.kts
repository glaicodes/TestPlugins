// settings.gradle.kts
// Add every new provider folder here.

rootProject.name = "AnizleExtension"

// Auto-include every sub-folder that has a build.gradle.kts
File(rootDir, ".").listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    ?.forEach { include(it.name) }
