package com.tvdeck.app.car

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/** Android Auto binds to this when TVDeck is opened on the car screen, and it shows [TVDeckCarActivity]. */
class CarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = TVDeckCarActivity::class.java
}
