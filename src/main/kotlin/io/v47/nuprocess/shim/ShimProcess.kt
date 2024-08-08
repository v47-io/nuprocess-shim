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
import com.zaxxer.nuprocess.NuProcessHandler
import io.v47.nuprocess.shim.internal.ProcessHandler
import java.nio.ByteBuffer
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
internal class ShimProcess(
    threadFactory: ThreadFactory,
    var nuProcessHandler: NuProcessHandler?
) : NuProcess {
    private val process = AtomicReference<Process>()
    private val processHandler = ProcessHandler(threadFactory, this)

    private var exitValue = Int.MAX_VALUE

    fun start(jProcessBuilder: ProcessBuilder): Boolean {
        return startProcessInternal(jProcessBuilder) != null
    }

    fun run(jProcessBuilder: ProcessBuilder) {
        startProcessInternal(jProcessBuilder)
        processHandler.waitForExit(0, TimeUnit.SECONDS)
    }

    private fun startProcessInternal(jProcessBuilder: ProcessBuilder): Process? {
        nuProcessHandler?.onPreStart(this)

        val proc = try {
            jProcessBuilder.start()
        } catch (_: Exception) {
            nuProcessHandler?.onExit(Int.MIN_VALUE)
            exitValue = Int.MIN_VALUE
            cleanup()
            return null
        }

        process.set(proc)
        processHandler.setProcess(proc)

        return proc
    }

    override fun waitFor(timeout: Long, timeUnit: TimeUnit) =
        processHandler.waitForExit(timeout, timeUnit)

    override fun wantWrite() {
        processHandler.wantWrite()
    }

    override fun writeStdin(buffer: ByteBuffer) {
        processHandler.queueWrite(buffer)
    }

    override fun closeStdin(force: Boolean) {
        processHandler.stopWriting(force)
    }

    override fun hasPendingWrites() =
        processHandler.hasPendingWrites

    override fun destroy(force: Boolean) {
        if (force) {
            processHandler.cleanup()
            process.get()?.destroyForcibly()

            nuProcessHandler?.onExit(Int.MAX_VALUE)
        } else
            process.get()?.destroy()
    }

    override fun isRunning() =
        process.get()?.isAlive == true

    override fun setProcessHandler(processHandler: NuProcessHandler?) {
        this.nuProcessHandler = processHandler
    }

    override fun getPID() =
        process.get()?.pid()?.toInt() ?: exitValue

    fun cleanup() {
        processHandler.cleanup()
        process.set(null)
    }
}
