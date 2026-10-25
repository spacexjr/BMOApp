package com.spacexjr.bmo.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

data class ToolResult(
    val ok: Boolean,
    val message: String,
)

/**
 * Allow-listed device actions. The language model can only ask for one of the
 * functions below; it never gets a shell, root, or dynamically evaluated code.
 */
class ActionManager(private val context: Context) {

    data class ToolDefinition(
        val name: String,
        val description: String,
        val arguments: Map<String, String>,
    )

    private val definitions = listOf(
        ToolDefinition("get_current_time", "Retorna a hora local atual.", emptyMap()),
        ToolDefinition("get_current_date", "Retorna a data local atual.", emptyMap()),
        ToolDefinition(
            "open_application",
            "Abre um aplicativo instalado pelo nome ou pacote.",
            mapOf("name" to "nome do app (ex.: WhatsApp) ou pacote (ex.: com.whatsapp)"),
        ),
        ToolDefinition(
            "open_system_settings",
            "Abre uma tela de configurações do Android.",
            mapOf("section" to "wifi, bluetooth, display, sound, battery, apps, accessibility, location, date, settings"),
        ),
        ToolDefinition("get_battery_status", "Retorna o nível e o estado da bateria.", emptyMap()),
        ToolDefinition("get_device_info", "Retorna modelo, Android e arquitetura do aparelho.", emptyMap()),
        ToolDefinition(
            "set_media_volume",
            "Ajusta o volume de mídia entre 0 e 100.",
            mapOf("percent" to "inteiro de 0 a 100"),
        ),
        ToolDefinition("list_installed_apps", "Lista aplicativos com ícone na tela inicial.", emptyMap()),
    )

    fun definitions(): List<ToolDefinition> = definitions

    fun definitionsPrompt(): String = buildString {
        appendLine("Ferramentas permitidas: get_current_time; get_current_date; " +
            "open_application{name}; open_system_settings{section}; " +
            "get_battery_status; get_device_info; set_media_volume{percent}; " +
            "list_installed_apps.")
        appendLine("Para usar uma ferramenta responda APENAS:")
        appendLine("<tool_call>{\"name\":\"ferramenta\",\"arguments\":{...}}</tool_call>")
        append("Se não precisar, responda normalmente.")
    }

    fun execute(name: String, arguments: JSONObject): ToolResult {
        return try {
            when (name) {
                "get_current_time" -> {
                    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    ToolResult(true, "Agora são $time.")
                }
                "get_current_date" -> {
                    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    ToolResult(true, "Hoje é $date.")
                }
                "open_application" -> openApplication(
                    arguments.optString("name").ifBlank { arguments.optString("package_name") }
                )
                "open_system_settings" -> openSettings(arguments.optString("section", "settings"))
                "get_battery_status" -> batteryStatus()
                "get_device_info" -> ToolResult(
                    true,
                    "Aparelho ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, arquitetura ${Build.SUPPORTED_ABIS.firstOrNull() ?: "desconhecida"}."
                )
                "set_media_volume" -> setVolume(arguments.optInt("percent", -1))
                "list_installed_apps" -> listApps()
                else -> ToolResult(false, "Ação não permitida: $name.")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "action $name failed", t)
            ToolResult(false, "A ação $name falhou: ${t.message ?: t.javaClass.simpleName}.")
        }
    }

    private fun openApplication(query: String): ToolResult {
        if (query.isBlank()) return ToolResult(false, "Informe o nome ou o pacote do aplicativo.")
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        val normalized = query.trim().lowercase(Locale.ROOT)
        val match = resolved.firstOrNull { info ->
            info.activityInfo.packageName.equals(query.trim(), ignoreCase = true) ||
                info.loadLabel(pm).toString().lowercase(Locale.ROOT) == normalized
        } ?: resolved.firstOrNull { info ->
            info.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(normalized)
        }
        if (match == null) {
            return ToolResult(false, "Não encontrei o aplicativo \"$query\" instalado neste aparelho.")
        }
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ToolResult(false, "\"${match.loadLabel(pm)}\" não possui uma tela inicial que eu possa abrir.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "Abri o aplicativo ${match.loadLabel(pm)} (${match.activityInfo.packageName}).")
    }

    private fun openSettings(section: String): ToolResult {
        val action = when (section.lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "tela" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "som" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps", "aplicativos" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility", "acessibilidade" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location", "localizacao", "localização" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "date", "data" -> Settings.ACTION_DATE_SETTINGS
            "storage", "armazenamento" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult(true, "Abri a tela de configurações: ${section.ifBlank { "geral" }}.")
    }

    private fun batteryStatus(): ToolResult {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        val state = if (charging) "carregando" else "na bateria"
        return ToolResult(true, "A bateria está em $level% e o aparelho está $state.")
    }

    private fun setVolume(percent: Int): ToolResult {
        if (percent !in 0..100) return ToolResult(false, "O volume precisa ser um número entre 0 e 100.")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent / 100.0 * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        return ToolResult(true, "Ajustei o volume de mídia para $percent%.")
    }

    private fun listApps(): ToolResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
        val json = JSONArray()
        apps.take(60).forEach { (label, pkg) ->
            json.put(JSONObject().put("nome", label).put("pacote", pkg))
        }
        return ToolResult(true, json.toString())
    }

    companion object {
        private const val TAG = "BmoActionManager"
    }
}
