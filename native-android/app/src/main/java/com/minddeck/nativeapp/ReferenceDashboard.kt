package com.minddeck.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.json.JSONArray

/** Reference-inspired native dashboard. Every displayed study metric uses real local state. */
@Composable
internal fun ReferenceDashboard(state: StudyUiState, onCreate: () -> Unit, onStudy: () -> Unit, onFocus: () -> Unit, onAccount: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("dashboard_planner", 0)
    var tasks by remember { mutableStateOf(runCatching {
        val array = JSONArray(preferences.getString("tasks", "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList<String>())) }
    var completed by remember { mutableStateOf(preferences.getStringSet("completed", emptySet())!!.toSet()) }
    var notes by remember { mutableStateOf(preferences.getString("notes", "") ?: "") }
    var draft by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(30_000) } }
    val today = LocalDate.now()
    val violet = Color(0xFF9966FF)
    val due = state.cards.count { it.due <= System.currentTimeMillis() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050611), Color(0xFF18112D), Color(0xFF080B17)))),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(when(now.hour) { in 5..11 -> "Good Morning,"; in 12..16 -> "Good Afternoon,"; else -> "Good Evening," }, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Text(state.profile.name.substringBefore(' ').ifBlank { "Student" }, color = violet, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Focus · Plan · Learn · Remember", fontSize = 12.sp, color = Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 34.sp, fontWeight = FontWeight.ExtraLight)
                    Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM")), color = Muted, fontSize = 11.sp)
                    TextButton(onClick = onAccount) { Text("Profile", color = violet, fontSize = 11.sp) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MiniStat("$due", "DUE", violet, Modifier.weight(1f))
                MiniStat("${state.cards.size}", "CARDS", Color(0xFF4D8EFF), Modifier.weight(1f))
                MiniStat("${state.focusSeconds / 60}m", "FOCUS", Color(0xFF35D6A0), Modifier.weight(1f))
            }
        }
        item {
            DashboardPanel("Calendar") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                }
                Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
                val offset = month.atDay(1).dayOfWeek.value - 1
                repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { weekday ->
                            val day = week * 7 + weekday - offset + 1
                            val valid = day in 1..month.lengthOfMonth()
                            val selected = valid && month.atDay(day) == today
                            Box(Modifier.weight(1f).height(38.dp).background(if (selected) violet else Color.Transparent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Text(if (valid) day.toString() else "", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardPanel("Today's Plan", Modifier.weight(1f)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = Muted, fontSize = 12.sp)
                TextButton(onClick = onStudy, contentPadding = PaddingValues(0.dp)) { Text("●  $due card reviews", color = violet, fontSize = 11.sp) }
                TextButton(onClick = onFocus, contentPadding = PaddingValues(0.dp)) { Text("●  Focus session", color = Color(0xFF6FAEFF), fontSize = 11.sp) }
                TextButton(onClick = onCreate, contentPadding = PaddingValues(0.dp)) { Text("●  Create AI deck", color = Color(0xFFEFB267), fontSize = 11.sp) }
            }
            DashboardPanel("Focus Timer", Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { if (state.timer.duration > 0) state.timer.remaining.toFloat() / state.timer.duration else 0f }, modifier = Modifier.size(120.dp), color = violet, trackColor = Color(0xFF292145), strokeWidth = 6.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d:%02d".format(state.timer.remaining / 60, state.timer.remaining % 60), fontSize = 23.sp, fontWeight = FontWeight.Light)
                        Text(if (state.timer.running) "FOCUSING" else "FOCUS", color = Muted, fontSize = 9.sp)
                    }
                }
                Button(onClick = onFocus, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.buttonColors(containerColor = violet)) { Text(if(state.timer.running) "Controls" else "Start", color = Color.White, fontSize = 11.sp) }
            }
          }
        }
        item {
            DashboardPanel("My Tasks") {
                TextButton(onClick = { adding = true }) { Text("+ Add task", color = violet) }
                if (tasks.isEmpty()) Text("Add your first study task.", color = Muted, fontSize = 13.sp)
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task in completed, onCheckedChange = { checked ->
                            completed = if (checked) completed + task else completed - task
                            preferences.edit().putStringSet("completed", completed).apply()
                        })
                        Text(task, Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = {
                            tasks = tasks - task; completed = completed - task
                            preferences.edit().putString("tasks", JSONArray(tasks).toString()).putStringSet("completed", completed).apply()
                        }) { Text("×", color = Muted) }
                    }
                }
            }
        }
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardPanel("Upcoming", Modifier.weight(1f)) {
                val nextDecks = state.cards.groupBy { it.deck }.entries.sortedBy { entry -> entry.value.minOf { it.due } }.take(3)
                if (nextDecks.isEmpty()) Text("Create a deck to see reviews.", color = Muted, fontSize = 10.sp)
                nextDecks.forEach { entry ->
                    TextButton(onClick = onStudy) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.key, color = Ink, fontSize = 10.sp, maxLines = 1)
                            Text("${entry.value.count { it.due <= System.currentTimeMillis() }} due · ${entry.value.size} cards", color = Muted, fontSize = 8.sp)
                        }
                    }
                }
            }
            DashboardPanel("Quick Notes", Modifier.weight(1f)) {
                OutlinedTextField(value = notes, onValueChange = {
                    notes = it.take(5000); preferences.edit().putString("notes", notes).apply()
                }, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 10.sp), placeholder = { Text("Formula, doubt or reminder…", fontSize = 9.sp) }, minLines = 5, shape = RoundedCornerShape(12.dp))
                Text("Keep going. You're doing great.", color = violet, fontSize = 9.sp)
            }
          }
        }
        item {
            DashboardPanel("Study progress") {
                Text("${state.focusSeconds / 60} focus minutes completed", color = Ink)
                Text("${tasks.count { it in completed }} of ${tasks.size} tasks complete", color = Muted, fontSize = 13.sp)
                LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else tasks.count { it in completed }.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth(), color = violet)
            }
        }
        item {
            Surface(onClick=onCreate,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color.Transparent,border=BorderStroke(1.dp,violet.copy(alpha=.6f))) {
                Row(Modifier.background(Brush.linearGradient(listOf(Color(0xFF302060),Color(0xFF182248),Color(0xFF153842)))).padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("✦",color=Color.White,fontSize=24.sp,modifier=Modifier.background(violet,RoundedCornerShape(12.dp)).padding(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal=14.dp)) {Text("Create with MindDeck AI",fontWeight=FontWeight.Bold);Text("Turn a chapter, note or photo into revision cards.",color=Muted,fontSize=10.sp)}
                    Text("›",color=violet,fontSize=28.sp)
                }
            }
        }
        item { Text(when { state.cloudSyncing -> "Syncing securely with Supabase…"; state.cloudReady -> "Supabase cloud library synced"; state.user != null -> "Supabase connected · offline changes stay safe"; else -> "Offline mode · sign in to enable Supabase sync" },Modifier.fillMaxWidth(),color=Muted,fontSize=10.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add study task") }, text = {
        OutlinedTextField(value = draft, onValueChange = { draft = it.take(200) }, label = { Text("Task") })
    }, confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
        val task = draft.trim()
        if (task !in tasks) tasks = tasks + task
        preferences.edit().putString("tasks", JSONArray(tasks).toString()).apply()
        draft = ""; adding = false
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
}

@Composable
private fun DashboardPanel(title: String, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, color = Color(0xE6111628), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF303049)), shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun MiniStat(value: String,label: String,color: Color,modifier: Modifier=Modifier) {
    Surface(modifier=modifier,color=Color(0xE6111628),shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,color.copy(alpha=.45f))) {
        Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(value,fontWeight=FontWeight.Bold,fontSize=18.sp)
            Text(label,color=Muted,fontSize=8.sp,letterSpacing=1.sp)
        }
    }
}
