package com.chunsik.study

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import java.time.temporal.ChronoUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Calendar

private data class Palette(val bg:Color,val card:Color,val accent:Color,val text:Color,val muted:Color,val onAccent:Color)
private val palettes=listOf(
    Palette(Color(0xFFE9EDE5),Color(0xFFFAFAF6),Color(0xFF718B7A),Color(0xFF34473A),Color(0xFF667E6D),Color.White),
    Palette(Color(0xFF202724),Color(0xFF303B35),Color(0xFF52695A),Color(0xFFF4F2EA),Color(0xFFB9C8BC),Color.White),
    Palette(Color(0xFFECEEF0),Color(0xFFFAFAFB),Color(0xFF697D92),Color(0xFF34465A),Color(0xFF677989),Color.White),
    Palette(Color(0xFFF2EBDF),Color(0xFFFFF9F0),Color(0xFFA48B70),Color(0xFF514539),Color(0xFF867461),Color.White),
    Palette(Color(0xFFEEEAF2),Color(0xFFFAF8FC),Color(0xFF8C829E),Color(0xFF494155),Color(0xFF857C91),Color.White)
)
private val colorSet=listOf(Color(0xFF789C81),Color(0xFFC7A371),Color(0xFF859DC1),Color(0xFFB487A5),Color(0xFF66A8A1))
private val words=listOf("오늘도 할 수 있다.","오늘의 노력이 내일을 만든다.","꾸준함은 재능을 이긴다.","조금씩, 그러나 계속.","오늘의 한 시간이 쌓인다.","포기하지 않는 하루.","어제보다 한 걸음 더.")

