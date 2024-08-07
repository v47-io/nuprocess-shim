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
import io.v47.nuprocess.shim.utils.Constants
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger(ProcessHandler::class.java)!!

@Suppress("TooManyFunctions")
internal class ProcessHandler(private val threadFactory: ThreadFactory, val shim: ShimProcess) {
    private val handlerThreadMut = AtomicReference<Thread>()
    private val stdoutPullerThreadMut = AtomicReference<Thread>()
    private val stderrPullerThreadMut = AtomicReference<Thread>()

    private val processMut = AtomicReference<Process>()

    private val stdInClosedMut = AtomicBoolean(false)
    lateinit var stdInChannel: WritableByteChannel

    private val onExit = CompletableFuture<Int>()
    private val wantsWrite = AtomicBoolean(false)
    private val queuedWrites = AtomicReference<MutableList<ByteBuffer>>()

    private val poisoned = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    val hasPendingWrites
        get() = wantsWrite.get() || !queuedWrites.get().isNullOrEmpty()

    init {
        startHandlerThreadIfRequired()
    }

    private fun startHandlerThreadIfRequired() {
        handlerThreadMut.updateAndGet { existing ->
            if (existing?.isAlive == true)
                return@updateAndGet existing

            threadFactory.newThread {
                doHandle()
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "LongMethod",
        "LoopWithTooManyJumpStatements",
        "NestedBlockDepth",
        "TooGenericExceptionCaught"
    )
    private fun doHandle() {
        val stdinBuffer = ByteBuffer.allocateDirect(Constants.BUFFER_SIZE)

        var sleepInterval = 0L
        var idleTimeout = 0L

        handler@ while (!Thread.interrupted()) {
            val proc = processMut.get()
            if (proc == null) {
                if (idleTimeout == 0L) {
                    sleepInterval = 0L
                    idleTimeout = System.currentTimeMillis() + Constants.LINGER_TIME_MS
                }

                if (System.currentTimeMillis() >= idleTimeout) {
                    poisoned.set(true)
                    break@handler
                }

                if (sleepInterval > 0L) {
                    try {
                        Thread.sleep(sleepInterval)
                    } catch (_: InterruptedException) {
                        poisoned.set(true)
                        Thread.currentThread().interrupt();
                        break@handler
                    }
                }

                Thread.onSpinWait()
                sleepInterval = (sleepInterval * 2) + 1
            } else {
                sleepInterval = 0L
                idleTimeout = 0L

                var canWrite = stdInChannel.isOpen

                try {
                    val exitValue = proc.exitValue()

                    stdoutPullerThreadMut.get()?.join()
                    stderrPullerThreadMut.get()?.join()

                    shim.nuProcessHandler?.onExit(exitValue)
                    onExit.complete(exitValue)

                    shim.cleanup()
                    break
                } catch (_: IllegalThreadStateException) {
                }

                while (canWrite && wantsWrite.getAndSet(false)) {
                    stdinBuffer.clear()
                    var wantsWriteCont = false
                    var doWrite = false

                    try {
                        wantsWriteCont = shim.nuProcessHandler?.onStdinReady(stdinBuffer) ?: false
                        doWrite = true
                    } catch (x: Exception) {
                        if (logger.isWarnEnabled)
                            logger.warn("Exception caught in onStdinReady for $shim", x)
                    }

                    if (doWrite) {
                        if (logger.isDebugEnabled)
                            logger.debug("Writing {} bytes for {}", stdinBuffer.remaining(), shim)

                        var bytesWritten: Int

                        do {
                            bytesWritten = try {
                                stdInChannel.write(stdinBuffer)
                            } catch (x: IOException) {
                                if (logger.isDebugEnabled)
                                    logger.debug("Failed to write for $shim", x)

                                canWrite = false
                                -1
                            }
                        } while (stdinBuffer.hasRemaining() && bytesWritten > -1)

                        wantsWrite.compareAndSet(false, wantsWriteCont && canWrite)
                    }
                }

                val queuedData = queuedWrites.get()?.removeFirstOrNull()
                if (canWrite && queuedData != null) {
                    var bytesWritten: Int

                    do {
                        bytesWritten = stdInChannel.write(queuedData)
                    } while (queuedData.hasRemaining() && bytesWritten > -1)
                }

                if (queuedWrites.get().isNullOrEmpty() && !wantsWrite.get() && stdInClosedMut.get())
                    stdInChannel.close()
            }
        }

        handlerThreadMut.set(null)

        if (!destroyed.get() && !poisoned.get() && !onExit.isDone)
            startHandlerThreadIfRequired()
    }

    fun setProcess(process: Process) {
        if (poisoned.get()) {
            process.destroyForcibly()
            error("ProcessHandler poisoned for $shim")
        }

        stdInChannel = Channels.newChannel(process.outputStream)

        startPullerThreadIfRequired(stdoutPullerThreadMut, process.inputStream, ::pullStdOut)
        startPullerThreadIfRequired(stderrPullerThreadMut, process.errorStream, ::pullStdErr)

        shim.nuProcessHandler?.onStart(shim)

        processMut.set(process)
    }

    private fun startPullerThreadIfRequired(
        ref: AtomicReference<Thread>,
        stream: InputStream,
        pull: (AtomicReference<Thread>, InputStream) -> Unit
    ) {
        ref.updateAndGet { existing ->
            if (existing?.isAlive == true)
                return@updateAndGet existing

            threadFactory.newThread {
                pull(ref, stream)
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun pullStdOut(ref: AtomicReference<Thread>, input: InputStream) {
        val buffer = ByteBuffer.wrap(ByteArray(Constants.BUFFER_SIZE))
        var canRead = true

        puller@ while (!Thread.interrupted() && canRead) {
            val bytesRead = input.read(buffer.array(), buffer.position(), buffer.remaining())
            if (bytesRead < 0)
                canRead = false
            else
                buffer.position(buffer.position() + bytesRead)

            if (logger.isDebugEnabled)
                logger.debug("Read {} bytes (stdout) for {}", bytesRead, shim)

            buffer.flip()

            try {
                shim.nuProcessHandler?.onStdout(buffer, !canRead)
            } catch (x: Exception) {
                if (logger.isWarnEnabled)
                    logger.warn("Exception caught in onStdout for $shim", x)
            }

            buffer.compact()
        }

        ref.set(null)
        if (!destroyed.get() && canRead)
            startPullerThreadIfRequired(ref, input, ::pullStdOut)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun pullStdErr(ref: AtomicReference<Thread>, input: InputStream) {
        val buffer = ByteBuffer.wrap(ByteArray(Constants.BUFFER_SIZE))
        var canRead = true

        puller@ while (!Thread.interrupted() && canRead) {
            val bytesRead = input.read(buffer.array(), buffer.position(), buffer.remaining())
            if (bytesRead < 0)
                canRead = false
            else
                buffer.position(buffer.position() + bytesRead)

            if (logger.isDebugEnabled)
                logger.debug("Read {} bytes (stderr) for {}", bytesRead, shim)

            buffer.flip()

            try {
                shim.nuProcessHandler?.onStderr(buffer, !canRead)
            } catch (x: Exception) {
                if (logger.isWarnEnabled)
                    logger.warn("Exception caught in onStderr for $shim", x)
            }

            buffer.compact()
        }

        ref.set(null)
        if (!destroyed.get() && canRead)
            startPullerThreadIfRequired(ref, input, ::pullStdErr)
    }

    fun wantWrite() {
        wantsWrite.set(true)
    }

    fun queueWrite(buffer: ByteBuffer) {
        if (!stdInClosedMut.get()) {
            queuedWrites.updateAndGet { existing ->
                val actual = existing ?: mutableListOf()
                actual += buffer

                actual
            }
        }
    }

    fun stopWriting(force: Boolean) {
        if (force) {
            stdInClosedMut.set(true)
            if (::stdInChannel.isInitialized)
                stdInChannel.close()
        } else
            stdInClosedMut.set(true)
    }

    fun waitForExit(timeout: Long, unit: TimeUnit?): Int =
        if (unit != null)
            onExit.get(timeout, unit)
        else
            onExit.get()

    fun cleanup() {
        destroyed.set(true)
        handlerThreadMut.getAndSet(null)?.interrupt()

        stdInClosedMut.set(true)

        if (::stdInChannel.isInitialized)
            stdInChannel.close()

        queuedWrites.set(null)

        processMut.set(null)

        stdoutPullerThreadMut.getAndSet(null)?.interrupt()
        stderrPullerThreadMut.getAndSet(null)?.interrupt()
    }
}
