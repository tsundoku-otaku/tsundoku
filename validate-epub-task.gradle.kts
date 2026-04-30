// Add this to build.gradle.kts in the root or source-local module
// For now, we'll create it in a new file for the source-local module

tasks.register<JavaExec>("validateEpubChapters") {
    group = "verification"
    description = "Validate EPUB chapter extraction with real EPUB files"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ValidateEpubChaptersKt")
    
    workingDir = rootProject.projectDir
}
