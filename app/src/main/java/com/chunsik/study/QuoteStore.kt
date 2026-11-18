package com.chunsik.study

import android.content.Context
import org.json.JSONArray

object QuoteStore {
    val defaults=listOf(
        "오늘도 할 수 있다.","오늘의 노력이 내일을 만든다.","꾸준함은 재능을 이긴다.",
        "조금씩, 그러나 계속.","오늘의 한 시간이 쌓인다.","포기하지 않는 하루.","어제보다 한 걸음 더."
    )
    fun custom(context:Context):List<String> = runCatching{
        val data=context.getSharedPreferences("app",0).getString("quotes","[]")?:"[]"
        val arr=JSONArray(data)
        (0 until arr.length()).map{arr.getString(it)}.filter{it.isNotBlank()}
    }.getOrDefault(emptyList())
    fun all(context:Context)=defaults+custom(context)
    fun add(context:Context,value:String):Boolean {
        val text=value.trim()
        if(text.isBlank()||text.length>160||all(context).contains(text))return false
        save(context,custom(context)+text)
        return true
    }
    fun remove(context:Context,value:String){save(context,custom(context).filterNot{it==value})}
    private fun save(context:Context,values:List<String>){
        context.getSharedPreferences("app",0).edit().putString("quotes",JSONArray(values).toString()).apply()
    }
}
