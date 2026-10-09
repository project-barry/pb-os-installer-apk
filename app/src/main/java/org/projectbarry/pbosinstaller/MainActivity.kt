package org.projectbarry.pbosinstaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.projectbarry.pbosinstaller.device.DeviceInfo
import org.projectbarry.pbosinstaller.ui.InstallerScreen
import org.projectbarry.pbosinstaller.ui.InstallerViewModel

// Always dark: a calm dark background with PB-OS purple (as on the stick lights, 8c00ff) for actions.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC08CFF),
    onPrimary = Color(0xFF22004A),
    primaryContainer = Color(0xFF5B12A8),
    onPrimaryContainer = Color(0xFFF0DBFF),
    secondary = Color(0xFF8C00FF),
    background = Color(0xFF121016),
    onBackground = Color(0xFFE8E1EC),
    surface = Color(0xFF121016),
    onSurface = Color(0xFFE8E1EC),
    surfaceVariant = Color(0xFF241F2B),
    onSurfaceVariant = Color(0xFFCBC3D3),
    surfaceContainerLowest = Color(0xFF0D0B10),
    surfaceContainerLow = Color(0xFF1A161F),
    surfaceContainer = Color(0xFF1E1A24),
    surfaceContainerHigh = Color(0xFF241F2B),
    surfaceContainerHighest = Color(0xFF2B2533),
    outline = Color(0xFF8E8698),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0000),
)

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                // Background edge to edge, content clear of the bars and any cutout.
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        InstallerScreen(vm)
                    }
                }
            }
        }
    }
}
