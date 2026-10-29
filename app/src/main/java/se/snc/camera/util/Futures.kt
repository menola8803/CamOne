package se.snc.camera.util

import com.google.common.util.concurrent.ListenableFuture
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val DirectExecutor = Executor { it.run() }

/** Väntar på en ListenableFuture (CameraX) utan extra bibliotek. */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }, DirectExecutor)
    cont.invokeOnCancellation { cancel(false) }
}

/** Väntar på en ML Kit-Task. */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener(DirectExecutor) { cont.resume(it) }
    addOnFailureListener(DirectExecutor) { cont.resumeWithException(it) }
    addOnCanceledListener(DirectExecutor) { cont.cancel() }
}
