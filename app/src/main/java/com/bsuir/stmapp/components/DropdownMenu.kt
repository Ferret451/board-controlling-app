package com.bsuir.stmapp.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun NumberDropdownMenu(
    modifier: Modifier = Modifier,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    options: List<Int> = listOf(1, 3, 5),
    label: String = "Выберите значение",
    enabled: Boolean = true  // Добавлено поле enabled со значением по умолчанию
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = {
                if (enabled) {  // Проверяем enabled перед открытием
                    expanded = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled  // Передаем enabled в Button
        ) {
            Text(text = "$label: $selectedValue")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        if (enabled) {  // Дополнительная проверка enabled
                            onValueChange(option)
                            expanded = false
                        }
                    },
                    enabled = enabled  // Передаем enabled в DropdownMenuItem
                )
            }
        }
    }
}