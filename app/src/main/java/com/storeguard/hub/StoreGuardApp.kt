package com.storeguard.hub

import android.app.Application
import com.storeguard.hub.data.StoreGuardDatabase

class StoreGuardApp : Application() {
    val database: StoreGuardDatabase by lazy { StoreGuardDatabase.get(this) }
}
