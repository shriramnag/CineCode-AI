package com.shriram.cinecode.data.jarvis

import android.content.Context
import android.content.SharedPreferences
import com.shriram.cinecode.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent store for Jarvis multi-step missions and named reusable agent teams (`bz6` parity).
 * Scoped per `manager_id` (e.g. "voice" or MCP chat session ID).
 */
object JarvisMissionStore {
    private const val PREFS_NAME = "jarvis_missions"
    private const val KEY_WORKSPACES = "workspaces_json"

    data class MissionStep(
        val index: Int,
        val text: String,
        val status: String = "todo", // todo | doing | done | blocked
        val note: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("index", index)
            .put("text", text)
            .put("status", status)
            .put("note", note)

        companion object {
            fun fromJson(o: JSONObject, fallbackIdx: Int): MissionStep = MissionStep(
                index = o.optInt("index", fallbackIdx),
                text = o.optString("text"),
                status = o.optString("status", "todo").ifBlank { "todo" },
                note = o.optString("note")
            )
        }
    }

    data class Mission(
        val goal: String = "",
        val doneWhen: String = "",
        val steps: List<MissionStep> = emptyList(),
        val status: String = "active", // active | done | paused
        val autopilot: Boolean = false,
        val notes: String = "",
        val updatedAt: Long = System.currentTimeMillis()
    ) {
        val isEmpty: Boolean get() = goal.isBlank()
        val doneCount: Int get() = steps.count { it.status == "done" }

        fun toJson(): JSONObject = JSONObject()
            .put("goal", goal)
            .put("doneWhen", doneWhen)
            .put("status", status)
            .put("autopilot", autopilot)
            .put("notes", notes)
            .put("updatedAt", updatedAt)
            .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

        companion object {
            fun fromJson(o: JSONObject): Mission {
                val arr = o.optJSONArray("steps") ?: JSONArray()
                val steps = (0 until arr.length()).mapNotNull { idx ->
                    arr.optJSONObject(idx)?.let { MissionStep.fromJson(it, idx + 1) }
                }
                return Mission(
                    goal = o.optString("goal"),
                    doneWhen = o.optString("doneWhen"),
                    steps = steps,
                    status = o.optString("status", "active").ifBlank { "active" },
                    autopilot = o.optBoolean("autopilot", false),
                    notes = o.optString("notes"),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            }
        }
    }

    data class Team(
        val name: String,
        val agent: AgentKind,
        val model: String = "",
        val role: String = "",
        val project: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("agent", agent.name)
            .put("model", model)
            .put("role", role)
            .put("project", project)

        companion object {
            fun fromJson(o: JSONObject): Team {
                val agent = runCatching { AgentKind.valueOf(o.optString("agent", AgentKind.CLAUDE.name)) }
                    .getOrDefault(AgentKind.CLAUDE)
                return Team(
                    name = o.optString("name"),
                    agent = agent,
                    model = o.optString("model"),
                    role = o.optString("role"),
                    project = o.optString("project")
                )
            }
        }
    }

    data class ManagerWorkspace(
        val managerId: String,
        val mission: Mission = Mission(),
        val teams: List<Team> = emptyList()
    )

    private var prefs: SharedPreferences? = null
    private val workspaces = ConcurrentHashMap<String, ManagerWorkspace>()
    private val _state = MutableStateFlow<Map<String, ManagerWorkspace>>(emptyMap())
    val state: StateFlow<Map<String, ManagerWorkspace>> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            loadFromPrefs(p)
        }
    }

    fun managerIdFromArgs(args: JSONObject): String {
        val explicit = args.optString("manager_id").trim()
        if (explicit.isNotEmpty()) return explicit
        val session = args.optString("_mcp_session").trim()
        if (session.isNotEmpty()) return session
        return "voice"
    }

    fun getWorkspace(managerId: String): ManagerWorkspace {
        return workspaces[managerId] ?: ManagerWorkspace(managerId)
    }

    fun setMission(
        managerId: String,
        goal: String,
        doneWhen: String,
        rawSteps: String,
        autopilot: Boolean
    ): Mission {
        val parsedSteps = rawSteps
            .lineSequence()
            .map { it.trim().replace(Regex("^\\d+[.)]\\s*|^[-*]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { idx, line -> MissionStep(index = idx + 1, text = line) }
            .toList()
        val mission = Mission(
            goal = goal.trim(),
            doneWhen = doneWhen.trim(),
            steps = parsedSteps,
            status = "active",
            autopilot = autopilot
        )
        val prev = getWorkspace(managerId)
        workspaces[managerId] = prev.copy(mission = mission)
        save()
        return mission
    }

    fun updateMission(
        managerId: String,
        stepNum: Int?,
        stepStatus: String?,
        stepNote: String?,
        addStepsRaw: String?,
        missionStatus: String?,
        autopilot: Boolean?,
        notes: String?
    ): Mission {
        val prev = getWorkspace(managerId)
        var m = prev.mission
        if (m.isEmpty && !addStepsRaw.isNullOrBlank()) {
            m = m.copy(goal = "Active mission")
        }
        val mutableSteps = m.steps.toMutableList()
        if (stepNum != null && stepNum in 1..mutableSteps.size) {
            val cur = mutableSteps[stepNum - 1]
            mutableSteps[stepNum - 1] = cur.copy(
                status = stepStatus?.takeIf { it in listOf("todo", "doing", "done", "blocked") } ?: cur.status,
                note = stepNote ?: cur.note
            )
        }
        if (!addStepsRaw.isNullOrBlank()) {
            addStepsRaw.lineSequence()
                .map { it.trim().replace(Regex("^\\d+[.)]\\s*|^[-*]\\s*"), "").trim() }
                .filter { it.isNotEmpty() }
                .forEach { text ->
                    mutableSteps.add(MissionStep(index = mutableSteps.size + 1, text = text))
                }
        }
        val next = m.copy(
            steps = mutableSteps,
            status = missionStatus?.takeIf { it in listOf("active", "done", "paused") } ?: m.status,
            autopilot = autopilot ?: m.autopilot,
            notes = notes ?: m.notes,
            updatedAt = System.currentTimeMillis()
        )
        workspaces[managerId] = prev.copy(mission = next)
        save()
        return next
    }

    fun upsertTeam(managerId: String, team: Team): Team {
        val prev = getWorkspace(managerId)
        val filtered = prev.teams.filterNot { it.name.equals(team.name, ignoreCase = true) } + team
        workspaces[managerId] = prev.copy(teams = filtered)
        save()
        return team
    }

    fun deleteTeam(managerId: String, name: String): Boolean {
        val prev = getWorkspace(managerId)
        val next = prev.teams.filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        if (next.size == prev.teams.size) return false
        workspaces[managerId] = prev.copy(teams = next)
        save()
        return true
    }

    fun getTeam(managerId: String, name: String): Team? {
        return getWorkspace(managerId).teams.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }

    fun buildMissionBrief(managerId: String): String {
        val ws = getWorkspace(managerId)
        val m = ws.mission
        val now = System.currentTimeMillis()
        val tasks = JarvisTasks.tasks.value.filter { it.active || now - it.updatedAt < 1_800_000L }

        return buildString {
            appendLine("<mission_brief manager_id=\"$managerId\">")
            if (m.isEmpty) {
                appendLine("mission: none yet. For any job with more than one step call jarvis_mission_set first (goal, done_when, steps).")
            } else {
                appendLine("mission: ${m.goal}")
                append("status: ${m.status}")
                if (m.steps.isNotEmpty()) {
                    append(" · ${m.doneCount}/${m.steps.size} steps done")
                }
                appendLine(" · autopilot ${if (m.autopilot) "ON (keep going until done_when is met)" else "off"}")
                if (m.doneWhen.isNotBlank()) appendLine("done when: ${m.doneWhen}")
                if (m.steps.isNotEmpty()) {
                    appendLine("steps:")
                    for (s in m.steps) {
                        val icon = when (s.status) {
                            "doing" -> "[>]"
                            "done" -> "[x]"
                            "blocked" -> "[!]"
                            else -> "[ ]"
                        }
                        append("  $icon ${s.index}. ${s.text}")
                        if (s.note.isNotBlank()) append(" — ${s.note}")
                        appendLine()
                    }
                }
                if (m.notes.isNotBlank()) appendLine("notes: ${m.notes.take(600)}")
            }
            if (ws.teams.isNotEmpty()) {
                val summary = ws.teams.joinToString(" · ") { t ->
                    val extras = listOfNotNull(
                        t.agent.name.lowercase(),
                        t.model.takeIf { it.isNotBlank() },
                        t.project.takeIf { it.isNotBlank() }?.substringAfterLast('/')
                    ).joinToString("/")
                    "${t.name} ($extras)"
                }
                appendLine("teams: $summary")
            }
            if (tasks.isEmpty()) {
                appendLine("your tasks: none running")
            } else {
                appendLine("your tasks:")
                for (t in tasks.take(10)) {
                    val ageSec = ((now - t.createdAt).coerceAtLeast(0L)) / 1000L
                    val ageStr = when {
                        ageSec < 60 -> "${ageSec}s"
                        ageSec < 3600 -> "${ageSec / 60}m"
                        else -> "${ageSec / 3600}h${(ageSec % 3600) / 60}m"
                    }
                    append("  - \"${t.title.take(40)}\" id ${t.id.take(8)} · ${t.agent.name.lowercase()} · ${t.state.label} · started $ageStr ago")
                    if (t.note.isNotBlank() && t.note != "Working") append(" · ${t.note.take(60)}")
                    appendLine()
                }
            }
            append("</mission_brief>")
        }
    }

    private fun save() {
        val snap = workspaces.toMap()
        _state.value = snap
        val root = JSONObject()
        snap.forEach { (mgrId, ws) ->
            val obj = JSONObject()
                .put("mission", ws.mission.toJson())
                .put("teams", JSONArray().apply { ws.teams.forEach { put(it.toJson()) } })
            root.put(mgrId, obj)
        }
        prefs?.edit()?.putString(KEY_WORKSPACES, root.toString())?.apply()
    }

    private fun loadFromPrefs(p: SharedPreferences) {
        val raw = p.getString(KEY_WORKSPACES, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            val loaded = mutableMapOf<String, ManagerWorkspace>()
            root.keys().forEach { mgrId ->
                val obj = root.optJSONObject(mgrId) ?: return@forEach
                val m = obj.optJSONObject("mission")?.let { Mission.fromJson(it) } ?: Mission()
                val arr = obj.optJSONArray("teams") ?: JSONArray()
                val teams = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Team.fromJson(it) } }
                loaded[mgrId] = ManagerWorkspace(mgrId, m, teams)
            }
            workspaces.clear()
            workspaces.putAll(loaded)
            _state.value = loaded
        }
    }
}
