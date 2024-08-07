/**
 * The Clear BSD License
 *
 * Copyright (c) 2024, Alex Katlein <dev@vemilyus.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the disclaimer
 * below) provided that the following conditions are met:
 *
 *      * Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *
 *      * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *
 *      * Neither the name of the copyright holder nor the names of its
 *      contributors may be used to endorse or promote products derived from this
 *      software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY
 * THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package io.v47.nuprocess.shim

import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessFactory
import com.zaxxer.nuprocess.NuProcessHandler
import java.nio.file.Path
import java.util.concurrent.ThreadFactory

internal class ShimProcessFactory : NuProcessFactory {
    private val threadFactory: ThreadFactory = Thread.ofVirtual().name("nuprocess-shim-", 0).factory()

    override fun createProcess(
        commands: MutableList<String>,
        env: MutableMap<String, String>,
        processListener: NuProcessHandler?,
        cwd: Path?
    ): NuProcess {
        val proc = ShimProcess(threadFactory, processListener)

        runCatching {
            proc.start(createProcessBuilder(commands, env, cwd))
        }.onFailure { _ ->
            proc.cleanup()
        }

        return proc
    }

    override fun runProcess(
        commands: MutableList<String>,
        env: MutableMap<String, String>,
        processListener: NuProcessHandler?,
        cwd: Path?
    ) {
        val proc = ShimProcess(threadFactory, processListener)

        runCatching {
            proc.run(createProcessBuilder(commands, env, cwd))
        }.onFailure {
            proc.cleanup()
        }.getOrThrow()
    }

    private fun createProcessBuilder(
        commands: List<String>,
        env: Map<String, String>,
        cwd: Path?
    ) =
        ProcessBuilder().apply {
            command(commands)

            environment().clear()
            environment().putAll(env)

            if (cwd != null)
                directory(cwd.toFile())
        }
}
