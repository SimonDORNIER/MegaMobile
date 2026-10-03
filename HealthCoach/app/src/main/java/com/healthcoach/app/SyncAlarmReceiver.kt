package com.healthcoach.app
import android.content.*
class SyncAlarmReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent?){Scheduler.now(c);Scheduler.apply(c)}}