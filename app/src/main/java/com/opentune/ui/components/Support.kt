package com.opentune.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ways to back the project: a star on GitHub, or money over UPI. */
object Support {
    const val REPO = "https://github.com/justshivv/Opentune"
    const val UPI_ID = "whoosthis@axl"
    private const val PAYEE = "OpenTune"
    private const val NOTE = "Support OpenTune"
    /** Suggested amounts, in rupees. */
    val AMOUNTS = listOf(49, 99, 199, 499)

    /**
     * A UPI payment link for [rupees], which any UPI app opens with the
     * payee and amount filled in. Built by hand: UPI apps want the "@" in
     * the address as it is, not percent-encoded.
     */
    fun upiLink(rupees: Double): String =
        "upi://pay?pa=$UPI_ID&pn=$PAYEE&am=${String.format(Locale.ROOT, "%.2f", rupees)}&cu=INR&tn=${NOTE.replace(" ", "%20")}"

    /** Asks which UPI app to pay with, the amount already in. */
    fun pay(context: Context, rupees: Double) {
        val view = Intent(Intent.ACTION_VIEW, upiLink(rupees).toUri())
        try {
            context.startActivity(Intent.createChooser(view, "Send with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No UPI app on this phone. You can send it to $UPI_ID from another one.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Asking for a star, twice a month ----

    /** Days of use before the first ask. */
    private const val DAYS_BEFORE = 5
    /** However the halves of the month fall, asks are at least this far apart. */
    private val MIN_GAP_MS = TimeUnit.DAYS.toMillis(7)

    private fun prefs(context: Context) = context.getSharedPreferences("support", Context.MODE_PRIVATE)

    /** Counts today as a day OpenTune was used; called when the app comes to the front. */
    fun usedToday(context: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val day = TimeUnit.MILLISECONDS.toDays(now)
        if (p.getLong("lastDay", -1) == day) return
        p.edit { putLong("lastDay", day); putInt("days", p.getInt("days", 0) + 1) }
    }

    /**
     * Which half of which month [now] falls in, counted from year 0: the
     * 1st to the 15th is one half, the 16th to the end the other. One ask
     * per half makes two a month.
     */
    internal fun halfMonth(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int {
        val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (date.year * 12 + date.monthValue - 1) * 2 + if (date.dayOfMonth <= 15) 0 else 1
    }

    /** Whether to ask for a star now. */
    fun shouldAsk(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        return shouldAsk(p.getBoolean("done", false), p.getInt("days", 0), p.getInt("askedHalf", -1), p.getLong("askedAt", 0), now)
    }

    internal fun shouldAsk(done: Boolean, days: Int, askedHalf: Int, askedAt: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
        !done && days >= DAYS_BEFORE && halfMonth(now, zone) != askedHalf && now - askedAt >= MIN_GAP_MS

    /** The prompt was answered: [never] for a star given or "Don't ask again"; otherwise it comes back in the next half of the month. */
    fun answered(context: Context, never: Boolean, now: Long = System.currentTimeMillis()) = prefs(context).edit {
        putInt("askedHalf", halfMonth(now))
        putLong("askedAt", now)
        if (never) putBoolean("done", true)
    }
}

/** A thank-you to the developer: a few suggested sums, or your own, sent from the phone's UPI app. */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("99") }
    val rupees = text.toDoubleOrNull()?.takeIf { it >= 1 && it <= 100_000 }
    FloatingDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Favorite, null) },
        title = { Text("Support OpenTune") },
        text = {
            Column {
                Text("OpenTune has no ads and nothing to sell. If it has added a little something to your days, a small thank-you helps keep it going, and every one is truly appreciated.")
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Support.AMOUNTS.forEach { a ->
                        FilterChip(selected = text == a.toString(), onClick = { text = a.toString() }, label = { Text("₹$a") })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' }.take(8) },
                    singleLine = true,
                    prefix = { Text("₹") },
                    label = { Text("Your thank-you") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Goes to ${Support.UPI_ID}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            SheetButton(
                rupees?.let { "Send ₹${if (it % 1.0 == 0.0) it.toLong().toString() else String.format(Locale.ROOT, "%.2f", it)}" } ?: "Send",
                enabled = rupees != null,
                onClick = { rupees?.let { Support.pay(context, it) }; onDismiss() },
            )
        },
        dismissButton = { SheetButton("Maybe later", onClick = onDismiss, closes = true) },
    )
}

/**
 * Twice a month, after some days of use: a small card that slides up above
 * the dock asking for a star on GitHub. Starring or "Don't ask again" ends
 * it; closing it waits for the next half of the month.
 */
@Composable
fun StarCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)
            .glass(shape)
            .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(Icons.Rounded.Star, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
                Text("Enjoying OpenTune?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A star on GitHub helps other people find it. It's free and takes a second.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Not now: back in the next half of the month.
            androidx.compose.material3.IconButton(
                onClick = { Support.answered(context, never = false); onDismiss() },
                modifier = Modifier.align(androidx.compose.ui.Alignment.Top).size(36.dp),
            ) {
                Icon(Icons.Rounded.Close, "Not now", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = { Support.answered(context, never = true); onDismiss() }) {
                Text("Don't ask again", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.compose.material3.Button(onClick = {
                Support.answered(context, never = true)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Support.REPO.toUri())) }
                onDismiss()
            }) {
                Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
                Text("Star on GitHub", Modifier.padding(start = 6.dp), maxLines = 1)
            }
        }
    }
}
