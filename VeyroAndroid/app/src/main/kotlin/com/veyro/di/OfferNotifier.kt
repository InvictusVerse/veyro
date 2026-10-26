package com.veyro.di

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.veyro.MainActivity
import com.veyro.VeyroApplication
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.IncomingOffer
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.service.VeyroNotifications
import com.veyro.ui.format.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The incoming-file question as a high-priority notification while the app is not visible.
 *
 * Accept and Decline go through [TransferCoordinator.respondToOffer], the same call the in-app
 * prompt makes, so whichever the user answers first decides and the other closes. The
 * notification counts down to the same deadline and disappears with the offer.
 */
public class OfferNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val coordinator: () -> TransferCoordinator,
    private val appVisible: StateFlow<Boolean>,
) {

    public companion object {
        private const val TAG = "OfferNotifier"
        public const val ACTION_ACCEPT: String = "com.veyro.action.OFFER_ACCEPT"
        public const val ACTION_DECLINE: String = "com.veyro.action.OFFER_DECLINE"
        public const val EXTRA_TRANSFER_ID: String = "transferId"
    }

    private val notifications by lazy { VeyroNotifications(context).also { it.ensureChannels() } }
    private var job: Job? = null

    /** Idempotent. */
    @Synchronized
    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(coordinator().pendingOffer, appVisible) { offer, visible -> offer.takeUnless { visible } }
                .distinctUntilChanged()
                .collect { offer ->
                    if (offer == null) notifications.cancel(VeyroNotifications.ID_OFFER) else show(offer)
                }
        }
    }

    public fun respond(transferId: String, accept: Boolean) {
        notifications.cancel(VeyroNotifications.ID_OFFER)
        scope.launch { coordinator().respondToOffer(transferId, accept) }
    }

    private fun show(offer: IncomingOffer) {
        val summary = when (offer.fileCount) {
            1 -> offer.files.firstOrNull()?.name ?: "1 file"
            else -> "${offer.fileCount} files"
        }
        notifications.show(
            VeyroNotifications.ID_OFFER,
            notifications.offer(
                peerName = offer.peerName,
                summary = summary,
                totalBytesLabel = "${formatBytes(offer.totalBytes)}. Declines automatically if you do not answer.",
                acceptIntent = action(ACTION_ACCEPT, offer.transferId, REQUEST_ACCEPT),
                declineIntent = action(ACTION_DECLINE, offer.transferId, REQUEST_DECLINE),
                contentIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                expiresAtEpochMillis = offer.expiresAtEpochMillis,
            ),
        )
        VeyroLog.i(TAG) { "[${offer.transferId}] Offer notification shown" }
    }

    private fun action(action: String, transferId: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, OfferActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TRANSFER_ID, transferId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

private const val REQUEST_ACCEPT = 20
private const val REQUEST_DECLINE = 21
private const val REQUEST_OPEN = 22

/** Receives the offer notification's Accept / Decline. Not exported: only our PendingIntents reach it. */
public class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val transferId = intent.getStringExtra(OfferNotifier.EXTRA_TRANSFER_ID) ?: return
        val accept = when (intent.action) {
            OfferNotifier.ACTION_ACCEPT -> true
            OfferNotifier.ACTION_DECLINE -> false
            else -> return
        }
        val app = context.applicationContext as? VeyroApplication ?: return
        app.container.offerNotifier.respond(transferId, accept)
    }
}
