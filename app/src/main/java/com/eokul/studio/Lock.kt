package com.eokul.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** PIN kilit ekranı: 4–8 haneli PIN; üst üste yanlış denemelerde kısa bekleme uygulanır. */
@Composable
fun LockScreen(studio: StudioViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var waitSec by remember { mutableStateOf(studio.pinWaitSeconds()) }

    LaunchedEffect(waitSec) {
        if (waitSec > 0) {
            delay(1000)
            waitSec -= 1
        }
    }

    fun tryUnlock() {
        if (waitSec > 0 || pin.length < 4) return
        if (studio.unlock(pin)) {
            pin = ""
            error = null
        } else {
            pin = ""
            error = "PIN yanlış"
            waitSec = studio.pinWaitSeconds()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔒", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.size(8.dp))
        Text("E-Okul Foto Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Öğrenci verileri PIN ile korunuyor", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> if (v.length <= 8 && v.all { it.isDigit() }) { pin = v; error = null } },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
            enabled = waitSec == 0,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        if (waitSec > 0) Text("$waitSec sn bekleyin", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.size(12.dp))
        Button(onClick = { tryUnlock() }, enabled = pin.length >= 4 && waitSec == 0, modifier = Modifier.fillMaxWidth()) {
            Text("Kilidi aç")
        }
        Spacer(Modifier.size(16.dp))
        Text(
            "PIN’i unuttuysanız uygulama verilerini temizlemek gerekir (yedeğiniz kayıt klasöründe durur).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** PIN belirleme / değiştirme / kaldırma diyaloğu. */
@Composable
fun PinDialog(studio: StudioViewModel, hasPin: Boolean, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    @Composable
    fun field(value: String, label: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (v.length <= 8 && v.all { it.isDigit() }) { onChange(v); error = null } },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "PIN değiştir / kaldır" else "PIN belirle") },
        text = {
            Column {
                if (hasPin) field(current, "Mevcut PIN") { current = it }
                field(pin1, if (hasPin) "Yeni PIN (4–8 hane)" else "PIN (4–8 hane)") { pin1 = it }
                field(pin2, "PIN tekrar") { pin2 = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (hasPin) Text("PIN’i kaldırmak için yeni PIN alanlarını boş bırakıp “Kaldır”a basın.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (hasPin && !studio.checkPin(current)) {
                    error = "Mevcut PIN yanlış"
                } else if (pin1.length < 4) {
                    error = "PIN en az 4 haneli olmalı"
                } else if (pin1 != pin2) {
                    error = "PIN’ler aynı değil"
                } else {
                    studio.setPin(pin1)
                    onDismiss()
                }
            }) { Text("Kaydet") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (hasPin) {
                    TextButton(onClick = {
                        if (!studio.checkPin(current)) {
                            error = "Mevcut PIN yanlış"
                        } else {
                            studio.setPin(null)
                            onDismiss()
                        }
                    }) { Text("Kaldır") }
                }
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
            }
        }
    )
}
