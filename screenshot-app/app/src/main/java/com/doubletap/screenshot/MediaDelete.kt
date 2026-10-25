package com.doubletap.screenshot

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Deleting a screenshot the app did not create.
 *
 * Since Android 10 a ContentResolver.delete() on another app's MediaStore row
 * does not delete anything: API 29 throws RecoverableSecurityException, API 30+
 * throws SecurityException. The platform wants the user to confirm, and hands
 * back an IntentSender that shows that confirmation.
 *
 * The gallery used to call delete() inside a bare `catch (_: Exception) {}`, so
 * on every modern phone Delete threw, the throw was swallowed, and the button
 * looked dead. Nothing was ever deleted and nothing was ever reported.
 */
object MediaDelete {

    const val REQUEST_DELETE = 3101

    sealed class Result {
        /** Already gone — refresh the list. */
        object Deleted : Result()
        /** The system is asking the user; the answer lands in onActivityResult. */
        object Confirming : Result()
        data class Failed(val error: Exception) : Result()
    }

    /** True when the platform shows its own confirmation, so we should not. */
    fun systemConfirms(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun delete(activity: Activity, uris: List<Uri>): Result {
        if (uris.isEmpty()) return Result.Deleted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val request = MediaStore.createDeleteRequest(activity.contentResolver, uris)
                activity.startIntentSenderForResult(
                    request.intentSender, REQUEST_DELETE, null, 0, 0, 0
                )
                Result.Confirming
            } catch (e: Exception) {
                ScreenshotService.instance?.recordError("delete request", e)
                Result.Failed(e)
            }
        }

        // API 28 shares the storage permission group, so a granted read also
        // grants write and the plain delete goes through. API 29 hands back a
        // recoverable exception carrying the confirmation intent.
        return try {
            uris.forEach { activity.contentResolver.delete(it, null, null) }
            Result.Deleted
        } catch (e: Exception) {
            recover(activity, e)
        }
    }

    /**
     * API 29 only: the throw carries the confirmation intent with it. The
     * version gate keeps the class from being resolved at all on API 28, where
     * it does not exist.
     */
    private fun recover(activity: Activity, e: Exception): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val recoverable = e as? android.app.RecoverableSecurityException
            if (recoverable != null) {
                return try {
                    activity.startIntentSenderForResult(
                        recoverable.userAction.actionIntent.intentSender,
                        REQUEST_DELETE, null, 0, 0, 0
                    )
                    Result.Confirming
                } catch (inner: Exception) {
                    ScreenshotService.instance?.recordError("delete confirm", inner)
                    Result.Failed(inner)
                }
            }
        }
        ScreenshotService.instance?.recordError("delete", e)
        return Result.Failed(e)
    }
}
