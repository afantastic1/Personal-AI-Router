/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceAdapterTest {
    @Test
    fun hugFaceSearchResultsKeepRepositoryAndMnnArtifactMetadata() {
        val models = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"nvidia/Qwen3-1.7B-MNN","pipeline_tag":"text-generation","tags":["mnn","qwen3"],"siblings":[{"rfilename":"config.json","size":120,"lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn","size":2048,"lfs":{"oid":"${"b".repeat(64)}"}}]}]""",
        )

        assertEquals(1, models.size)
        assertEquals("nvidia/Qwen3-1.7B-MNN", models.single().source.repository)
        assertEquals(ModelFormat.MNN, models.single().format)
        assertEquals(1_700_000_000L, models.single().parameterCount)
        assertTrue(models.single().files.any { it.path == "llm.mnn" && it.sha256 == "b".repeat(64) })
    }

    @Test
    fun hfFortyHexGitOidIsNotSha256() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"owner/model","siblings":[{"rfilename":"config.json","oid":"${"e".repeat(40)}"}]}]""",
        ).single()

        assertNull(model.files.single().sha256)
    }

    @Test
    fun hfLfsSha256IsAccepted() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"owner/model","siblings":[{"rfilename":"llm.mnn","lfs":{"oid":"sha256:${"a".repeat(64)}"}}]}]""",
        ).single()

        assertEquals("a".repeat(64), model.files.single().sha256)
    }

    @Test
    fun missingConfigHashIsNotVerifiedInstallable() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"owner/Qwen3-MNN","siblings":[{"rfilename":"config.json"},{"rfilename":"llm.mnn","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn.weight","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"tokenizer.txt","lfs":{"oid":"${"c".repeat(64)}"}}]}]""",
        ).single()

        assertEquals(ModelInstallability.MISSING_VERIFICATION_METADATA, model.installability())
    }

    @Test
    fun missingRequiredArtifactHashIsNotVerifiedInstallable() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"owner/Qwen3-MNN","siblings":[{"rfilename":"config.json","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"llm.mnn.weight"},{"rfilename":"tokenizer.txt","lfs":{"oid":"${"c".repeat(64)}"}}]}]""",
        ).single()

        assertEquals(ModelInstallability.MISSING_VERIFICATION_METADATA, model.installability())
    }

    @Test
    fun incompleteMnnArtifactSetIsNotVerifiedInstallable() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"owner/Qwen3-MNN","siblings":[{"rfilename":"config.json","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"b".repeat(64)}"}}]}]""",
        ).single()

        assertEquals(ModelInstallability.INCOMPLETE_ARTIFACT_SET, model.installability())
    }

    @Test
    fun modelScopeSearchResultsMapTheirModelPathsToCatalogDescriptors() {
        val models = ModelScopeAdapter().parseSearchResponse(
            """{"Code":200,"Data":{"Models":[{"Name":"Qwen3 0.6B MNN","Path":"NVIDIA/Qwen3-0.6B-MNN","Description":"MNN chat model","Tags":["qwen3","mnn"],"Files":[{"Name":"config.json","Size":120,"Sha256":"${"c".repeat(64)}"},{"Name":"llm.mnn","Size":2048,"Sha256":"${"d".repeat(64)}"}]}]}}""",
        )

        assertEquals(1, models.size)
        assertEquals("NVIDIA/Qwen3-0.6B-MNN", models.single().source.repository)
        assertEquals(600_000_000L, models.single().parameterCount)
        assertTrue(models.single().files.any { it.path == "llm.mnn" && it.sizeBytes == 2048L })
    }
}
