package com.sickdash.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sickdash.service.CanService
import com.sickdash.service.ConnectionManager

/** Entry point Android Auto binds to. */
class DashCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ConnectionManager.acquire(carContext, CLIENT)
                // Keep the link alive if the phone locks; may be refused from the background, which is fine.
                runCatching { CanService.start(carContext) }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ConnectionManager.release(CLIENT)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = SurfaceRenderer(carContext, lifecycle)
        return DashScreen(carContext, renderer)
    }

    private companion object {
        const val CLIENT = "android-auto"
    }
}