class MainActivity:ComponentActivity(){
    private val store by lazy {Store(this)}
    private val appPrefs by lazy { getSharedPreferences("app",0) }
    private val timerPrefs by lazy { getSharedPreferences("timer",0) }
    private var selectedScreen by mutableStateOf(0)
    private var jumpToTodayPlan by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)
    private var permissionLauncher:androidx.activity.result.ActivityResultLauncher<String>?=null
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){refreshKey++}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        if(!StudyService.alive && timerPrefs.getBoolean("running",false)) {
            val sid=timerPrefs.getLong("subject",-1)
            val start=timerPrefs.getLong("spanStart",System.currentTimeMillis())
            val checkpoint=timerPrefs.getLong("checkpoint",start)
            if(sid!=-1L && checkpoint>start)store.recordActiveSpan(sid,start,checkpoint)
            timerPrefs.edit().putBoolean("running",false).putBoolean("paused",false).remove("subject").apply()
        }
        Backups(this,store).checkMonthly()
        if(Build.VERSION.SDK_INT>=33){
            permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReminderScheduler.schedule(this)
        val filter=IntentFilter("com.chunsik.study.REFRESH")
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)
        setContent { StudyApp() }
        if(!Settings.canDrawOverlays(this) && !appPrefs.getBoolean("overlay_intro_seen",false)) {
            showOverlayPermissionIntro()
        }
    }
    private fun showOverlayPermissionIntro() {
        android.app.AlertDialog.Builder(this)
            .setTitle("플로팅 타이머 권한")
            .setMessage("다른 앱을 사용하면서도 공부 시간을 볼 수 있어요. '다른 앱 위에 표시' 권한을 허용해주세요. 나중에 설정해도 일반 타이머는 사용할 수 있어요.")
            .setPositiveButton("권한 설정하기"){_,_->
                appPrefs.edit().putBoolean("overlay_intro_seen",true).apply()
                runCatching{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
                    .onFailure{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))}
            }
            .setNegativeButton("나중에 설정"){_,_->appPrefs.edit().putBoolean("overlay_intro_seen",true).apply()}
            .show()
    }
    override fun onDestroy(){try{unregisterReceiver(receiver)}catch(_:Exception){};super.onDestroy()}
    override fun onStop(){super.onStop();if(!isChangingConfigurations && (timerPrefs.getBoolean("running",false)||timerPrefs.getBoolean("paused",false))) sendAction(StudyService.ACTION_OVERLAY)}
    override fun onStart(){super.onStart();sendAction(StudyService.ACTION_HIDE);ReminderScheduler.schedule(this)}
    private fun sendAction(action:String,subject:Long=-1) {val intent=Intent(this,StudyService::class.java).setAction(action).putExtra("subject",subject)
        try{if(action==StudyService.ACTION_START)startForegroundService(intent) else if(StudyService.alive) startService(intent)}catch(_:Exception){}
        refreshKey++
    }
    @Composable private fun StudyApp(){
        var theme by remember { mutableIntStateOf(appPrefs.getInt("theme",0)) }
        val pal=palettes[theme.coerceIn(0,4)]
        val selectedQuote=remember{QuoteStore.all(this@MainActivity).random()}
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit){while(true){tick=System.currentTimeMillis();delay(1000)}}
        val force=refreshKey // snapshot invalidation after writes
        val subjects=remember(force){
            val raw=store.subjects()
            val order=appPrefs.getString("subjectOrder","").orEmpty().split(",").mapNotNull{it.toLongOrNull()}
            raw.sortedWith(compareBy<Subject>({val index=order.indexOf(it.id);if(index<0) Int.MAX_VALUE else index},{it.id}))
        }
        val records=remember(force){store.records()}
        val plans=remember(force){store.plans()}
        val events=remember(force){store.events()}
        Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding()){
            Column(Modifier.fillMaxWidth().background(pal.accent).padding(horizontal=18.dp, vertical=15.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("또바기",fontSize=12.sp,color=pal.onAccent.copy(alpha=.85f),modifier=Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp));Text(selectedQuote,fontWeight=FontWeight.SemiBold,fontSize=19.sp,color=pal.onAccent)
            }
            Box(Modifier.weight(1f)){
                when(selectedScreen){
                    0->TimerPage(pal,subjects,records,tick)
                    1->SchedulePage(pal,subjects,plans,jumpToTodayPlan){jumpToTodayPlan=false}
                    2->CalendarPage(pal,events,records)
                    3->StatsPage(pal,subjects,records)
                    else->SettingsPage(pal,subjects,records,theme,{theme=it;appPrefs.edit().putInt("theme",it).apply()})
                }
            }
            Row(Modifier.fillMaxWidth().background(pal.accent).navigationBarsPadding().padding(horizontal=4.dp, vertical=6.dp),horizontalArrangement=Arrangement.SpaceAround){
                val names=listOf("타이머","시간표","캘린더","통계","설정")
                val icons=listOf(Icons.Default.Timer,Icons.Default.DateRange,Icons.Default.CalendarMonth,Icons.Default.PieChart,Icons.Default.Settings)
                names.forEachIndexed {i,label->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if(selectedScreen==i) pal.onAccent.copy(alpha=.17f) else Color.Transparent).clickable{selectedScreen=i}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Icon(icons[i],null,tint=pal.onAccent,modifier=Modifier.size(22.dp));Text(label,color=pal.onAccent,fontSize=11.sp)
                    }
                }
            }
        }
    }
    @Composable private fun Card(p:Palette,content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(p.card).padding(15.dp),verticalArrangement=Arrangement.spacedBy(11.dp),content=content)}
    @Composable private fun Heading(p:Palette,title:String,subtitle:String="") { Column {if(subtitle.isNotBlank())Text(subtitle,fontSize=10.sp,color=p.muted);Text(title,fontWeight=FontWeight.Bold,fontSize=20.sp,color=p.text)} }
    @Composable private fun PrimaryButton(p:Palette,label:String,enabled:Boolean=true,onClick:()->Unit){ Button(onClick=onClick,enabled=enabled,colors=ButtonDefaults.buttonColors(containerColor=p.accent),shape=RoundedCornerShape(12.dp)){Text(label,color=p.onAccent)} }
    @Composable private fun TimerPage(p:Palette,subjects:List<Subject>,records:List<StudyRecord>,tick:Long){
        val running=timerPrefs.getBoolean("running",false);val paused=timerPrefs.getBoolean("paused",false)
        val subjectId=timerPrefs.getLong("subject",-1)
        val chosen=subjects.find{it.id==subjectId}
        val today=LocalDate.now()
        var lastSubject by remember { mutableLongStateOf(subjects.firstOrNull()?.id?:-1L) }
        var manageSubjects by remember { mutableStateOf(false) }
        var newSubject by remember { mutableStateOf("") }
        var editId by remember { mutableLongStateOf(-1L) }
        var editName by remember { mutableStateOf("") }
        var subjectMessage by remember { mutableStateOf("") }
        fun reorder(id:Long,delta:Int) {
            val ids=subjects.map{it.id}.toMutableList()
            val i=ids.indexOf(id)
            if(i>=0 && i+delta in ids.indices) {
                val other=ids[i+delta];ids[i+delta]=ids[i];ids[i]=other
                appPrefs.edit().putString("subjectOrder",ids.joinToString(",")).apply()
                refreshKey++
            }
        }
        val count=records.filter{Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate()==today}.sumOf{it.seconds}
        val goal=store.targetFor(today)
        val secs=if(running) ((tick-timerPrefs.getLong("spanStart",tick))/1000).coerceAtLeast(0) else 0L
        LazyColumn(contentPadding=PaddingValues(13.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Heading(p,"학습 타이머","TIMER");Text(if(running)"공부 중" else if(paused)"일시정지" else "대기 중",color=p.muted,fontSize=12.sp)} }
            item {Card(p){
                if(running||paused){
                    Text(chosen?.name?:"과목",color=p.muted,modifier=Modifier.align(Alignment.CenterHorizontally))
                    Text(formatClock(secs+timerPrefs.getLong("sessionSeconds",0)),fontFamily=FontFamily.Monospace,fontSize=39.sp,fontWeight=FontWeight.Bold,color=p.text,modifier=Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                        PrimaryButton(p,if(running)"Ⅱ 일시정지" else "▶ 이어하기"){sendAction(if(running)StudyService.ACTION_PAUSE else StudyService.ACTION_RESUME)}
                        Spacer(Modifier.width(10.dp)); OutlinedButton(onClick={sendAction(StudyService.ACTION_END)}){Text("■ 종료",color=p.text)}
                    }
                } else {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Text("공부할 과목을 선택하세요",color=p.text,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                        TextButton(onClick={manageSubjects=!manageSubjects;subjectMessage="";editId=-1L}){
                            Icon(if(manageSubjects) Icons.Default.Close else Icons.Default.Edit,null,tint=p.accent,modifier=Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if(manageSubjects)"완료" else "편집",color=p.accent)
                        }
                    }
                    if(!manageSubjects){
                        if(subjects.isEmpty()){
                            Text("아직 등록된 학습과목이 없어요. 편집을 눌러 첫 과목을 추가해 주세요.",color=p.muted,fontSize=13.sp)
                        }
                        subjects.forEach{subject->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg).clickable{lastSubject=subject.id;sendAction(StudyService.ACTION_START,subject.id)}.padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(subject.name,color=p.text,fontSize=14.sp);Icon(Icons.Default.PlayArrow,null,tint=p.accent)}
                        }
                    }else{
                        subjects.forEachIndexed{index,sub->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg).padding(9.dp)){
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                    Text(sub.name,color=p.text,fontSize=14.sp,modifier=Modifier.weight(1f))
                                    IconButton(onClick={reorder(sub.id,-1)},enabled=index>0,modifier=Modifier.size(34.dp)){Icon(Icons.Default.KeyboardArrowUp,"위로",tint=p.muted)}
                                    IconButton(onClick={reorder(sub.id,1)},enabled=index<subjects.lastIndex,modifier=Modifier.size(34.dp)){Icon(Icons.Default.KeyboardArrowDown,"아래로",tint=p.muted)}
                                    IconButton(onClick={editId=if(editId==sub.id)-1L else sub.id;editName=sub.name},modifier=Modifier.size(34.dp)){Icon(Icons.Default.Edit,"이름 변경",tint=p.accent)}
                                    IconButton(onClick={if(store.deleteSubject(sub.id)){refreshKey++;subjectMessage="과목을 삭제했어."}else subjectMessage="공부 기록이나 계획이 있는 과목은 삭제할 수 없어."},modifier=Modifier.size(34.dp)){Icon(Icons.Default.Delete,"삭제",tint=p.muted)}
                                }
                                if(editId==sub.id){
                                    OutlinedTextField(value=editName,onValueChange={editName=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("과목 이름")})
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                                        TextButton(onClick={editId=-1L}){Text("취소",color=p.muted)}
                                        TextButton(onClick={
                                            val name=editName.trim()
                                            if(name.isBlank() || subjects.any{it.id!=sub.id&&it.name==name}) subjectMessage="비어 있거나 중복된 과목 이름이야."
                                            else runCatching{store.renameSubject(sub.id,name)}.onSuccess{editId=-1L;refreshKey++;subjectMessage="과목 이름을 변경했어."}.onFailure{subjectMessage="이름 변경에 실패했어."}
                                        }){Text("저장",color=p.accent)}
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedTextField(value=newSubject,onValueChange={newSubject=it},modifier=Modifier.weight(1f),singleLine=true,label={Text("새 과목")})
                            PrimaryButton(p,"추가"){
                                val name=newSubject.trim()
                                if(name.isBlank() || subjects.any{it.name==name}) subjectMessage="비어 있거나 중복된 과목 이름이야."
                                else {store.addSubject(name);newSubject="";refreshKey++;subjectMessage="과목을 추가했어."}
                            }
                        }
                        if(subjectMessage.isNotBlank())Text(subjectMessage,color=p.muted,fontSize=12.sp)
                    }
                }
            }}
            item {Card(p){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("오늘의 목표",fontWeight=FontWeight.SemiBold,color=p.text);TextButton(onClick={selectedScreen=1;jumpToTodayPlan=true}){Text("목표 설정",color=p.accent,fontSize=12.sp)}}
                LinearProgressIndicator(progress={if(goal>0) (count.toFloat()/goal).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(8.dp),color=p.accent,trackColor=p.bg,strokeCap=StrokeCap.Round)
                Text("공부 ${formatClock(count)}  /  목표 ${if(goal==0L)"없음" else formatClock(goal)}",color=p.muted,fontSize=12.sp)
            }}
            item {Card(p){Text("과목별 공부 기록",fontWeight=FontWeight.SemiBold,color=p.text)
                subjects.forEach{s->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(s.name,color=p.text,fontSize=13.sp);Text(formatClock(records.filter{it.subjectId==s.id&&Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate()==today}.sumOf{it.seconds}),color=p.muted,fontSize=13.sp,fontFamily=FontFamily.Monospace)} }
            }}
        }
    }
    @Composable private fun SchedulePage(p:Palette,subjects:List<Subject>,plans:List<Plan>,openToday:Boolean=false,onOpened:()->Unit={}){
        var date by remember {mutableStateOf(LocalDate.now())}
        var editing by remember {mutableStateOf(false)}
        var subject by remember {mutableLongStateOf(subjects.firstOrNull()?.id?:-1L)}
        var start by remember {mutableIntStateOf(1140)};var end by remember {mutableIntStateOf(1230)}
        var mode by remember {mutableStateOf("weekly")}
        var weekdays by remember {mutableStateOf(setOf(1,2,3,4,5))}
        var picker by remember {mutableStateOf("")}
        LaunchedEffect(openToday){if(openToday){date=LocalDate.now();editing=true;onOpened()}}
        var error by remember {mutableStateOf("")}
        val week=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val current=store.plansFor(date)
        LazyColumn(contentPadding=PaddingValues(13.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Heading(p,"주간 시간표","SCHEDULE");TextButton(onClick={date=LocalDate.now()}){Text("오늘",color=p.accent)} }}
            item {Card(p){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){IconButton(onClick={date=date.minusWeeks(1)}){Icon(Icons.Default.ChevronLeft,null,tint=p.text)};Text("${week.monthValue}.${week.dayOfMonth} ~ ${week.plusDays(6).monthValue}.${week.plusDays(6).dayOfMonth}",color=p.text);IconButton(onClick={date=date.plusWeeks(1)}){Icon(Icons.Default.ChevronRight,null,tint=p.text)}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){(0..6).forEach{i->val d=week.plusDays(i.toLong());Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if(date==d)p.accent else p.bg).clickable{date=d;editing=false}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(listOf("월","화","수","목","금","토","일")[i],color=if(date==d)p.onAccent else p.text,fontSize=12.sp);Text("${d.dayOfMonth}",color=if(date==d)p.onAccent else p.muted,fontSize=11.sp)} }}
            }}
            item {Card(p){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("${date.monthValue}월 ${date.dayOfMonth}일 계획",color=p.muted,fontSize=12.sp);Text(if(store.isRest(date))"휴식일" else "${current.sumOf{it.endMinute-it.startMinute}/60.0}시간 목표",color=p.text,fontSize=18.sp,fontWeight=FontWeight.SemiBold)};PrimaryButton(p,"+ 추가"){editing=!editing;error=""}}
                if(store.isRest(date))Text("휴식일에는 계획과 목표가 적용되지 않아.",color=p.muted,fontSize=12.sp)
                else if(current.isEmpty())Text("등록된 공부 계획이 없어.",color=p.muted)
                else current.forEach{plan->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.bg).padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text(plan.subject,color=p.text);Text("${formatMinute(plan.startMinute)} ~ ${formatMinute(plan.endMinute)}",color=p.muted,fontSize=12.sp)};IconButton(onClick={store.deletePlan(plan.id);refreshKey++;ReminderScheduler.schedule(this@MainActivity)}){Icon(Icons.Default.Delete,null,tint=p.muted)}}}
            }}
            if(editing) item {Card(p){
                Text("공부 계획 추가",fontWeight=FontWeight.Bold,color=p.text)
                Text("과목 선택",fontSize=12.sp,color=p.muted)
                SubjectPicker(subjects,subject,p,Modifier.fillMaxWidth()){subject=it}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Column(Modifier.weight(1f)){Text("시작",color=p.muted,fontSize=12.sp);OutlinedButton(onClick={picker="start"},modifier=Modifier.fillMaxWidth()){Text(formatClock12(start),color=p.text)}}
                    Column(Modifier.weight(1f)){Text("종료",color=p.muted,fontSize=12.sp);OutlinedButton(onClick={picker="end"},modifier=Modifier.fillMaxWidth()){Text(formatClock12(end),color=p.text)}}
                }
                Text("적용 방식",fontSize=12.sp,color=p.muted)
                ChoicePicker(listOf("매주 반복" to "weekly","해당 날짜만" to "once","직접 설정" to "custom"),mode,p,Modifier.fillMaxWidth()){mode=it}
                if(mode=="custom") {
                    Text("반복 요일 선택",color=p.text,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        listOf("월","화","수","목","금","토","일").forEachIndexed{index,label->
                            val d=index+1
                            Box(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(9.dp)).background(if(d in weekdays)p.accent else p.bg).clickable{weekdays=if(d in weekdays)weekdays-d else weekdays+d}.padding(vertical=12.dp),contentAlignment=Alignment.Center){
                                Text(label,color=if(d in weekdays)p.onAccent else p.text,fontSize=12.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        OutlinedButton(onClick={weekdays=(1..5).toSet()}){Text("평일",color=p.text)}
                        OutlinedButton(onClick={weekdays=setOf(6,7)}){Text("주말",color=p.text)}
                        OutlinedButton(onClick={weekdays=(1..7).toSet()}){Text("매일",color=p.text)}
                    }
                }
                if(error.isNotEmpty())Text(error,color=Color(0xFFAA5544),fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                    PrimaryButton(p,"계획 저장"){if(subject<0){error="먼저 학습과목을 추가해 주세요."}else {
                        val saved=when(mode){
                            "custom"->store.addWeeklyPlans(weekdays,subject,start,end)
                            "once"->store.addPlan(date.dayOfWeek.value,date.toString(),subject,start,end,"replace")
                            else->store.addWeeklyPlans(setOf(date.dayOfWeek.value),subject,start,end)
                        }
                        if(saved){editing=false;refreshKey++;ReminderScheduler.schedule(this@MainActivity)}else error="시간이 겹치거나 범위가 올바르지 않아."
                    }}
                }
            }}
            if(picker.isNotBlank()) item {
                ClockPickerDialog(if(picker=="start")start else end,p,allowMidnight=picker=="end",onDismiss={picker=""},onConfirm={value->if(picker=="start")start=value else end=value;picker=""})
            }
        }
    }
    @Composable private fun CalendarPage(p:Palette,events:List<CalendarEvent>,records:List<StudyRecord>){
        var selected by remember {mutableStateOf(LocalDate.now())};var month by remember {mutableStateOf(LocalDate.now().withDayOfMonth(1))}
        var expanded by remember {mutableStateOf(false)};var form by remember {mutableStateOf(false)}
        var name by remember {mutableStateOf("")};var type by remember {mutableStateOf("exam")};var time by remember {mutableStateOf("09:00")};var repeat by remember {mutableStateOf("none")}
        val exam=events.filter{it.type=="exam"&&it.repeat=="none"&&LocalDate.parse(it.date)>=LocalDate.now()}.minByOrNull{it.date}
        val days=month.lengthOfMonth();val pad=month.dayOfWeek.value%7
        val dayEvents=store.eventsFor(selected)
        var horizontal by remember {mutableFloatStateOf(0f)};var vertical by remember {mutableFloatStateOf(0f)}
        LazyColumn(contentPadding=PaddingValues(13.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Heading(p,"학습 캘린더","CALENDAR");TextButton(onClick={selected=LocalDate.now();month=selected.withDayOfMonth(1);expanded=true}){Text("오늘",color=p.accent)}}}
            if(exam!=null) item {Card(p){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("다가오는 시험",fontSize=11.sp,color=p.muted);Text(exam.name,color=p.text,fontWeight=FontWeight.SemiBold)};Text("D-${ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(exam.date))}",color=p.accent,fontWeight=FontWeight.Bold,fontSize=19.sp)}}}

            item {
                var gestureDistance by remember{mutableFloatStateOf(0f)}
                Box(Modifier.pointerInput(month,selected,expanded){
                    detectHorizontalDragGestures(onDragEnd={
                        if(kotlin.math.abs(gestureDistance)>55f) {
                            if(expanded) {
                                selected=selected.plusWeeks(if(gestureDistance<0)1L else -1L)
                                month=selected.withDayOfMonth(1)
                            }else{
                                month=month.plusMonths(if(gestureDistance<0)1 else -1)
                            }
                        }
                        gestureDistance=0f
                    }){change,amount->change.consume();gestureDistance+=amount}
                }){
                    Column(Modifier.fillMaxWidth().animateContentSize(animationSpec=tween(280))){
                        Card(p){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                IconButton(onClick={
                                    if(expanded){selected=selected.minusWeeks(1);month=selected.withDayOfMonth(1)}
                                    else month=month.minusMonths(1)
                                }){Icon(Icons.Default.ChevronLeft,null,tint=p.text)}
                                Text(if(expanded)"${selected.monthValue}월 ${selected.dayOfMonth}일 주간" else "${month.year}년 ${month.monthValue}월",fontWeight=FontWeight.SemiBold,color=p.text)
                                IconButton(onClick={
                                    if(expanded){selected=selected.plusWeeks(1);month=selected.withDayOfMonth(1)}
                                    else month=month.plusMonths(1)
                                }){Icon(Icons.Default.ChevronRight,null,tint=p.text)}
                            }
                            Row(Modifier.fillMaxWidth()){listOf("일","월","화","수","목","금","토").forEachIndexed{i,day->
                                Text(day,color=if(i==0)Color(0xFFBE6262) else if(i==6)Color(0xFF6388B0) else p.muted,fontSize=12.sp,modifier=Modifier.weight(1f),textAlign=TextAlign.Center)
                            }}
                            val weekStart=selected.minusDays((selected.dayOfWeek.value%7).toLong())
                            val gridDays=if(expanded)7 else ((pad+days+6)/7)*7
                            repeat(gridDays/7){row->
                                Row(Modifier.fillMaxWidth()){
                                    repeat(7){col->
                                        val dd=if(expanded)weekStart.plusDays(col.toLong()) else month.plusDays((row*7+col-pad).toLong())
                                        val valid=expanded || dd.month==month.month
                                        val holiday=koreanSolarHoliday(dd)
                                        val eventsOn=if(valid)store.eventsFor(dd) else emptyList()
                                        val label=holiday?:eventsOn.firstOrNull()?.let{if(it.type=="rest")"휴식" else it.name}
                                        val tone=if(holiday!=null||col==0)Color(0xFFBE6262) else if(col==6)Color(0xFF6388B0) else p.text
                                        Box(Modifier.weight(1f).height(if(expanded)70.dp else 77.dp).padding(2.dp)){
                                            if(valid){
                                                Column(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(if(dd==selected)p.accent else p.bg).clickable{selected=dd;month=dd.withDayOfMonth(1);expanded=true;form=false}.padding(4.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                                                    Text("${dd.dayOfMonth}",color=if(dd==selected)p.onAccent else tone,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                                                    if(label!=null){
                                                        Text(label,fontSize=9.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(dd==selected)p.onAccent else if(holiday!=null)Color(0xFFBE6262) else p.accent)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if(expanded){TextButton(onClick={expanded=false;form=false}){Text("⌄ 상세 닫기",color=p.muted)}}
                        }
                    }
                }
            }
            item {AnimatedVisibility(visible=expanded,enter=slideInVertically(animationSpec=tween(300),initialOffsetY={it})+fadeIn(),exit=slideOutVertically(animationSpec=tween(260),targetOffsetY={it})+fadeOut()){Card(p){
                Column(Modifier.fillMaxWidth().pointerInput(selected){detectHorizontalDragGestures(onDragEnd={if(horizontal>50){selected=selected.minusDays(1)}else if(horizontal< -50){selected=selected.plusDays(1)};month=selected.withDayOfMonth(1);horizontal=0f}){change,drag->change.consume();horizontal+=drag}}.pointerInput(selected){detectVerticalDragGestures(onDragEnd={if(vertical>90)expanded=false;vertical=0f}){change,drag->if(drag>0)vertical+=drag}}){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("${selected.monthValue}월 ${selected.dayOfMonth}일",color=p.muted,fontSize=12.sp);Text("선택한 날짜의 일정",fontWeight=FontWeight.SemiBold,color=p.text)};PrimaryButton(p,"+ 추가"){form=!form}}
                    Spacer(Modifier.height(12.dp))
                    if(dayEvents.isEmpty()){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.EventAvailable,null,tint=p.accent);Text("등록된 일정이 없어.",color=p.muted)}}
                    else dayEvents.forEach{ev->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("${if(ev.type=="rest")"☕" else if(ev.type=="exam")"⚑" else "▣"} ${ev.name} · ${ev.clock}",fontSize=12.sp,color=p.text);IconButton(onClick={store.deleteEvent(ev.id);refreshKey++}){Icon(Icons.Default.Delete,null,tint=p.muted)}}}
                    Spacer(Modifier.height(10.dp))
                    val studied=records.filter{Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate()==selected}.sumOf{it.seconds}
                    val target=store.targetFor(selected)
                    Column(Modifier.fillMaxWidth().background(p.bg,RoundedCornerShape(10.dp)).padding(12.dp)){Text("이날의 공부 기록",color=p.text,fontWeight=FontWeight.SemiBold);Text("총 공부 ${formatClock(studied)}",color=p.muted,fontSize=12.sp);Text(if(store.isRest(selected))"휴식일" else if(target==0L)"목표 없음" else "달성률 ${studied*100/target}%",color=p.muted,fontSize=12.sp)}
                }
            }}}
            if(expanded&&form) item {Card(p){Text("새 일정 추가",fontWeight=FontWeight.Bold,color=p.text)
                ChoicePicker(listOf("시험" to "exam","개인공부" to "study","휴식" to "rest"),type,p){type=it}
                if(type!="rest") TextField(value=name,onValueChange={name=it},label={Text("일정 이름")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                if(type!="rest") TextField(value=time,onValueChange={time=it},label={Text("시각 (HH:MM)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                ChoicePicker(listOf("반복 없음" to "none","매일" to "daily","매주" to "weekly","매월" to "monthly"),repeat,p){repeat=it}
                PrimaryButton(p,"일정 저장",type=="rest"||name.isNotBlank()){store.addEvent(selected.toString(),if(type=="rest")"휴식" else name,type,time,repeat);form=false;name="";refreshKey++}
            }}
        }
    }
    @Composable private fun StatsPage(p:Palette,subjects:List<Subject>,records:List<StudyRecord>){
        var mode by remember {mutableIntStateOf(0)};var offset by remember {mutableIntStateOf(0)}
        val now=LocalDate.now()
        val selected=when(mode){0->now.plusDays(offset.toLong());1->now.plusWeeks(offset.toLong());else->now.plusMonths(offset.toLong())}
        val start=when(mode){0->selected;1->selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));else->selected.withDayOfMonth(1)}
        val end=when(mode){0->start;1->start.plusDays(6);else->start.withDayOfMonth(start.lengthOfMonth())}
        val vals=store.totalsIn(start,end);val total=vals.values.sum()
        val shares=subjects.mapNotNull{s->val count=vals[s.id]?:0L;if(count>0)s to count else null}
        LazyColumn(contentPadding=PaddingValues(13.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item {Heading(p,"학습 통계","STATISTICS")}
            item {Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(5.dp)){listOf("일간","주간","월간").forEachIndexed{i,s->Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if(mode==i)p.accent else Color.Transparent).clickable{mode=i;offset=0}.padding(10.dp),contentAlignment=Alignment.Center){Text(s,color=if(mode==i)p.onAccent else p.text)}}}}
            item {Card(p){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={offset--}){Icon(Icons.Default.ChevronLeft,null,tint=p.text)};Text(if(mode==0)"$selected" else "$start ~ $end",color=p.text);IconButton(onClick={offset++}){Icon(Icons.Default.ChevronRight,null,tint=p.text)}}
                Text(if(mode==0)"총 공부 시간" else if(mode==1)"주간 누적 공부 시간" else "월간 누적 공부 시간",color=p.muted,fontSize=12.sp)
                Text(formatClock(total),color=p.text,fontWeight=FontWeight.Bold,fontSize=27.sp,fontFamily=FontFamily.Monospace)
                if(mode==0){
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
                        Box(Modifier.size(176.dp),contentAlignment=Alignment.Center){
                            Canvas(Modifier.size(150.dp)){val stroke=20.dp.toPx();var angle=-90f; if(total==0L)drawArc(p.bg,0f,360f,false,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke));shares.forEachIndexed{i,pair->val sweep=pair.second.toFloat()/total*360f;drawArc(colorSet[i%colorSet.size],angle,sweep,false,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke));angle+=sweep}}
                            Text(formatClock(total),fontSize=15.sp,color=p.text,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold)
                        }
                        if(shares.isEmpty())Text("아직 공부 기록이 없어.",color=p.muted,fontSize=12.sp)
                        else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            shares.forEachIndexed{i,pair->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                    Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
                                        Box(Modifier.size(10.dp).background(colorSet[i%colorSet.size],RoundedCornerShape(3.dp)))
                                        Spacer(Modifier.width(8.dp))
                                        Text(pair.first.name,color=p.text,fontSize=13.sp)
                                    }
                                    Text("${formatClock(pair.second)} · ${pair.second*100/total}%",color=p.muted,fontSize=12.sp)
                                }
                            }
                        }
                    }
                }else{
                    if(mode==1){val days=(0..6).map{start.plusDays(it.toLong())};BarSet(p,days.map{store.totalsFor(it).values.sum()},days.map{listOf("월","화","수","목","금","토","일")[it.dayOfWeek.value-1]})}
                    shares.forEachIndexed{i,pair->Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(pair.first.name,color=p.text,fontSize=13.sp);Text("${formatClock(pair.second)} · ${(pair.second*100.0/total).toInt()}%",color=p.muted,fontSize=11.sp)};LinearProgressIndicator(progress={pair.second.toFloat()/total},modifier=Modifier.fillMaxWidth().height(8.dp),color=colorSet[i%colorSet.size],trackColor=p.bg)}}
                    if(mode==2){Text("월별 학습 추세",color=p.text,fontWeight=FontWeight.SemiBold);val months=(0..10).map{start.minusMonths((10-it).toLong())};BarSet(p,months.map{store.totalsIn(it.withDayOfMonth(1),it.withDayOfMonth(it.lengthOfMonth())).values.sum()},months.map{"${it.monthValue}"})}
                }
            }}
        }
    }
    @Composable private fun BarSet(p:Palette,values:List<Long>,labels:List<String>){val max=(values.maxOrNull()?:0L).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(155.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom){values.forEachIndexed{i,v->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Bottom){Box(Modifier.fillMaxWidth().height((110f*v/max).coerceAtLeast(if(v>0)3f else 0f).dp).background(if(i==values.lastIndex)Color(0xFFC7A371) else p.accent,RoundedCornerShape(topStart=3.dp,topEnd=3.dp)));Text(labels[i],color=p.muted,fontSize=10.sp)}}}
    }
    @Composable private fun SettingsPage(p:Palette,subjects:List<Subject>,records:List<StudyRecord>,theme:Int,onTheme:(Int)->Unit){
        var subsection by remember {mutableStateOf("")};var input by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var chosenId by remember{mutableLongStateOf(subjects.firstOrNull()?.id?:-1L)}
        var focus by remember{mutableStateOf(appPrefs.getBoolean("focus",true))};var floating by remember{mutableStateOf(appPrefs.getBoolean("floating",true))}
        var morningEnabled by remember{mutableStateOf(appPrefs.getBoolean("morning_enabled",true))}
        var morningTime by remember{mutableStateOf(appPrefs.getString("morning_time","08:00")?:"08:00")}
        var planAlertEnabled by remember{mutableStateOf(appPrefs.getBoolean("plan_reminders",true))}
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)runCatching{contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(store.snapshot())}}.onSuccess{note="백업 파일을 내보냈어."}.onFailure{note="내보내기 실패: ${it.message}"}}
        val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)runCatching{val str=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("파일 읽기 실패");store.restore(str);refreshKey++}.onSuccess{note="복원 완료"}.onFailure{note="복원 실패: ${it.message}"}}
        LazyColumn(contentPadding=PaddingValues(13.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item {Row(verticalAlignment=Alignment.CenterVertically){if(subsection.isNotBlank())IconButton(onClick={subsection=""}){Icon(Icons.Default.ArrowBack,null,tint=p.text)};Heading(p,if(subsection.isBlank())"환경설정" else subsection,"SETTINGS")}}
            if(subsection.isBlank()){
                item {Card(p){Text("학습 관리",color=p.muted,fontSize=12.sp);listOf("과목 관리","공부 기록 관리").forEach{SettingsLink(p,it){subsection=it}}}}
                item {Card(p){Text("사용 환경",color=p.muted,fontSize=12.sp);listOf("테마 설정","집중 모드","플로팅 타이머","응원 알림","공부 시작 알림","응원 문구 관리").forEach{SettingsLink(p,it){subsection=it}}}}
                item {Card(p){Text("데이터 관리",color=p.muted,fontSize=12.sp);SettingsLink(p,"백업 및 복원"){subsection="백업 및 복원"}}}
            }else if(subsection=="과목 관리"){
                item {Card(p){subjects.forEach{s->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(s.name,color=p.text);IconButton(onClick={if(!store.deleteSubject(s.id))note="기록이나 계획이 있는 과목은 삭제할 수 없어." else refreshKey++}){Icon(Icons.Default.Delete,null,tint=p.muted)}}};TextField(value=input,onValueChange={input=it},label={Text("새 과목")},modifier=Modifier.fillMaxWidth());PrimaryButton(p,"추가"){store.addSubject(input);input="";refreshKey++}}}
            }else if(subsection=="테마 설정"){
                item {Card(p){listOf("세이지 그린","다크 올리브","슬레이트 블루","웜 베이지","뮤트 라벤더").forEachIndexed{i,s->Row(Modifier.fillMaxWidth().clickable{onTheme(i)}.padding(9.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=theme==i,onClick={onTheme(i)});Text(s,color=p.text)}};Text("사용자 지정 테마는 다음 개발 단계에 연결 예정",color=p.muted,fontSize=12.sp)}}
            }else if(subsection=="집중 모드"){
                item {Card(p){Row(verticalAlignment=Alignment.CenterVertically){Checkbox(focus,onCheckedChange={focus=it;appPrefs.edit().putBoolean("focus",it).apply()});Text("공부 중 방해 금지 자동 실행",color=p.text,fontSize=14.sp)};Text("권한 승인 시 작동해. 공부 종료 시 앱이 켠 상태만 복원해.",color=p.muted,fontSize=12.sp);PrimaryButton(p,"방해 금지 권한 설정"){startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))}}}
            }else if(subsection=="플로팅 타이머"){
                item {Card(p){Row(verticalAlignment=Alignment.CenterVertically){Checkbox(floating,onCheckedChange={floating=it;appPrefs.edit().putBoolean("floating",it).apply()});Text("팝업 타이머 사용",color=p.text)};PrimaryButton(p,"화면 위에 표시 권한"){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}}}
            }else if(subsection=="응원 알림"){
                item {Card(p){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Checkbox(morningEnabled,onCheckedChange={morningEnabled=it;appPrefs.edit().putBoolean("morning_enabled",it).apply();ReminderScheduler.schedule(this@MainActivity)})
                        Text("매일 아침 응원 알림",color=p.text)
                    }
                    TextField(value=morningTime,onValueChange={morningTime=it},label={Text("알림 시간 (HH:MM)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    PrimaryButton(p,"시간 저장",runCatching{LocalTime.parse(morningTime)}.isSuccess){
                        appPrefs.edit().putString("morning_time",morningTime).apply()
                        ReminderScheduler.schedule(this@MainActivity)
                        note="아침 알림 시간을 저장했어."
                    }
                    Text("주말 포함 매일 1회 · 기본 문구와 사용자 문구에서 랜덤 선택",color=p.muted,fontSize=12.sp)
                }}
            }else if(subsection=="공부 시작 알림"){
                item {Card(p){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Checkbox(planAlertEnabled,onCheckedChange={planAlertEnabled=it;appPrefs.edit().putBoolean("plan_reminders",it).apply();ReminderScheduler.schedule(this@MainActivity)})
                        Text("공부 계획 10분 전 알림",color=p.text)
                    }
                    Text("시간표의 시작 10분 전에 안내해요. 이미 해당 과목을 공부 중이라면 생략합니다. 휴대폰 배터리 설정에 따라 알림 시각이 늦어질 수 있습니다.",color=p.muted,fontSize=12.sp)
                }}
            }else if(subsection=="백업 및 복원"){
                item {Card(p){Text("자동 백업: 매달 첫 앱 실행 시",color=p.text);Text("6개월 지난 자동 백업만 삭제 · 원본 기록 영구 보관",color=p.muted,fontSize=12.sp);PrimaryButton(p,"백업 파일 내보내기"){export.launch("chunsik-study-${LocalDate.now()}.json")};OutlinedButton(onClick={import.launch(arrayOf("application/json","text/plain"))}){Text("백업 파일 가져오기")};Text("내부 자동 백업: ${Backups(this@MainActivity,store).snapshots().size}개",color=p.muted,fontSize=12.sp)}}
            }else if(subsection=="공부 기록 관리"){
                item {Card(p){Text("수동 기록 추가",color=p.text,fontWeight=FontWeight.Bold);SubjectPicker(subjects,chosenId,p){chosenId=it};TextField(value=input,onValueChange={input=it},label={Text("공부 시간 (분)")},keyboardOptions=KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),modifier=Modifier.fillMaxWidth());PrimaryButton(p,"오늘 기록에 추가",input.toLongOrNull()?.let{it>0}==true){val minutes=input.toLongOrNull()?:0;if(minutes>0){val now=System.currentTimeMillis();store.addRecord(chosenId,now-minutes*60000,now,minutes*60);refreshKey++;input=""}}}}
                items(records.take(30).size){i->val r=records[i];Card(p){Text("${r.subject} · ${formatClock(r.seconds)}",color=p.text,fontSize=13.sp);Row{OutlinedButton(onClick={store.deleteRecord(r.id);refreshKey++}){Text("삭제")};Spacer(Modifier.width(8.dp));OutlinedButton(onClick={store.editRecord(r.id,(r.seconds-60).coerceAtLeast(1));refreshKey++}){Text("1분 줄이기")}}}}
            }else if(subsection=="응원 문구 관리"){
                item {Card(p){
                    Text("기본 문구 ${QuoteStore.defaults.size}개 · 사용자 문구 ${QuoteStore.custom(this@MainActivity).size}개",color=p.text)
                    QuoteStore.custom(this@MainActivity).forEach{quote->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                            Text(quote,color=p.text,modifier=Modifier.weight(1f))
                            IconButton(onClick={QuoteStore.remove(this@MainActivity,quote);refreshKey++}){Icon(Icons.Default.Delete,null,tint=p.muted)}
                        }
                    }
                    TextField(value=input,onValueChange={input=it},label={Text("응원 문구 직접 추가")},modifier=Modifier.fillMaxWidth())
                    PrimaryButton(p,"문구 추가",input.isNotBlank()){
                        if(QuoteStore.add(this@MainActivity,input)){input="";refreshKey++;note="문구를 추가했어."}else note="중복이거나 너무 긴 문구야."
                    }
                }}
            }
            if(note.isNotBlank())item {Text(note,color=p.muted,fontSize=12.sp)}
        }
    }
    @Composable private fun SettingsLink(p:Palette,text:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=11.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(text,color=p.text,fontSize=14.sp);Icon(Icons.Default.ChevronRight,null,tint=p.muted)}}
    @Composable private fun SubjectPicker(subjects:List<Subject>,chosen:Long,p:Palette,modifier:Modifier=Modifier,onChange:(Long)->Unit){var expanded by remember{mutableStateOf(false)};Box(modifier){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(subjects.firstOrNull{it.id==chosen}?.name?:"과목 선택",color=p.text)};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){subjects.forEach{s->DropdownMenuItem(text={Text(s.name)},onClick={onChange(s.id);expanded=false})}}}}
    @Composable private fun MinutePicker(chosen:Int,options:List<Int>,p:Palette,modifier:Modifier=Modifier,onChange:(Int)->Unit){var expanded by remember{mutableStateOf(false)};Box(modifier){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(formatMinute(chosen),color=p.text)};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){Box(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){Column{options.forEach{x->DropdownMenuItem(text={Text(formatMinute(x))},onClick={onChange(x);expanded=false})}}}}}}

    @Composable private fun ClockPickerDialog(initial:Int,p:Palette,allowMidnight:Boolean,onDismiss:()->Unit,onConfirm:(Int)->Unit){
        var am by remember(initial){mutableStateOf(initial<720 || initial==1440)}
        // 12-hour clock; 00:xx is 12 AM and 12:xx is 12 PM.
        var h by remember(initial){mutableIntStateOf(((initial/60)%12).let{if(it==0)12 else it})}
        var minute by remember(initial){mutableIntStateOf(initial%60)}
        var entry by remember{mutableStateOf(false)}
        var typed by remember{mutableStateOf("")}
        var lastMinute by remember(initial){mutableIntStateOf(initial)}
        val value=if(allowMidnight&&am&&h==12&&minute==0&&initial==1440)1440 else ((h%12)+(if(am)0 else 12))*60+minute
        AlertDialog(onDismissRequest=onDismiss,title={Text("시간 선택",color=p.text)},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(formatClock12(value),fontSize=22.sp,color=p.text,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    WheelColumn(listOf("오전","오후"),if(am)0 else 1,p,Modifier.weight(1.2f)){am=it==0}
                    WheelColumn((1..12).map{"%02d".format(it)},h-1,p,Modifier.weight(1f)){h=it+1}
                    Column(Modifier.weight(1f)){
                        WheelColumn((0..5).map{"%02d".format(it*10)},minute/10,p,Modifier.fillMaxWidth(),onTap={typed="%02d".format(minute);entry=true}){minute=it*10}
                        TextButton(onClick={typed="%02d".format(minute);entry=true},modifier=Modifier.fillMaxWidth()){Text("분 직접 입력",fontSize=10.sp,color=p.accent)}
                    }
                }
                if(allowMidnight)TextButton(onClick={am=true;h=12;minute=0;lastMinute=1440},modifier=Modifier.fillMaxWidth()){Text("하루 종료 24:00",color=p.accent)}
                if(entry) {
                    OutlinedTextField(value=typed,onValueChange={if(it.length<=2&&it.all(Char::isDigit))typed=it},label={Text("분 (00~59)")},keyboardOptions=KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),singleLine=true)
                    TextButton(onClick={typed.toIntOrNull()?.let{if(it in 0..59){minute=it;entry=false}}}){Text("입력 적용")}
                }
            }
        },confirmButton={TextButton(onClick={onConfirm(if(allowMidnight&&lastMinute==1440&&am&&h==12&&minute==0)1440 else ((h%12)+(if(am)0 else 12))*60+minute)}){Text("확인",color=p.accent)}},dismissButton={TextButton(onClick=onDismiss){Text("취소",color=p.muted)}} ,containerColor=p.card)
    }
    @Composable private fun WheelColumn(options:List<String>,selected:Int,p:Palette,modifier:Modifier=Modifier,onTap:(()->Unit)?=null,onSelect:(Int)->Unit){
        var drag by remember{mutableFloatStateOf(0f)}
        Column(modifier.pointerInput(selected,options.size){detectVerticalDragGestures(onDragEnd={if(kotlin.math.abs(drag)>12f){onSelect((selected+(if(drag<0)1 else -1)+options.size)%options.size)};drag=0f}){change,amount->change.consume();drag+=amount}},horizontalAlignment=Alignment.CenterHorizontally){
            IconButton(onClick={onSelect((selected-1+options.size)%options.size)}){Icon(Icons.Default.KeyboardArrowUp,null,tint=p.muted)}
            Text(options[selected],fontWeight=FontWeight.Bold,color=p.text,fontSize=17.sp,modifier=Modifier.clip(RoundedCornerShape(9.dp)).background(p.bg).clickable{if(onTap!=null)onTap() else onSelect((selected+1)%options.size)}.padding(horizontal=12.dp,vertical=15.dp))
            IconButton(onClick={onSelect((selected+1)%options.size)}){Icon(Icons.Default.KeyboardArrowDown,null,tint=p.muted)}
        }
    }
    @Composable private fun ChoicePicker(options:List<Pair<String,String>>,chosen:String,p:Palette,modifier:Modifier=Modifier,onChange:(String)->Unit){var expanded by remember{mutableStateOf(false)};Box(modifier){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(options.firstOrNull{it.second==chosen}?.first?:"선택",color=p.text)};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{(label,value)->DropdownMenuItem(text={Text(label)},onClick={onChange(value);expanded=false})}}}}
}
private fun formatMinute(minutes:Int):String="%02d:%02d".format(minutes/60,minutes%60)
private fun formatClock12(value:Int):String = if(value==1440)"오전 12:00 (익일)" else "%s %02d:%02d".format(if(value/60<12)"오전" else "오후", ((value/60)%12).let{if(it==0)12 else it},value%60)

