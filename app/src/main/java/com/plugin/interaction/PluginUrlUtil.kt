package com.plugin.interaction

import java.net.URI

private val pluginUrlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

/** 플러그인 입력에서 문장 속 URL을 찾고 유효한 항목만 반환합니다. */
fun findPluginURLsFromString(input: String, firstMatchOnly: Boolean = false): List<String> =
    pluginUrlPattern
        .findAll(input)
        .map { match ->
            var url = match.value.trimEnd('.', ',', ';', '!')
            while (url.isNotEmpty()) {
                val opening =
                    when (url.last()) {
                        ')' -> '('
                        ']' -> '['
                        '}' -> '{'
                        else -> break
                    }
                if (url.count { it == url.last() } <= url.count { it == opening }) break
                url = url.dropLast(1).trimEnd('.', ',', ';', '!')
            }
            url
        }
        .filter { url ->
            runCatching { URI(url).let { it.host != null && it.port in -1..65535 } }
                .getOrDefault(false)
        }
        .distinct()
        .take(if (firstMatchOnly) 1 else Int.MAX_VALUE)
        .toList()
