package com.opentune.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
            context.startActivity(Intent.createChooser(view, "Pay with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No UPI app found. Pay $UPI_ID from any UPI app.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Asking for a star, now and then ----

    /** Days of use before the first ask, and between asks after "Later". */
    private const val DAYS_BEFORE = 5
    private val LATER_MS = TimeUnit.DAYS.toMillis(21)
    private const val MAX_ASKS = 3

    private fun prefs(context: Context) = context.getSharedPreferences("support", Context.MODE_PRIVATE)

    /** Counts today as a day OpenTune was used; called when the app comes to the front. */
    fun usedToday(context: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val day = TimeUnit.MILLISECONDS.toDays(now)
        if (p.getLong("lastDay", -1) == day) return
        p.edit { putLong("lastDay", day); putInt("days", p.getInt("days", 0) + 1) }
    }

    /** Whether to ask for a star now. */
    fun shouldAsk(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        return shouldAsk(p.getBoolean("done", false), p.getInt("days", 0), p.getInt("asked", 0), p.getLong("nextAt", 0), now)
    }

    internal fun shouldAsk(done: Boolean, days: Int, asked: Int, nextAt: Long, now: Long): Boolean =
        !done && asked < MAX_ASKS && days >= DAYS_BEFORE && now >= nextAt

    /** The prompt was answered: [never] for a star given or "Don't ask again"; otherwise later. */
    fun answered(context: Context, never: Boolean, now: Long = System.currentTimeMillis()) = prefs(context).edit {
        val p = prefs(context)
        putInt("asked", p.getInt("asked", 0) + 1)
        putLong("nextAt", now + LATER_MS)
        if (never) putBoolean("done", true)
    }
}

/** An amount to give, then the phone's UPI apps to pay with. */
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
                Text("OpenTune is free and has no ads. If it's worth something to you, pick an amount and pay with any UPI app.")
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
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "UPI ID: ${Support.UPI_ID}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            SheetButton("Pay", enabled = rupees != null, onClick = { rupees?.let { Support.pay(context, it) }; onDismiss() })
        },
        dismissButton = { SheetButton("Not now", onClick = onDismiss, closes = true) },
    )
}

/** Now and then, after some days of use: would you star the project on GitHub? */
@Composable
fun StarPrompt(onDismiss: () -> Unit, onSupport: () -> Unit) {
    val context = LocalContext.current
    FloatingDialog(
        onDismissRequest = { Support.answered(context, never = false); onDismiss() },
        icon = { Icon(Icons.Rounded.Star, null) },
        title = { Text("Enjoying OpenTune?") },
        text = {
            Column {
                Text("A star on GitHub helps other people find it. It's free and takes a second.")
                Text(
                    "Don't ask again",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp).clickable { Support.answered(context, never = true); onDismiss() },
                )
                Text(
                    "Or support it with UPI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp).clickable { Support.answered(context, never = false); onSupport() },
                )
            }
        },
        confirmButton = {
            SheetButton("Star on GitHub", onClick = {
                Support.answered(context, never = true)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Support.REPO.toUri())) }
                onDismiss()
            })
        },
        dismissButton = { SheetButton("Later", onClick = { Support.answered(context, never = false); onDismiss() }, closes = true) },
    )
}
