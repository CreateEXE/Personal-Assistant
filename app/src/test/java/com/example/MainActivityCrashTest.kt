package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityCrashTest {

    @Test
    fun testActivityLaunch() {
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            assert(controller.get() != null)
        } catch (e: Exception) {
            System.err.println("CRASH CAUSE: " + e.message)
            e.cause?.printStackTrace()
            throw e
        }
    }
}
