package ch.leico.declic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** Notification avec champ de saisie : on tape le code sans quitter la fenêtre d'association. */
object PairNotif {
    private const val CH = "pair"
    private const val ID = 7
    const val KEY = "code"

    fun show(ctx: Context, text: String, withInput: Boolean = true) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Association", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Association de Déclic")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
        if (withInput) {
            val ri = RemoteInput.Builder(KEY).setLabel("Code à 6 chiffres").build()
            val pi = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, PairReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_fg), "Entrer le code", pi)
                    .addRemoteInput(ri).build()
            )
        } else {
            b.setTimeoutAfter(15_000)
        }
        nm.notify(ID, b.build())
    }
}

/** Notification simple (ex. moteur impossible à démarrer). */
fun PairNotif.info(ctx: Context, title: String, text: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("etat", "État de Déclic", NotificationManager.IMPORTANCE_DEFAULT))
    val pi = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    nm.notify(8, Notification.Builder(ctx, "etat").setSmallIcon(R.drawable.ic_fg).setContentTitle(title)
        .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build())
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairNotif.KEY)?.toString() ?: return
        Core.init(context)
        PairNotif.show(context, "Association en cours…", withInput = false)
        Core.pair(code)
    }
}
