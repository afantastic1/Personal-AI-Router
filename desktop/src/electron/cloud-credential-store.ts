// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { app, safeStorage } from 'electron'
import { readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'

const STORE_NAME = 'cloud-credentials.enc'

async function readEntries(): Promise<Map<string, string>> {
    try {
        const content = await readFile(path.join(app.getPath('userData'), STORE_NAME), 'utf8')
        const entries = new Map<string, string>()
        for (const line of content.split('\n')) {
            if (!line) continue
            const separator = line.indexOf(':')
            if (separator <= 0) continue
            entries.set(decodeURIComponent(line.slice(0, separator)), line.slice(separator + 1))
        }
        return entries
    } catch (error) {
        if (error instanceof Error && 'code' in error && error.code === 'ENOENT') {
            return new Map()
        }
        throw new Error('Secure credential storage could not be read')
    }
}

async function writeEntries(entries: Map<string, string>): Promise<void> {
    const directory = app.getPath('userData')
    const target = path.join(directory, STORE_NAME)
    const temporary = `${target}.tmp`
    const content = [...entries.entries()]
        .map(([authRef, ciphertext]) => `${encodeURIComponent(authRef)}:${ciphertext}`)
        .join('\n')
    await writeFile(temporary, content, { encoding: 'utf8', mode: 0o600 })
    await rename(temporary, target)
}

export async function saveCloudCredential(authRef: string, credential: string): Promise<void> {
    if (!safeStorage.isEncryptionAvailable()) {
        throw new Error('Secure credential storage is unavailable on this device')
    }
    const entries = await readEntries()
    if (!credential) entries.delete(authRef)
    else entries.set(authRef, safeStorage.encryptString(credential).toString('base64'))
    await writeEntries(entries)
}

export async function loadCloudCredentials(): Promise<
    Array<{ authRef: string; credential: string }>
> {
    if (!safeStorage.isEncryptionAvailable()) return []
    const entries = await readEntries()
    const credentials: Array<{ authRef: string; credential: string }> = []
    for (const [authRef, ciphertext] of entries) {
        try {
            const credential = safeStorage.decryptString(Buffer.from(ciphertext, 'base64'))
            if (credential) credentials.push({ authRef, credential })
        } catch {
            // An unreadable credential is left in the vault for recovery, but is never
            // sent to the broker or included in an error/log message.
        }
    }
    return credentials
}
