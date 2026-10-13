package com.plugin.compatibility

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.plugin.PluginId
import com.plugin.PluginState

/** 공유 전용 별칭만 변경하며 앱 실행 아이콘과 다운로드 액티비티는 유지합니다. */
object ShareMenuSettings {
    private const val KEY = "plugin_share_destinations"
    const val QUICK = 1
    const val MAIN = 2
    val aliases = listOf("com.plugin.ui.QuickShareAlias", "com.plugin.ui.MainShareAlias")

    /** 알 수 없는 비트와 빈 선택은 기본 빠른 다운로드로 복구합니다. */
    fun normalize(value: Int): Int = (value and (QUICK or MAIN)).takeIf { it != 0 } ?: QUICK

    /** 비활성 플러그인의 세부 선택은 보존하고 노출에는 기본값을 적용합니다. */
    fun selected(): Int = normalize(KEY.getInt(QUICK))

    /** 패키지 관리자 반영에 성공한 선택만 저장합니다. */
    fun save(context: Context, value: Int) {
        val selection = normalize(value)
        apply(context, selection)
        KEY.updateInt(selection)
    }

    /** 앱 시작과 활성 변경에서 저장값을 Android 공유 메뉴에 동기화합니다. */
    fun synchronize(context: Context) = apply(context, if (PluginState.isEnabled(PluginId.SHARE_MENU)) selected() else QUICK)

    /** 먼저 새 진입점을 활성화해 변경 도중 공유 대상이 모두 사라지지 않도록 합니다. */
    private fun apply(context: Context, selection: Int) {
        val manager = context.packageManager
        aliases.indices.sortedBy { if (selection and (1 shl it) != 0) 0 else 1 }.forEach { index ->
            val component = ComponentName(context.packageName, aliases[index])
            val enabled = selection and (1 shl index) != 0
            val requested = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val current = manager.getComponentEnabledSetting(component)
            val effective = if (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) index == 0 else current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (effective != enabled) manager.setComponentEnabledSetting(component, requested, PackageManager.DONT_KILL_APP)
        }
    }
}
