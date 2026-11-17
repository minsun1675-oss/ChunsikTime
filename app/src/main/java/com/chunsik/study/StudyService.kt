package com.chunsik.study

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

/** Foreground stopwatch. No server, internet, or permanent CPU wake lock. */
class StudyService: Service() {
    companion object {
        const val ACTION_START="com.chunsik.study.START"
        const val ACTION_PAUSE="com.chunsik.study.PAUSE"
        const val ACTION_RESUME="com.chunsik.study.RESUME"
        const val ACTION_END="com.chunsik.study.END"
        const val ACTION_OVERLAY="com.chunsik.study.OVERLAY"
        const val ACTION_HIDE="com.chunsik.study.HIDE"
        @Volatile var alive=false
    }
    private val handler=Handler(Looper.getMainLooper())
    private val store by lazy {Store(this)}
    private val prefs by lazy {getSharedPreferences("timer",0)}
    private var overlay:View?=null
    private var wm:WindowManager?=null
    private var notificationManager:NotificationManager?=null
    private var checkpointSecond=0
    private val ticker=object:Runnable {override fun run(){
        if(prefs.getBoolean("running",false)) {
            if(++checkpointSecond%15==0) prefs.edit().putLong("checkpoint",System.currentTimeMillis()).apply()
        }
        updateOverlay();handler.postDelayed(this,1000L)
    }}
    override fun onCreate() {super.onCreate();alive=true
        notificationManager=getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(NotificationChannel("study_timer","공부 타이머",NotificationManager.IMPORTANCE_LOW))
        handler.post(ticker)
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            ACTION_START -> {
                if(!prefs.getBoolean("running",false)&&!prefs.getBoolean("paused",false)){
                    val subject=intent.getLongExtra("subject",-1);if(store.subjects().none{it.id==subject})return START_NOT_STICKY
                    val now=System.currentTimeMillis()
                    prefs.edit().putLong("subject",subject).putBoolean("running",true).putBoolean("paused",false).putLong("spanStart",now).putLong("checkpoint",now).putLong("sessionSeconds",0).apply()
                    startForegroundNotification();setDnd(true)
                }
            }
            ACTION_PAUSE -> {
                if(prefs.getBoolean("running",false)) {
                    val stoppedAt=System.currentTimeMillis()
                    val from=prefs.getLong("spanStart",stoppedAt)
                    commitSpan(stoppedAt)
                    prefs.edit().putLong("sessionSeconds",prefs.getLong("sessionSeconds",0)+((stoppedAt-from)/1000).coerceAtLeast(0)).putBoolean("running",false).putBoolean("paused",true).apply()
                    startForegroundNotification()
                }
            }
            ACTION_RESUME -> {
                if(prefs.getBoolean("paused",false)) {
                    val now=System.currentTimeMillis();prefs.edit().putBoolean("running",true).putBoolean("paused",false).putLong("spanStart",now).putLong("checkpoint",now).apply()
                    startForegroundNotification()
                }
            }
            ACTION_END -> {
                if(prefs.getBoolean("running",false))commitSpan(System.currentTimeMillis())
                prefs.edit().putBoolean("running",false).putBoolean("paused",false).remove("subject").remove("sessionSeconds").apply()
                setDnd(false);hideOverlay();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                sendBroadcast(Intent("com.chunsik.study.REFRESH").setPackage(packageName))
            }
            ACTION_OVERLAY -> if(prefs.getBoolean("running",false)||prefs.getBoolean("paused",false))showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }
    private fun commitSpan(now:Long) {
        val from=prefs.getLong("spanStart",now)
        val to=now.coerceAtLeast(from)
        val subject=prefs.getLong("subject",-1)
        if(subject!=-1L) store.recordActiveSpan(subject,from,to)
        prefs.edit().putLong("spanStart",to).putLong("checkpoint",to).apply()
    }
    private fun startForegroundNotification() {
        val launch=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,StudyService::class.java).setAction(if(prefs.getBoolean("running",false))ACTION_PAUSE else ACTION_RESUME),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val subjectName=store.subjects().firstOrNull{it.id==prefs.getLong("subject",-1)}?.name?:"공부"
        val n=Notification.Builder(this,"study_timer")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("$subjectName · ${if(prefs.getBoolean("running",false))"공부 중" else "일시정지"}")
            .setContentText("학습 타이머 작동 중 · 눌러서 앱 열기")
            .setContentIntent(launch).addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,if(prefs.getBoolean("running",false))"일시정지" else "이어하기",pause).build())
            .setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(5001,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(5001,n)
    }
    private fun setDnd(enable:Boolean) {
        if(!getSharedPreferences("app",0).getBoolean("focus",true))return
        val nm=getSystemService(NotificationManager::class.java)
        if(!nm.isNotificationPolicyAccessGranted)return
        val dndPrefs=getSharedPreferences("dnd",0)
        if(enable){
            if(!dndPrefs.getBoolean("owned",false)){
                val previous=nm.currentInterruptionFilter
                dndPrefs.edit().putInt("previous",previous).putBoolean("owned",true).apply()
                if(previous==NotificationManager.INTERRUPTION_FILTER_ALL)nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                else dndPrefs.edit().putBoolean("owned",false).apply() // did not change it
            }
        }else if(dndPrefs.getBoolean("owned",false)){
            // Do not override a change the user may have made while studying.
            if(nm.currentInterruptionFilter==NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(dndPrefs.getInt("previous",NotificationManager.INTERRUPTION_FILTER_ALL))
            dndPrefs.edit().putBoolean("owned",false).apply()
        }
    }
    private fun showOverlay(){
        if(overlay!=null||!Settings.canDrawOverlays(this)||!getSharedPreferences("app",0).getBoolean("floating",true))return
        val manager=getSystemService(WINDOW_SERVICE) as WindowManager;wm=manager
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,18,18,14);setBackgroundColor(android.graphics.Color.rgb(48,59,53));elevation=12f}
        val label=TextView(this).apply{tag="label";setTextColor(android.graphics.Color.WHITE);textSize=13f}
        val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val timer=TextView(this).apply{tag="time";setTextColor(android.graphics.Color.WHITE);textSize=22f;typeface=android.graphics.Typeface.MONOSPACE;setPadding(0,8,20,0)}
        val pause=Button(this).apply{tag="pause";text="Ⅱ";textSize=12f;setOnClickListener { startService(Intent(this@StudyService,StudyService::class.java).setAction(if(prefs.getBoolean("running",false))ACTION_PAUSE else ACTION_RESUME)) }}
        val close=Button(this).apply{text="×";textSize=12f;setOnClickListener {hideOverlay()} }
        line.addView(timer);line.addView(pause,LinearLayout.LayoutParams(105,105));line.addView(close,LinearLayout.LayoutParams(100,105))
        root.addView(label);root.addView(line)
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=40;y=180}
        root.setOnTouchListener(object:View.OnTouchListener{var ox=0;var oy=0;var tx=0f;var ty=0f
            override fun onTouch(v:View,event:android.view.MotionEvent):Boolean{when(event.action){
                android.view.MotionEvent.ACTION_DOWN->{ox=params.x;oy=params.y;tx=event.rawX;ty=event.rawY;return true}
                android.view.MotionEvent.ACTION_MOVE->{params.x=ox+(event.rawX-tx).toInt();params.y=oy+(event.rawY-ty).toInt();manager.updateViewLayout(root,params);return true}
            };return false}
        })
        try{manager.addView(root,params);overlay=root;updateOverlay()}catch(_:Exception){overlay=null}
    }
    private fun updateOverlay(){val v=overlay?:return
        val sid=prefs.getLong("subject",-1);val name=store.subjects().firstOrNull{it.id==sid}?.name?:"공부"
        (v.findViewWithTag<TextView>("label"))?.text=name
        val elapsed=if(prefs.getBoolean("running",false))((System.currentTimeMillis()-prefs.getLong("spanStart",System.currentTimeMillis()))/1000).coerceAtLeast(0) else 0L
        (v.findViewWithTag<TextView>("time"))?.text=formatClock(elapsed+prefs.getLong("sessionSeconds",0))
        (v.findViewWithTag<Button>("pause"))?.text=if(prefs.getBoolean("running",false))"Ⅱ" else "▶"
    }
    private fun hideOverlay(){overlay?.let{try{wm?.removeView(it)}catch(_:Exception){}};overlay=null}
    override fun onDestroy(){handler.removeCallbacks(ticker);hideOverlay();alive=false;super.onDestroy()}
}
fun formatClock(s:Long):String="%02d:%02d:%02d".format(s/3600,(s%3600)/60,s%60)