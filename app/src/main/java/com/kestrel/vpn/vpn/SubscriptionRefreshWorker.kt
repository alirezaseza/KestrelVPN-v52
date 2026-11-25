package com.kestrel.vpn.vpn
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
@HiltWorker
class SubscriptionRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ConfigRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return when (repository.refreshSubscription(force = false)) {
            is SubscriptionRefreshResult.Updated,
            is SubscriptionRefreshResult.NotModified,
            is SubscriptionRefreshResult.SkippedThrottled,
            is SubscriptionRefreshResult.CachedOffline -> Result.success()
            is SubscriptionRefreshResult.Error -> {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }
    companion object {
        private const val UNIQUE_WORK_NAME = "kestrel_subscription_periodic_refresh"
        fun schedulePeriodicRefresh(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
