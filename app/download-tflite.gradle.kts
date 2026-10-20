import org.gradle.api.tasks.Copy
import java.net.URI

val modelVersion = "v1.2.0"
val upstreamModelFileName = "fairscan-segmentation-model.tflite"
val modelFileName = "freescan-segmentation-model.tflite"
val modelUrl = "https://github.com/pynicolas/fairscan-segmentation-model/releases/download/$modelVersion/$upstreamModelFileName"

// Keep a local copy outside build/ so Gradle clean and failed task cleanup do not remove it.
val localModelPath = layout.projectDirectory.file("model-cache/$modelFileName")
val downloadedModelPath = layout.buildDirectory.file("downloads/$modelFileName")
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets")

val downloadTFLiteModel = tasks.register("downloadTFLiteModel") {
    val localModelFile = localModelPath.asFile
    val outputFile = downloadedModelPath.get().asFile
    // A missing local override is expected in CI. Optional input properties still
    // validate any configured path, so only expose the file when it exists.
    inputs.files(providers.provider {
        if (localModelFile.isFile) listOf(localModelFile) else emptyList<File>()
    })
    outputs.file(outputFile)

    doLast {
        outputFile.parentFile.mkdirs()
        if (localModelFile.isFile) {
            println("Using local TFLite model: ${localModelFile.absolutePath}")
            localModelFile.copyTo(outputFile, overwrite = true)
        } else if (!outputFile.exists()) {
            println("Downloading $modelFileName from $modelUrl")
            URI.create(modelUrl).toURL().openStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            println("Model already downloaded: ${outputFile.absolutePath}")
        }
    }
}

val copyTFLiteToAssets = tasks.register<Copy>("copyTFLiteToAssets") {
    dependsOn(downloadTFLiteModel)
    from(downloadedModelPath)
    into(generatedAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(copyTFLiteToAssets)
}
