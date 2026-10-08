// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseCloudProvidersSettings } from '@/electron/service-bridge/cloud-providers'

describe('cloud provider settings bridge', () => {
    it('parses public settings without carrying extra credential fields', () => {
        const settings = parseCloudProvidersSettings({
            schema_version: 1,
            config: {
                schema_version: 1,
                providers: [
                    {
                        id: 'provider-a',
                        protocol: 'openai_chat_completions',
                        base_url: 'https://api.example.com',
                        auth_ref: 'user-vault:provider-a',
                        enabled: true,
                        api_key: 'must-not-leak',
                        models: [
                            {
                                public_id: 'cloud/provider-a/chat',
                                upstream_id: 'chat-model',
                                capabilities: ['chat', 'streaming']
                            }
                        ]
                    }
                ]
            },
            cloudEnabled: true,
            policy: 'cloud_only',
            allowPaidFallback: false,
            monthlyBudgetUSD: 25,
            perRequestMaxEstimatedCostUSD: 2
        })

        expect(settings.config.providers[0].auth_ref).toBe('user-vault:provider-a')
        expect(Object.hasOwn(settings.config.providers[0], 'api_key')).toBe(false)
    })

    it('rejects a settings response with an unknown policy', () => {
        expect(() =>
            parseCloudProvidersSettings({
                schema_version: 1,
                config: { schema_version: 1, providers: [] },
                cloudEnabled: false,
                policy: 'automatic-paid-fallback',
                allowPaidFallback: false,
                monthlyBudgetUSD: 0,
                perRequestMaxEstimatedCostUSD: 0
            })
        ).toThrow('invalid response')
    })
})
