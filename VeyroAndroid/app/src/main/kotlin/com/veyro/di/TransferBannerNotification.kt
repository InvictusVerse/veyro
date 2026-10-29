package com.veyro.di

import android.app.Notification
import android.app.PendingIntent
import com.veyro.core.model.ActiveTransfer
import com.veyro.core.service.VeyroNotifications
import com.veyro.ui.components.bannerDetail
import com.veyro.ui.components.bannerFileLine
import com.veyro.ui.components.bannerIndeterminate
import com.veyro.ui.components.bannerTitle

/**
 * The in-app transfer banner as a notification (ADR-051), for sending and receiving, in the
 * banner's own words:
 *  - header, next to "Veyro": "Receiving from Office PC" / "Sending to Office PC";
 *  - title: the file, or "5 files";
 *  - text: "1.2 GB of 3.9 GB, 35 MB/s, 1 min left, file 2 of 5";
 *  - the bar with its percentage, moving without one while connecting or checking;
 *  - Cancel.
 */
internal fun VeyroNotifications.transferBanner(
    transfer: ActiveTransfer,
    cancelIntent: PendingIntent?,
    contentIntent: PendingIntent?,
): Notification {
    val percent = if (transfer.totalBytes > 0) {
        (transfer.bytesTransferred * 100 / transfer.totalBytes).toInt().coerceIn(0, 100)
    } else {
        null
    }
    val measurable = !bannerIndeterminate(transfer) && percent != null
    return progress(
        title = bannerFileLine(transfer),
        text = bannerDetail(transfer),
        progressPercent = percent.takeIf { measurable },
        cancelIntent = cancelIntent,
        contentIntent = contentIntent,
        subText = bannerTitle(transfer),
        indeterminate = !measurable,
    )
}
