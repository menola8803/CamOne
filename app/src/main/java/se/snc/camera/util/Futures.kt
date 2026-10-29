package se.snc.camera.util

import com.google.common.util.concurrent.ListenableFuture
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val DirectExecutor = Executor { it.run() }

/**
 * Väntar på en ListenableFuture (CameraX) utan extra bibliotek.
 *
 * Viktigt: om coroutinen avbryts avbryts INTE själva future:n. CameraX återanvänder samma
 * future (t.ex. ProcessCameraProvider.getInstance), så att avbryta den skulle göra att
 * kameran aldrig kan starta igen under appens livstid.
 */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        if (!cont.isActive) return@addListener
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }, DirectExecutor)
}

/** Väntar på en ML Kit-Task. */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener(DirectExecutor) { cont.resume(it) }
    addOnFailureListener(DirectExecutor) { cont.resumeWithException(it) }
    addOnCanceledListener(DirectExecutor) { cont.cancel() }
}
