package com.kestrel.vpn.ui.navigation
import com.kestrel.vpn.ui.components.AuroraBackground
import com.kestrel.vpn.domain.model.ConnectionStatus
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kestrel.vpn.ui.home.HomeScreen
import com.kestrel.vpn.ui.onboarding.OnboardingScreen
import com.kestrel.vpn.ui.privacy.PrivacyPolicyScreen
import com.kestrel.vpn.ui.servers.ServerListScreen
import com.kestrel.vpn.ui.settings.SettingsScreen
import com.kestrel.vpn.ui.splittunnel.SplitTunnelScreen
object KestrelRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val SPLIT_TUNNEL = "split_tunnel"
}
@Composable
fun KestrelNavGraph(
    onboardingCompleted: Boolean,
    onCompleteOnboarding: () -> Unit
) {
    val navController = rememberNavController()
    val startDestination = if (onboardingCompleted) KestrelRoutes.HOME else KestrelRoutes.ONBOARDING
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 1.02f) },
        popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.97f) }
    ) {
        composable(KestrelRoutes.ONBOARDING) {
            AuroraBackground(status = ConnectionStatus.DISCONNECTED) {
                OnboardingScreen(
                    onFinished = {
                        onCompleteOnboarding()
                        navController.navigate(KestrelRoutes.HOME) {
                            popUpTo(KestrelRoutes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }
        }
        composable(KestrelRoutes.HOME) {
            HomeScreen(
                onOpenServers = { navController.navigate(KestrelRoutes.SERVERS) },
                onOpenSettings = { navController.navigate(KestrelRoutes.SETTINGS) }
            )
        }
        composable(KestrelRoutes.SERVERS) {
            ServerListScreen(onBack = { navController.popBackStack() })
        }
        composable(KestrelRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPrivacyPolicy = { navController.navigate(KestrelRoutes.PRIVACY) },
                onOpenSplitTunnel = { navController.navigate(KestrelRoutes.SPLIT_TUNNEL) }
            )
        }
        composable(KestrelRoutes.SPLIT_TUNNEL) {
            SplitTunnelScreen(onBack = { navController.popBackStack() })
        }
        composable(KestrelRoutes.PRIVACY) {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }
    }
}
