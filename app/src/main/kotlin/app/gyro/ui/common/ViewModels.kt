package app.gyro.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyro.AppContainer
import app.gyro.GyroApp

/** Creates a ViewModel wired to the app container (manual DI). */
@Composable
inline fun <reified VM : ViewModel> gyroViewModel(crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as GyroApp
    return viewModel(factory = viewModelFactory { initializer { create(app.container) } })
}