/** Local Korean holiday cache for 2026–2027; solar fixed dates are used in other years. */
private fun koreanSolarHoliday(date:LocalDate):String? {
    val dated=when(date.year){
        2026->mapOf(
            "02-16" to "설 연휴","02-17" to "설날","02-18" to "설 연휴",
            "03-02" to "삼일절 대체공휴일",
            "05-01" to "노동절","05-24" to "부처님오신날","05-25" to "대체공휴일",
            "06-03" to "전국동시지방선거",
            "08-17" to "광복절 대체공휴일",
            "09-24" to "추석 연휴","09-25" to "추석","09-26" to "추석 연휴",
            "10-05" to "개천절 대체공휴일"
        )
        2027->mapOf(
            "02-06" to "설 연휴","02-07" to "설날","02-08" to "설 연휴","02-09" to "설날 대체공휴일",
            "05-01" to "노동절","05-03" to "노동절 대체공휴일","05-13" to "부처님오신날",
            "07-17" to "제헌절","07-19" to "제헌절 대체공휴일",
            "08-16" to "광복절 대체공휴일",
            "09-14" to "추석 연휴","09-15" to "추석","09-16" to "추석 연휴",
            "10-04" to "개천절 대체공휴일","10-11" to "한글날 대체공휴일",
            "12-27" to "성탄절 대체공휴일"
        )
        else->emptyMap()
    }
    return dated["%02d-%02d".format(date.monthValue,date.dayOfMonth)] ?: when(date.monthValue to date.dayOfMonth){
    1 to 1 -> "신정"
    3 to 1 -> "삼일절"
    5 to 5 -> "어린이날"
    6 to 6 -> "현충일"
    8 to 15 -> "광복절"
    10 to 3 -> "개천절"
    10 to 9 -> "한글날"
    12 to 25 -> "성탄절"
    else -> null
    }
}
