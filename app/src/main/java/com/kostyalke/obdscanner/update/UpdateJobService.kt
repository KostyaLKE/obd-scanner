package com.kostyalke.obdscanner.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Фоновая проверка обновлений: примерно раз в 12 часов и только при наличии сети.
 * Точное время выбирает Android. Задача скачивает новую сборку заранее; ставит её сама
 * лишь там, где система разрешает установку без вопросов (Android 12+), и только когда
 * диагностика не идёт. В остальных случаях скачанный файл ждёт открытия приложения.
 * Сбой (нет интернета и т.п.) диагностике не мешает: Android повторит позже с нарастающей паузой.
 */
class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            var retry = false
            try {
                val rel = Updater.fetchLatest()
                if (rel.versionCode > Updater.currentVersionCode(applicationContext)) {
                    val file = Updater.download(applicationContext, rel) {}
                    if (Build.VERSION.SDK_INT >= 31 && Updater.canInstall(applicationContext) && !Updater.isBusy()) {
                        Updater.install(applicationContext, file)
                    }
                }
            } catch (e: Exception) {
                retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 1701
        private const val PERIOD_MS = 12 * 60 * 60 * 1000L

        /** Включает или выключает фоновую проверку вместе с настройкой «обновлять автоматически». */
        fun schedule(ctx: Context, enabled: Boolean) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setBackoffCriteria(30 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            runCatching { js.schedule(info) }
        }
    }
}
