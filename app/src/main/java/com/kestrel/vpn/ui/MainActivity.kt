package com.kestrel.vpn.ui
import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kestrel.vpn.data.local.AppThemeMode
import com.kestrel.vpn.data.local.UserPreferences
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.ui.components.LocalReduceEffects
import com.kestrel.vpn.ui.navigation.KestrelNavGraph
import com.kestrel.vpn.ui.theme.KestrelTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var preferencesDataStore: UserPreferencesDataStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs: UserPreferences? by preferencesDataStore.preferencesFlow
                .collectAsStateWithLifecycle(initialValue = null)
            KestrelTheme(themeMode = prefs?.themeMode ?: AppThemeMode.SYSTEM) {
                val loadedPrefs = prefs
                if (loadedPrefs == null) {
                    Surface(modifier = Modifier.fillMaxSize()) {}
                } else {
                    val lowRam = remember {
                        getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
                    }
                    CompositionLocalProvider(
                        LocalReduceEffects provides (loadedPrefs.reduceEffects || lowRam)
                    ) {
                        KestrelNavGraph(
                            onboardingCompleted = loadedPrefs.onboardingCompleted,
                            onCompleteOnboarding = {
                                lifecycleScope.launch {
                                    preferencesDataStore.setPublicNoticeAcknowledged(true)
                                    preferencesDataStore.setOnboardingCompleted(true)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
