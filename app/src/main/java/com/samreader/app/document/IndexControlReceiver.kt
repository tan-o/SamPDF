package com.samreader.app.document

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samreader.app.SamReaderApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IndexControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID) ?: return
        val pendingResult = goAsync()
        val repository = (context.applicationContext as SamReaderApplication).container.documents
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> repository.pauseIndex(documentId)
                    ACTION_CANCEL -> repository.cancelIndex(documentId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.samreader.app.action.PAUSE_INDEX"
        const val ACTION_CANCEL = "com.samreader.app.action.CANCEL_INDEX"
        private const val EXTRA_DOCUMENT_ID = "document_id"

        fun pendingIntent(context: Context, documentId: String, action: String): PendingIntent {
            val requestCode = 31 * documentId.hashCode() + action.hashCode()
            val intent = Intent(context, IndexControlReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_DOCUMENT_ID, documentId)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
