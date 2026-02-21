package com.bsuir.stmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsuir.stmapp.components.NumberDropdownMenu
import com.bsuir.stmapp.ui.theme.STMAppTheme
import com.bsuir.stmapp.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            STMAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    // Показываем ошибку
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("STM Control") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Статус подключения
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isConnected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Статус:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyLarge
                    )

//                    Spacer(modifier = Modifier.height(4.dp))
//
//                    // Кнопка обновления статуса
//                    OutlinedButton(
//                        onClick = { viewModel.getStatus() },
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .height(48.dp),
//                        enabled = uiState.isConnected && !uiState.isLoading
//                    ) {
//                        if (uiState.isLoading) {
//                            CircularProgressIndicator(
//                                modifier = Modifier.size(20.dp),
//                                color = MaterialTheme.colorScheme.primary
//                            )
//                        } else {
//                            Text("Обновить статус")
//                        }
//                    }

                    // Отображение JSON ответа
                    if (uiState.rawJsonResponse.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "JSON ответ:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.rawJsonResponse,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Кнопка подключения/отключения
            Button(
                onClick = {
                    if (uiState.isConnected) {
                        viewModel.disconnectFromDevice()
                    } else {
                        viewModel.connectToDevice()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !uiState.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isConnected)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (uiState.isConnected) "Отключиться" else "Подключиться",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )

            // Управление миганием через Dropdown
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Управление миганием:",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Dropdown меню для выбора частоты
                    NumberDropdownMenu(
                        selectedValue = uiState.selectedFrequency,
                        onValueChange = { viewModel.updateSelectedFrequency(it) },
                        options = uiState.availableFrequencies,
                        label = "Выберите частоту (Гц)",
                        enabled = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Кнопка запуска мигания с выбранной частотой
                    Button(
                        onClick = { viewModel.sendSelectedFrequency() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = uiState.isConnected && !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        if (uiState.isButtonClicked) {
                            Text("✓ Мигание запущено!")
                        } else if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onTertiary
                            )
                        } else {
                            Text(
                                text = if (uiState.isConnected)
                                    "Запустить мигание ${uiState.selectedFrequency} Гц"
                                else
                                    "Сначала подключитесь к устройству"
                            )
                        }
                    }

                    // Кнопка остановки мигания
                    Button(
                        onClick = { viewModel.stopBlinking() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = uiState.isConnected && !uiState.isLoading && uiState.isBlinking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Остановить мигание")
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )

            // Кнопка управления светодиодом
            Button(
                onClick = { viewModel.toggleLed() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = uiState.isConnected && !uiState.isLoading && !uiState.isBlinking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isLedOn)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    text = if (uiState.isLedOn)
                        "Выключить светодиод"
                    else
                        "Включить светодиод",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}


