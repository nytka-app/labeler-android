package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onDone: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onDone() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to your Nytka server", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = ui.url,
            onValueChange = viewModel::setUrl,
            label = { Text("Server address") },
            placeholder = { Text("https://nytka.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("url"),
        )
        OutlinedTextField(
            value = ui.token,
            onValueChange = viewModel::setToken,
            label = { Text(if (ui.tokenSaved) "New token (a token is saved)" else "Token (scope: labeling)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("token"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(ui.privateNetwork, viewModel::setPrivateNetwork, Modifier.testTag("private_network"))
            Text("  Allow plain http:// on a private network")
        }
        ui.error?.let { Text(it, Modifier.testTag("setup_error"), color = MaterialTheme.colorScheme.error) }
        Button(onClick = viewModel::save, Modifier.fillMaxWidth().testTag("save_setup")) { Text("Save") }
    }
}
