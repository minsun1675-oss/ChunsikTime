package com.chunsik.study

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Maintains one upcoming reminder, avoiding hundreds of stale recurring alarms. */
object ReminderScheduler {
    private const val REQUEST=9107
    private fun alarm(context:Context)=context.getSystemService(AlarmManager::class.java)
    private fun intent(context:Context)=PendingIntent.getBroadcast(context,REQUEST,
        Intent(context,ReminderReceiver::class.java).setAction("com.chunsik.study.REMIND"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context:Context){
        val manager=alarm(context)
        val pending=intent(context)
        manager.cancel(pending)
        val p=context.getSharedPreferences("app",0)
        val now=System.currentTimeMillis()
        val zone=ZoneId.systemDefault()
        val candidates=mutableListOf<Long>()
        if(p.getBoolean("morning_enabled",true)){
            val hhmm=p.getString("morning_time","08:00")?:"08:00"
            val parsed=runCatching{LocalTime.parse(hhmm)}.getOrDefault(LocalTime.of(8,0))
            for(i in 0..1){
                val day=LocalDate.now().plusDays(i.toLong())
                val stamp=LocalDateTime.of(day,parsed).atZone(zone).toInstant().toEpochMilli()
                if(stamp>now+2000)candidates+=stamp
            }
        }
        if(p.getBoolean("plan_reminders",true)){
            val store=Store(context)
            for(i in 0..7){
                val day=LocalDate.now().plusDays(i.toLong())
                if(store.isRest(day))continue
                for(plan in store.plansFor(day)){
                    val stamp=day.atStartOfDay(zone).plusMinutes(plan.startMinute.toLong()-10).toInstant().toEpochMilli()
                    if(stamp>now+2000)candidates+=stamp
                }
            }
            store.close()
        }
        val next=candidates.minOrNull()?:return
        // Inexact and idle-aware; Android may defer delivery for battery optimization.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
    }
}

class ReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action=="android.intent.action.BOOT_COMPLETED" ||
           intent?.action=="android.intent.action.TIME_SET" ||
           intent?.action=="android.intent.action.TIMEZONE_CHANGED"){
            ReminderScheduler.schedule(context)
            return
        }
        val prefs=context.getSharedPreferences("app",0)
        val now=System.currentTimeMillis()
        val zone=ZoneId.systemDefault()
        val current=java.time.Instant.ofEpochMilli(now).atZone(zone)
        val time=current.toLocalTime()
        val day=current.toLocalDate()
        val hhmm=prefs.getString("morning_time","08:00")?:"08:00"
        val morning=runCatching{LocalTime.parse(hhmm)}.getOrDefault(LocalTime.of(8,0))
        if(prefs.getBoolean("morning_enabled",true) &&
           kotlin.math.abs(java.time.Duration.between(morning,time).toMinutes())<=5){
            val already=prefs.getString("last_morning","")
            if(already!=day.toString()){
                prefs.edit().putString("last_morning",day.toString()).apply()
                notify(context,8801,"오늘도, 내일도, 한결같이.",QuoteStore.all(context).random())
            }
        }
        if(prefs.getBoolean("plan_reminders",true)){
            val store=Store(context)
            val running=context.getSharedPreferences("timer",0)
            val plans=store.plansFor(day)
            for(plan in plans){
                val scheduled=day.atStartOfDay(zone).plusMinutes(plan.startMinute.toLong()-10).toInstant().toEpochMilli()
                if(kotlin.math.abs(now-scheduled)<5*60*1000 && !store.isRest(day)){
                    if((running.getBoolean("running",false)||running.getBoolean("paused",false)) && running.getLong("subject",-1)==plan.subjectId)continue
                    val key="plan_reminder_${day}_${plan.id}"
                    if(prefs.getBoolean(key,false))continue
                    prefs.edit().putBoolean(key,true).apply()
                    notify(context,plan.id.toInt(),"책상에 앉을 준비는 되셨나요? 🌱","10분 후 ${plan.subject} 공부가 시작돼요!")
                }
            }
            store.close()
        }
        ReminderScheduler.schedule(context)
    }

    private fun notify(context:Context,id:Int,title:String,body:String){
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("study_reminders","학습 알림",NotificationManager.IMPORTANCE_DEFAULT))
        val launch=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,"study_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setColor(android.graphics.Color.rgb(113,139,122))
            .setAutoCancel(true).setContentIntent(launch).build()
        nm.notify(id,notification)
    }
}
