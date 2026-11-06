package io.drivegrowth.app.vehicle

import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.VehicleObservation
import java.util.concurrent.Executor

internal interface VehicleReader : AutoCloseable {
    fun read(): VehicleObservation
}

/** 제어·결과 전달은 main, SDK 자원은 단일 worker 소유다. 막힌 작업을 취소했다고 가정하지 않는다. */
internal class VehicleInputQueue(
    private val worker: Executor,
    private val main: Executor,
    private val elapsed: () -> Long,
    private val maxAgeMs: Long,
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    class Session internal constructor(
        internal val open: (Session) -> VehicleReader,
        internal var receive: ((Result<VehicleObservation>) -> Unit)?,
    ) {
        internal var revision = 0L
        @Volatile internal var active = true
    }
    private var current: Session? = null
    private var busy = false
    // 아래 두 필드는 worker에서만 접근한다. close가 실패하면 이전 자원을 보존해 새 연결을 막는다.
    private var reader: VehicleReader? = null
    private var readerSession: Session? = null

    fun start(open: (Session) -> VehicleReader, receive: (Result<VehicleObservation>) -> Unit): Session {
        current?.active = false
        current?.receive = null
        val session = Session(open, receive)
        current = session
        if (!busy) dispatch()
        return session
    }

    fun poll(session: Session) {
        if (current === session && !busy) dispatch()
    }

    fun invalidate(session: Session) {
        session.revision++
        if (current === session) session.receive?.invoke(Result.failure(IllegalStateException("connection_changed")))
    }

    fun stop(session: Session) {
        session.active = false
        session.receive = null
        if (current !== session) return
        current = null
        if (!busy) dispatch()
    }

    private fun dispatch() {
        val target = current
        val revision = target?.revision
        val started = elapsed()
        val epoch = EpochMillis(System.currentTimeMillis())
        busy = true
        worker.execute {
            var result: Result<VehicleObservation>? = null
            var operation = "read"
            try {
                if (target != null && !target.active) return@execute
                if (readerSession !== target) {
                    operation = "close"
                    reader?.close()
                    reader = null
                    readerSession = null
                    if (target?.active == true) {
                        operation = "open"
                        reader = target.open(target)
                        readerSession = target
                    }
                }
                operation = "read"
                if (target?.active == true) {
                    result = reader?.read()?.copy(receivedElapsedMs = started, receivedAt = epoch)
                        ?.let { Result.success(it) }
                }
            } catch (error: RuntimeException) {
                result = Result.failure(error)
            } catch (error: LinkageError) {
                result = Result.failure(error)
            } finally {
                val completed = result
                main.execute {
                    busy = false
                    completed?.exceptionOrNull()?.let { reportFailure(operation, it) }
                    if (target != null && current === target && target.revision == revision &&
                        elapsed() - started in 0..maxAgeMs && completed != null) {
                        target.receive?.invoke(completed)
                    }
                    if (current !== target && !busy) dispatch()
                }
            }
        }
    }
}
