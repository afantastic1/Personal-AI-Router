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

data class LocalModelFile(val name: String, val open: () -> InputStream)

class LocalImportAdapter(
    private val modelRoot: File,
    private val modelManager: MnnModelManager = MnnModelManager(),
) {
    fun importMnnModel(modelId: String, files: List<LocalModelFile>): ModelDescriptor {
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

        try {
            files.forEach { source ->
                val destination = File(staging, source.name)
                source.open().use { input -> destination.outputStream().buffered().use(input::copyTo) }
            }
            when (val result = modelManager.resolve(staging, modelId, modelId)) {
                is MnnResult.Success -> Unit
                is MnnResult.Failure -> throw IOException("Imported files are not a valid MNN model: ${result.error.message}")
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return ModelDescriptor(
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
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    companion object {
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
    }
}
