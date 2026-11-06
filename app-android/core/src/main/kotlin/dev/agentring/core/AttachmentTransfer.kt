package dev.agentring.core

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call

/** A transfer can be cancelled from the UI even while blocked in network I/O. */
class AttachmentTransfer {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>()
    fun cancel() { cancelled.set(true); call.get()?.cancel() }
    fun check() { if (cancelled.get()) throw InterruptedIOException("Transfer cancelled") }
    internal fun attach(value: Call) { call.set(value); if (cancelled.get()) value.cancel(); check() }
    internal fun detach(value: Call) { call.compareAndSet(value, null) }
    companion object { const val MAX_BYTES = 100L * 1024 * 1024 }
}
