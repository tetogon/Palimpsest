package app.palimpsest.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.palimpsest.R
import com.nulabinc.zxcvbn.Zxcvbn

/**
 * zxcvbn score plus the time an offline attacker needs on a stolen copy of the file.
 * Argon2id at 64 MiB / t=3 is memory-bound: assume a well-funded attacker with ~100 GPUs
 * at ~100 guesses/s each, and that the password is found halfway through the search.
 */
object PasswordStrength {
    const val GUESSES_PER_SECOND = 1e4

    private val zxcvbn by lazy { Zxcvbn() }

    class Estimate(val score: Int, val seconds: Double)

    fun estimate(password: String): Estimate {
        val strength = zxcvbn.measure(password)
        val estimate = Estimate(strength.score, strength.guesses / 2 / GUESSES_PER_SECOND)
        strength.wipe()
        return estimate
    }
}

private val LABELS = intArrayOf(
    R.string.strength_0, R.string.strength_1, R.string.strength_2, R.string.strength_3, R.string.strength_4,
)

@Composable
fun PasswordStrengthMeter(password: String, modifier: Modifier = Modifier) {
    if (password.isEmpty()) return
    val estimate = remember(password) { PasswordStrength.estimate(password) }
    val color = when (estimate.score) {
        0, 1 -> MaterialTheme.colorScheme.error
        2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { (estimate.score + 1) / 5f },
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        color = color,
    )
    Text(
        stringResource(LABELS[estimate.score.coerceIn(0, 4)]) + " · " +
            stringResource(R.string.crack_time, duration(estimate.seconds)),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun duration(seconds: Double): String {
    val minute = 60.0
    val hour = 60 * minute
    val day = 24 * hour
    val year = 365.25 * day
    return when {
        seconds < minute -> stringResource(R.string.time_instant)
        seconds < hour -> stringResource(R.string.time_minutes, (seconds / minute).toInt())
        seconds < day -> stringResource(R.string.time_hours, (seconds / hour).toInt())
        seconds < year -> stringResource(R.string.time_days, (seconds / day).toInt())
        seconds < 100 * year -> (seconds / year).toInt().let { pluralStringResource(R.plurals.time_years, it, it) }
        else -> stringResource(R.string.time_centuries)
    }
}
