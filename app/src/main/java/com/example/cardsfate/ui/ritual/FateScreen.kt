package com.example.cardsfate.ui.ritual

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/** All spatial attachment regions share the process-owned ritual and consent lifecycle. */
@Composable
fun FateScreen(id: String, onRetrySpace: () -> Unit) {
    val vm: FateViewModel = viewModel(
        viewModelStoreOwner = FateViewModelFactory,
        factory = FateViewModelFactory.providerFactory(LocalContext.current),
    )
    FatePanel(id, vm, onRetrySpace)
}
