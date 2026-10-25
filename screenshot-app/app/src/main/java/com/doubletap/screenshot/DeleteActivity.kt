package com.doubletap.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Deletes one screenshot on behalf of the Quick Share popup.
 *
 * The popup lives in the accessibility service, and the system's delete
 * confirmation is delivered through startIntentSenderForResult, which only an
 * Activity has. This one draws nothing: it exists so the confirmation can
 * appear over whatever the user is looking at, then it closes.
 */
class DeleteActivity : Activity() {

    companion object {
        private const val EXTRA_URI = "delete_uri"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, DeleteActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra(EXTRA_URI)
        if (raw == null) {
            finish()
            return
        }

        when (MediaDelete.delete(this, listOf(Uri.parse(raw)))) {
            is MediaDelete.Result.Deleted -> {
                Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
                finish()
            }
            is MediaDelete.Result.Confirming -> Unit
            is MediaDelete.Result.Failed -> {
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // The system delete confirmation reports back here.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MediaDelete.REQUEST_DELETE && resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
