package com.plugin.queue

import com.tencent.mmkv.MMKV
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 원본 작업 백업과 별도 키에 전용 모델을 저장하고 기존 대기열을 이전합니다. */
object PluginTaskBackup {
    private const val KEY = "plugin_task_list_v1"
    private val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true; encodeDefaults = true }

    /** 원본 백업은 보존하고 전용 백업이 없는 경우에만 읽습니다. */
    fun load(): Map<PluginTask, PluginTask.State> {
        val kv = MMKV.defaultMMKV()
        val stored = kv.decodeString(KEY)
        if (stored != null) return decode(stored)
        return kv.decodeString("task_list")?.let { decodeLegacy(it) } ?: emptyMap()
    }

    /** 완료·삭제 상태를 포함한 모든 전용 작업을 저장합니다. */
    fun save(tasks: Map<PluginTask, PluginTask.State>) {
        check(MMKV.defaultMMKV().encode(KEY, encode(tasks))) { "플러그인 대기열 백업 저장에 실패했습니다." }
    }

    /** 저장소 없이도 검증할 수 있도록 전용 대기열을 직렬화합니다. */
    internal fun encode(tasks: Map<PluginTask, PluginTask.State>): String = json.encodeToString(tasks)

    /** 손상된 백업은 빈 대기열로 덮어쓰지 않고 오류를 반환합니다. */
    internal fun decode(value: String): Map<PluginTask, PluginTask.State> = json.decodeFromString(value)

    /** 기존 타입 식별자만 변경해 페이지·완료 경로·생성 시간을 그대로 이전합니다. */
    internal fun decodeLegacy(value: String): Map<PluginTask, PluginTask.State> =
        json.decodeFromJsonElement(migrateTypes(json.parseToJsonElement(value)))

    /** 제목과 URL을 변경하지 않고 직렬화 타입 필드만 재귀적으로 옮깁니다. */
    private fun migrateTypes(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::migrateTypes))
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "type" && value is JsonPrimitive && value.isString &&
                value.content.startsWith("com.junkfood.seal.download.Task.")) {
                JsonPrimitive(value.content.replace("com.junkfood.seal.download.Task.",
                    "com.plugin.queue.PluginTask.").replace("TypeInfo.Instagram", "TypeInfo.SocialMedia"))
            } else migrateTypes(value)
        })
        else -> element
    }
}
