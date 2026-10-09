package org.projectbarry.pbosinstaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.projectbarry.pbosinstaller.device.DeviceInfo
import org.projectbarry.pbosinstaller.ui.InstallerScreen
import org.projectbarry.pbosinstaller.ui.InstallerViewModel

// PB-OS purple, as on the stick lights (8c00ff).
private val Purple = Color(0xFF8C00FF)

class MainActivity : ComponentActivity() {
    private val vm: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) intent.getStringExtra("fakeSoc")?.let { soc ->
            DeviceInfo.debugFake = DeviceInfo.read().copy(
                manufacturer = intent.getStringExtra("fakeManufacturer").orEmpty(),
                model = intent.getStringExtra("fakeModel").orEmpty(),
                socModel = soc,
            )
        }
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFC58BFF), secondary = Purple)
            } else {
                lightColorScheme(primary = Purple, secondary = Purple)
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    InstallerScreen(vm)
                }
            }
        }
    }
}
