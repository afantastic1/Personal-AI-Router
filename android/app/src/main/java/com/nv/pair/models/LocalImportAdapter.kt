/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import com.nv.pair.mnn.MnnModelManager
import com.nv.pair.mnn.MnnResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LocalModelFile(val name: String, val open: () -> InputStream)

class LocalImportAdapter(
    private val modelRoot: File,
    private val modelManager: MnnModelManager = MnnModelManager(),
) {
    suspend fun importMnnModel(
        modelId: String,
        files: List<LocalModelFile>,
        onPublished: (ModelDescriptor) -> Unit = {},
    ): ModelDescriptor {
        require(MODEL_ID.matches(modelId)) { "Model ID must be a safe local identifier." }
        require(files.isNotEmpty()) { "Select MNN model files to import." }
        files.forEach { file -> require(SAFE_FILE_NAME.matches(file.name)) { "Model file paths must be safe file names." } }
        require(files.map(LocalModelFile::name).distinct().size == files.size) { "Model import contains duplicate file names." }
        if (!modelRoot.exists() && !modelRoot.mkdirs()) throw IOException("Could not create the private model directory.")

        val target = File(modelRoot, modelId)
        require(target.canonicalFile.parentFile == modelRoot.canonicalFile) { "Model target must stay inside the private model root." }
        require(!target.exists()) { "A model with this ID is already installed." }
        val staging = File(modelRoot, ".$modelId.importing")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdir()) throw IOException("Could not create the private import staging directory.")

        var operationFailure: Throwable? = null
        try {
            val coroutineContext = currentCoroutineContext()
            files.forEach { source ->
                val destination = File(staging, source.name)
                source.open().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            when (val result = modelManager.resolve(staging, modelId, modelId)) {
                is MnnResult.Success -> Unit
                is MnnResult.Failure -> throw IOException("Imported files are not a valid MNN model: ${result.error.message}")
            }
            val imported = descriptorFor(staging, modelId).copy(
                source = ModelSource(ModelSourceKind.LOCAL, target.absolutePath),
            )
            coroutineContext.ensureActive()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            onPublished(imported)
            return imported
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            if (staging.exists() && !staging.deleteRecursively()) {
                val cleanupFailure = IOException("Could not remove the temporary model import directory.")
                if (operationFailure == null) throw cleanupFailure
                operationFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun descriptorFor(target: File, modelId: String) = ModelDescriptor(
        logicalId = "local:$modelId",
        engineModelId = modelId,
        displayName = modelId,
        family = "unknown",
        parameterCount = null,
        quantization = null,
        contextLength = null,
        source = ModelSource(ModelSourceKind.LOCAL, target.absolutePath),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = target.walkTopDown().filter(File::isFile).sumOf(File::length).takeIf { it > 0L },
        compatibility = ModelCompatibility.COMPATIBLE,
    )

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
