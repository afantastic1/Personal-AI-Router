/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.io.File

class MnnModelCatalog(
    private val modelRoot: File,
    private val modelManager: MnnModelManager = MnnModelManager(),
) {
    fun listModels(): List<MnnModelDescriptor> = modelRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .filterNot { it.name.startsWith('.') }
        .mapNotNull { directory ->
            when (val model = modelManager.resolve(directory, directory.name, directory.name)) {
                is MnnResult.Success -> model.value
                is MnnResult.Failure -> null
            }
        }
        .sortedBy(MnnModelDescriptor::modelId)
        .toList()

    fun find(modelId: String): MnnModelDescriptor? = listModels().firstOrNull { it.modelId == modelId }
}
