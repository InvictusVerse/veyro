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
import com.veyro.ui.components.defaultAlwaysAccept
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
 *
 * ADR-038: like the in-app prompt, where "Always accept from this PC" is ticked by default, the
 * main action is "Always accept" for a paired sender, with "Just this once" next to it.
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
        public const val ACTION_ACCEPT_ONCE: String = "com.veyro.action.OFFER_ACCEPT_ONCE"
        public const val ACTION_DECLINE: String = "com.veyro.action.OFFER_DECLINE"
        public const val EXTRA_TRANSFER_ID: String = "transferId"
        public const val EXTRA_ALWAYS: String = "always"
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

    public fun respond(transferId: String, accept: Boolean, always: Boolean) {
        notifications.cancel(VeyroNotifications.ID_OFFER)
        scope.launch { coordinator().respondToOffer(transferId, accept, alwaysAccept = accept && always) }
    }

    private fun show(offer: IncomingOffer) {
        val summary = when (offer.fileCount) {
            1 -> offer.files.firstOrNull()?.name ?: "1 file"
            else -> "${offer.fileCount} files"
        }
        val always = defaultAlwaysAccept(offer)
        val detail = buildString {
            append(formatBytes(offer.totalBytes)).append(". ")
            if (always) append("Always accept also saves future files from ${offer.peerName} without asking. ")
            append("Declines automatically if you do not answer.")
        }
        notifications.show(
            VeyroNotifications.ID_OFFER,
            notifications.offer(
                peerName = offer.peerName,
                summary = summary,
                totalBytesLabel = detail,
                acceptIntent = action(ACTION_ACCEPT, offer.transferId, REQUEST_ACCEPT, always = always),
                declineIntent = action(ACTION_DECLINE, offer.transferId, REQUEST_DECLINE, always = false),
                contentIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                expiresAtEpochMillis = offer.expiresAtEpochMillis,
                acceptOnceIntent = if (always) {
                    action(ACTION_ACCEPT_ONCE, offer.transferId, REQUEST_ACCEPT_ONCE, always = false)
                } else {
                    null
                },
            ),
        )
        VeyroLog.i(TAG) { "[${offer.transferId}] Offer notification shown" }
    }

    private fun action(action: String, transferId: String, requestCode: Int, always: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, OfferActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TRANSFER_ID, transferId)
                .putExtra(EXTRA_ALWAYS, always),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

private const val REQUEST_ACCEPT = 20
private const val REQUEST_DECLINE = 21
private const val REQUEST_OPEN = 22
private const val REQUEST_ACCEPT_ONCE = 23

/** Receives the offer notification's actions. Not exported: only our PendingIntents reach it. */
public class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val transferId = intent.getStringExtra(OfferNotifier.EXTRA_TRANSFER_ID) ?: return
        val accept = when (intent.action) {
            OfferNotifier.ACTION_ACCEPT, OfferNotifier.ACTION_ACCEPT_ONCE -> true
            OfferNotifier.ACTION_DECLINE -> false
            else -> return
        }
        val always = intent.action == OfferNotifier.ACTION_ACCEPT && intent.getBooleanExtra(OfferNotifier.EXTRA_ALWAYS, false)
        val app = context.applicationContext as? VeyroApplication ?: return
        app.container.offerNotifier.respond(transferId, accept, always)
    }
}
