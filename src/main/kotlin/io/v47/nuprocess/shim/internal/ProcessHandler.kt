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
package io.v47.nuprocess.shim.internal

import io.v47.nuprocess.shim.ShimProcess
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val BUFFER_SIZE = 65535

private val logger = LoggerFactory.getLogger(ProcessHandler::class.java)!!

@Suppress("TooManyFunctions")
internal class ProcessHandler(private val threadFactory: ThreadFactory, private val shim: ShimProcess) {
    private val handlerThreadMut = AtomicReference<Thread>()
    private val stdoutPullerThreadMut = AtomicReference<Thread>()
    private val stderrPullerThreadMut = AtomicReference<Thread>()

    private val processMut = AtomicReference<Process>()

    private val stdInClosedMut = AtomicBoolean(false)
    lateinit var stdInChannel: WritableByteChannel

    private val onExit = CompletableFuture<Int>()
    private val wantsWrite = AtomicBoolean(false)
    private val queuedWrites = ConcurrentLinkedDeque<ByteBuffer>()

    val hasPendingWrites
        get() = wantsWrite.get() || queuedWrites.isNotEmpty()

    private fun startHandlerThread() {
        handlerThreadMut.updateAndGet { existing ->
            check(existing?.isAlive != true) { "startHandlerThread called repeatedly" }

            threadFactory.newThread {
                doHandle()
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun doHandle() {
        val stdinBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)

        handler@ while (!Thread.interrupted()) {
            val proc = processMut.get()

            try {
                tryHandleExit(proc)
                break
            } catch (_: IllegalThreadStateException) {
            }

            while (wantsWrite.getAndSet(false)) {
                writeHandlerProvidedData(stdinBuffer)
            }

            val queuedData = queuedWrites.poll()
            if (queuedData != null) {
                var bytesWritten: Int

                do {
                    bytesWritten = stdInChannel.write(queuedData)
                } while (queuedData.hasRemaining() && bytesWritten > -1)
            }

            if (queuedWrites.isEmpty() && !wantsWrite.get() && stdInClosedMut.get())
                stdInChannel.close()
        }
    }

    @Suppress("NestedBlockDepth")
    private fun writeHandlerProvidedData(buffer: ByteBuffer) {
        buffer.clear()

        var wantsWriteCont = false
        var doWrite = false

        runCatching {
            wantsWriteCont = shim.nuProcessHandler?.onStdinReady(buffer) ?: false
            doWrite = true
        }.onFailure { x ->
            if (logger.isWarnEnabled)
                logger.warn("Exception caught in onStdinReady for $shim", x)
        }

        if (doWrite) {
            var bytesWritten: Int

            do {
                bytesWritten = stdInChannel.write(buffer)
            } while (buffer.hasRemaining() && bytesWritten > -1)

            wantsWrite.compareAndSet(false, wantsWriteCont)
        }
    }

    private fun tryHandleExit(proc: Process) {
        val exitValue = proc.exitValue()

        stdoutPullerThreadMut.get()?.join()
        stderrPullerThreadMut.get()?.join()

        shim.nuProcessHandler?.onExit(exitValue)
        onExit.complete(exitValue)

        shim.cleanup()
    }

    fun setProcess(process: Process) {
        processMut.set(process)

        stdInChannel = Channels.newChannel(process.outputStream)

        startPullerThread(stdoutPullerThreadMut, process.inputStream, ::pullStdOut)
        startPullerThread(stderrPullerThreadMut, process.errorStream, ::pullStdErr)

        shim.nuProcessHandler?.onStart(shim)

        startHandlerThread()
    }

    private fun startPullerThread(
        ref: AtomicReference<Thread>,
        stream: InputStream,
        pull: (InputStream) -> Unit
    ) {
        ref.updateAndGet { existing ->
            check(existing?.isAlive != true) { "startPullerThread ($stream) called repeatedly" }

            threadFactory.newThread {
                pull(stream)
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun pullStdOut(input: InputStream) {
        val buffer = ByteBuffer.wrap(ByteArray(BUFFER_SIZE))
        var canRead = true

        puller@ while (!Thread.interrupted() && canRead) {
            val bytesRead = input.read(buffer.array(), buffer.position(), buffer.remaining())
            if (bytesRead < 0)
                canRead = false
            else
                buffer.position(buffer.position() + bytesRead)

            buffer.flip()

            runCatching {
                shim.nuProcessHandler?.onStdout(buffer, !canRead)
            }.onFailure { x ->
                if (logger.isWarnEnabled)
                    logger.warn("Exception caught in onStdout for $shim", x)
            }

            buffer.compact()
        }
    }

    private fun pullStdErr(input: InputStream) {
        val buffer = ByteBuffer.wrap(ByteArray(BUFFER_SIZE))
        var canRead = true

        puller@ while (!Thread.interrupted() && canRead) {
            val bytesRead = input.read(buffer.array(), buffer.position(), buffer.remaining())
            if (bytesRead < 0)
                canRead = false
            else
                buffer.position(buffer.position() + bytesRead)

            buffer.flip()

            runCatching {
                shim.nuProcessHandler?.onStderr(buffer, !canRead)
            }.onFailure { x ->
                if (logger.isWarnEnabled)
                    logger.warn("Exception caught in onStderr for $shim", x)
            }

            buffer.compact()
        }
    }

    fun wantWrite() {
        wantsWrite.set(true)
    }

    fun queueWrite(buffer: ByteBuffer) {
        queuedWrites.addLast(buffer)
    }

    fun stopWriting(force: Boolean) {
        if (force) {
            stdInClosedMut.set(true)
            if (::stdInChannel.isInitialized)
                stdInChannel.close()
        } else
            stdInClosedMut.set(true)
    }

    fun waitForExit(timeout: Long, unit: TimeUnit): Int =
        if (timeout > 0)
            onExit.get(timeout, unit)
        else
            onExit.get()

    fun cleanup() {
        handlerThreadMut.getAndSet(null)?.interrupt()

        stdInClosedMut.set(true)

        if (::stdInChannel.isInitialized)
            stdInChannel.close()

        queuedWrites.clear()

        processMut.set(null)

        stdoutPullerThreadMut.getAndSet(null)?.interrupt()
        stderrPullerThreadMut.getAndSet(null)?.interrupt()
    }
}
