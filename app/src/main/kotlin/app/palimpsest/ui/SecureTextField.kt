package app.palimpsest.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import app.palimpsest.PalimpsestApp
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.palimpsest.R

/**
 * Platform EditText, because Compose text fields cannot set
 * IME_FLAG_NO_PERSONALIZED_LEARNING (incognito keyboard). Autofill is disabled too:
 * a password manager remembering several passwords for this app would reveal that
 * there are several vaults. Password fields get a show/hide toggle.
 */
@Composable
fun SecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    textSizeSp: Float = 18f,
    /** No underline: for fields drawn inside their own container (search, editor). */
    plain: Boolean = false,
    typeface: Typeface? = null,
    onDone: (() -> Unit)? = null,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnDone by rememberUpdatedState(onDone)
    var visible by remember { mutableStateOf(false) }
    val echo = remember { EchoGuard() }
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val hintColor = colors.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    val highlight = colors.primary.copy(alpha = 0.3f).toArgb()

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AndroidView(
            modifier = if (singleLine) Modifier.weight(1f) else Modifier.weight(1f).fillMaxHeight(),
            factory = { context ->
                SecureEditText(context).apply {
                    setSingleLine(singleLine)
                    inputType = when {
                        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        singleLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                        (if (singleLine) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE)
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    this.hint = hint
                    textSize = textSizeSp
                    typeface?.let { this.typeface = it }
                    if (!singleLine) {
                        gravity = Gravity.TOP or Gravity.START
                        setLineSpacing(0f, 1.35f)
                    }
                    if (plain || !singleLine) {
                        background = null
                        setPadding(0, paddingTop, 0, paddingBottom)
                    }
                    setText(value)
                    echo.emitted = value
                    doAfterTextChanged {
                        val text = it?.toString().orEmpty()
                        echo.emitted = text
                        currentOnChange(text)
                    }
                    setOnEditorActionListener { _, action, _ ->
                        val done = currentOnDone
                        if (action == EditorInfo.IME_ACTION_DONE && done != null) {
                            done()
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            update = { view ->
                view.setTextColor(textColor)
                view.setHintTextColor(hintColor)
                view.highlightColor = highlight
                echo.apply(view, value)
                if (password) {
                    // The keyboard stays in password mode either way; only the masking changes.
                    val method = if (visible) null else PasswordTransformationMethod.getInstance()
                    if (view.transformationMethod != method) {
                        val cursor = view.selectionEnd
                        view.transformationMethod = method
                        view.setSelection(cursor.coerceIn(0, view.text.length))
                    }
                }
            },
        )
        if (password) {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) EyeOffIcon else EyeIcon,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password),
                )
            }
        }
    }
}

/**
 * Keeps a two-way bound EditText from fighting its own echo. The state the view model hands
 * back lags the view by a frame; writing that stale value into the view would drop the
 * newest keystrokes and throw the cursor to the end. So only a value that differs from the
 * last one the view itself emitted is treated as an outside change (cleared field, ticked
 * checkbox, undo) and written in, keeping the cursor where it was.
 */
internal class EchoGuard {
    var emitted: String? = null

    fun apply(view: EditText, value: String) {
        if (value == emitted || view.text.toString() == value) return
        val cursor = view.selectionStart
        emitted = value
        view.setText(value)
        view.setSelection(if (value.isEmpty()) 0 else cursor.coerceIn(0, value.length))
    }
}

/**
 * EditText whose copy and cut go through [app.palimpsest.ClipboardGuard] (sensitive flag,
 * timed clear, optional block). "Share" and text-processing actions such as Translate or
 * web search are removed from the selection menu: they hand the selected plaintext to
 * another app.
 */
@SuppressLint("ViewConstructor")
internal open class SecureEditText(context: Context) : EditText(context) {
    private val app get() = context.applicationContext as PalimpsestApp

    init {
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val blockCopy = app.settings.values.value.blockCopy
                for (i in menu.size() - 1 downTo 0) {
                    val item = menu.getItem(i)
                    val leaks = item.itemId == android.R.id.shareText ||
                        item.intent?.action == Intent.ACTION_PROCESS_TEXT ||
                        (blockCopy && (item.itemId == android.R.id.copy || item.itemId == android.R.id.cut))
                    if (leaks) menu.removeItem(item.itemId)
                }
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id != android.R.id.copy && id != android.R.id.cut && id != android.R.id.shareText) {
            return super.onTextContextMenuItem(id)
        }
        if (id == android.R.id.shareText || app.settings.values.value.blockCopy) return true
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        if (start == end) return true
        app.clipboard.copy(text.subSequence(start, end).toString())
        if (id == android.R.id.cut) text.delete(start, end)
        return true
    }
}

// Material "visibility" / "visibility_off" glyphs (Apache 2.0), inlined to avoid pulling
// in the multi-megabyte material-icons-extended artifact for two icons.

internal val EyeIcon = glyph(
    "Visibility",
    "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
        "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
        "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
)

private val EyeOffIcon = glyph(
    "VisibilityOff",
    "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 " +
        "-1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
        "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 " +
        "4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
        "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 " +
        "0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
        "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
)
