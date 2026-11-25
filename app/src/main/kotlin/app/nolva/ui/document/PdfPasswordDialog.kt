package app.nolva.ui.document

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nolva.R
import app.nolva.data.MAX_PDF_PASSWORD_BYTES
import app.nolva.data.PaperSize

/** Why a password can't be used yet. */
internal enum class PasswordProblem { Empty, TooLong, Mismatch }

internal fun passwordProblem(password: String, confirmation: String): PasswordProblem? = when {
    password.isEmpty() -> PasswordProblem.Empty
    password.toByteArray(Charsets.UTF_8).size > MAX_PDF_PASSWORD_BYTES -> PasswordProblem.TooLong
    password != confirmation -> PasswordProblem.Mismatch
    else -> null
}

/**
 * Asks for a password (twice) and a paper size, then exports. The password
 * lives only in this dialog: it isn't saved, not even across rotation.
 */
@Composable
internal fun PdfPasswordDialog(
    onDismiss: () -> Unit,
    onExport: (PaperSize, password: String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var paperSize by rememberSaveable { mutableStateOf(PaperSize.A4) }
    // Errors show only after a first try, not while typing.
    var attempted by remember { mutableStateOf(false) }
    val problem = passwordProblem(password, confirmation)
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val passwordError = when {
        !attempted -> null
        problem == PasswordProblem.Empty -> R.string.pdf_password_empty
        problem == PasswordProblem.TooLong -> R.string.pdf_password_too_long
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.pdf_password_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.pdf_password)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(stringResource(if (visible) R.string.hide_password else R.string.show_password))
                        }
                    },
                    isError = passwordError != null,
                    supportingText = if (passwordError != null) {
                        { Text(stringResource(passwordError)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.pdf_password_confirm)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    isError = attempted && problem == PasswordProblem.Mismatch,
                    supportingText = if (attempted && problem == PasswordProblem.Mismatch) {
                        { Text(stringResource(R.string.pdf_password_mismatch)) }
                    } else {
                        null
                    },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    PaperSize.entries.forEach { size ->
                        FilterChip(
                            selected = size == paperSize,
                            onClick = { paperSize = size },
                            label = { Text(stringResource(size.labelRes())) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    if (problem == null) onExport(paperSize, password)
                },
            ) {
                Text(stringResource(R.string.export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
